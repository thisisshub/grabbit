package dev.usenetstream.engine.sevenz

import dev.usenetstream.engine.rar.NotStreamableException
import dev.usenetstream.engine.stream.RandomAccessSource
import dev.usenetstream.engine.stream.readFully
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException(message: String) : IOException(message)

/** One coder in a folder: method id bytes (00 = Copy, 06F10701 = AES-256 + SHA-256, 030101 = LZMA, 21 = LZMA2) + props. */
class Coder(val method: Long, val props: ByteArray)

class Folder(
    val coders: List<Coder>,
    val unpackSizes: List<Long>,
    val crc: Long?,
    /** (input index, output index) links: that coder's output feeds that coder's input. */
    val bindPairs: List<Pair<Int, Int>> = emptyList(),
) {
    /** Final output: the output stream no bind pair consumes. */
    val unpackSize: Long get() = unpackSizes[(unpackSizes.indices).first { o -> bindPairs.none { it.second == o } }]

    /** Coder indices in data order, packed input first (simple 1-in/1-out coders only). */
    val chain: List<Int>
        get() {
            var cur = coders.indices.first { c -> bindPairs.none { it.first == c } }
            val order = mutableListOf(cur)
            while (true) {
                cur = bindPairs.firstOrNull { it.second == cur }?.first ?: break
                order += cur
            }
            return order
        }
}

/** A file inside the archive: [folder] index, and where it starts in that folder's unpacked bytes. */
class SevenZEntry(val name: String, val size: Long, val folder: Int, val offsetInFolder: Long)

class SevenZArchive(
    /** Absolute offset (in the joined volumes) of each folder's packed data, and its packed size. */
    val packStart: List<Long>,
    val packSize: List<Long>,
    val folders: List<Folder>,
    val entries: List<SevenZEntry>,
)

/**
 * Reads 7z archives just far enough to stream a file out of them: the (possibly encrypted and LZMA-packed)
 * header, and folders that store data as-is (Copy) or encrypted (7zAES). Compressed data can't be streamed.
 */
object SevenZ {
    private val MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    const val COPY = 0x00L
    const val AES = 0x06F10701L
    const val LZMA = 0x030101L
    const val LZMA2 = 0x21L

    fun isSevenZ(head: ByteArray): Boolean = head.size >= 6 && MAGIC.indices.all { head[it] == MAGIC[it] }

    fun parse(src: RandomAccessSource, password: String?): SevenZArchive {
        val start = src.readFully(0, 32)
        if (!isSevenZ(start)) throw IOException("not a 7z archive")
        val le = java.nio.ByteBuffer.wrap(start).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val nextOffset = le.getLong(12)
        val nextSize = le.getLong(20)
        if (nextSize <= 0 || nextSize > 64L * 1024 * 1024) throw IOException("7z header size $nextSize")
        var header = src.readFully(32 + nextOffset, nextSize.toInt())
        if (crc(header) != (le.getInt(28).toLong() and 0xFFFFFFFFL)) throw IOException("7z header is damaged (CRC)")

        // An "encoded" header is a small archive of its own: one folder whose output is the real header.
        while (header.firstOrNull() == K_ENCODED_HEADER) {
            val r = Reader(header, 1)
            val info = r.streamsInfo()
            val f = info.folders.single()
            val packed = src.readFully(32 + info.packPos, info.packSizes.first().toInt())
            header = try {
                decodeFolder(packed, f, password)
            } catch (e: WrongPasswordException) {
                throw e
            } catch (e: IOException) {
                // A wrong key turns the encrypted index into noise, which the LZMA decoder rejects.
                if (f.coders.any { it.method == AES }) throw WrongPasswordException("wrong archive password") else throw e
            }
            if (f.crc != null && crc(header) != f.crc) {
                throw if (f.coders.any { it.method == AES }) WrongPasswordException("wrong archive password") else IOException("7z header CRC")
            }
        }
        if (header.firstOrNull() != K_HEADER) throw IOException("7z header type ${header.firstOrNull()}")
        return Reader(header, 1).header()
    }

    /** The bytes of [entry], decrypted on the fly when the folder is AES; seekable. */
    fun open(src: RandomAccessSource, archive: SevenZArchive, entry: SevenZEntry, password: String?): RandomAccessSource {
        val folder = archive.folders[entry.folder]
        val packed = Slice(src, archive.packStart[entry.folder], archive.packSize[entry.folder])
        val methods = folder.coders.map { it.method }
        val unpacked: RandomAccessSource = when {
            methods == listOf(COPY) || methods.isEmpty() -> packed
            methods == listOf(AES) -> AesCbcSource(packed, aesKey(folder.coders[0].props, password), folder.unpackSize)
            // "Copy then encrypt" in two coders is the same bytes as AES alone.
            methods.toSet() == setOf(COPY, AES) && folder.coders.size == 2 -> AesCbcSource(packed, aesKey(folder.coders.first { it.method == AES }.props, password), folder.unpackSize)
            else -> throw NotStreamableException("compressed 7z (${methods.joinToString { methodName(it) }}); download needed")
        }
        return Slice(unpacked, entry.offsetInFolder, entry.size)
    }

    fun methodName(m: Long) = when (m) {
        COPY -> "Copy"; AES -> "AES"; LZMA -> "LZMA"; LZMA2 -> "LZMA2"; else -> "0x%X".format(m)
    }

    // ------------------------------------------------------------ coders

    private fun decodeFolder(packed: ByteArray, f: Folder, password: String?): ByteArray {
        // Simple chains only (each coder 1 in / 1 out), in the order the bind pairs link them.
        var data = packed
        for (i in f.chain) {
            val c = f.coders[i]
            val outSize = f.unpackSizes[i]
            data = when (c.method) {
                COPY -> data
                AES -> {
                    if (data.size % 16 != 0) throw IOException("7z AES stream not block aligned")
                    val key = aesKey(c.props, password)
                    Cipher.getInstance("AES/CBC/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key.key, "AES"), IvParameterSpec(key.iv))
                        doFinal(data)
                    }.copyOf(outSize.toInt())
                }
                LZMA -> {
                    val dict = java.nio.ByteBuffer.wrap(c.props, 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    org.tukaani.xz.LZMAInputStream(ByteArrayInputStream(data), outSize, c.props[0], dict).use { it.readNBytesCompat(outSize.toInt()) }
                }
                LZMA2 -> org.tukaani.xz.LZMA2InputStream(ByteArrayInputStream(data), lzma2Dict(c.props[0])).use { it.readNBytesCompat(outSize.toInt()) }
                else -> throw IOException("7z header uses ${methodName(c.method)}")
            }
        }
        return data
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = read(out, got, n - got)
            if (r < 0) throw IOException("7z header ended early")
            got += r
        }
        return out
    }

    private fun lzma2Dict(p: Byte): Int {
        val b = p.toInt() and 0x3F
        return if (b >= 40) -1 else (2 or (b and 1)) shl (b / 2 + 11)
    }

    class AesKey(val key: ByteArray, val iv: ByteArray)

    /** 7-Zip's key: SHA-256 over (salt + UTF-16LE password + counter) repeated 2^power times. */
    fun aesKey(props: ByteArray, password: String?): AesKey {
        if (password == null) throw WrongPasswordException("archive is password protected and the NZB has no password")
        val b0 = props[0].toInt() and 0xFF
        val power = b0 and 0x3F
        var saltSize = 0
        var ivSize = 0
        if (b0 and 0xC0 != 0) {
            val b1 = props[1].toInt() and 0xFF
            saltSize = ((b0 shr 7) and 1) + (b1 shr 4)
            ivSize = ((b0 shr 6) and 1) + (b1 and 0x0F)
        }
        val salt = props.copyOfRange(2, 2 + saltSize)
        val iv = props.copyOfRange(2 + saltSize, 2 + saltSize + ivSize).copyOf(16)
        val pw = password.toByteArray(Charsets.UTF_16LE)
        val key = if (power == 0x3F) {
            (salt + pw).copyOf(32)
        } else {
            val sha = MessageDigest.getInstance("SHA-256")
            val counter = ByteArray(8)
            for (round in 0L until (1L shl power)) {
                sha.update(salt)
                sha.update(pw)
                sha.update(counter)
                // Little-endian 64-bit counter increment.
                var k = 0
                while (k < 8) { counter[k]++; if (counter[k].toInt() != 0) break; k++ }
            }
            sha.digest()
        }
        return AesKey(key, iv)
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    // ------------------------------------------------------------ header reader

    private const val K_END = 0x00
    private const val K_HEADER: Byte = 0x01
    private const val K_ARCHIVE_PROPERTIES = 0x02
    private const val K_ADDITIONAL_STREAMS = 0x03
    private const val K_MAIN_STREAMS = 0x04
    private const val K_FILES = 0x05
    private const val K_PACK_INFO = 0x06
    private const val K_UNPACK_INFO = 0x07
    private const val K_SUBSTREAMS = 0x08
    private const val K_SIZE = 0x09
    private const val K_CRC = 0x0A
    private const val K_FOLDER = 0x0B
    private const val K_UNPACK_SIZE = 0x0C
    private const val K_NUM_UNPACK_STREAM = 0x0D
    private const val K_EMPTY_STREAM = 0x0E
    private const val K_NAME = 0x11
    private const val K_ENCODED_HEADER: Byte = 0x17

    private class StreamsInfo(
        val packPos: Long,
        val packSizes: List<Long>,
        val folders: List<Folder>,
        val folderPackStreams: List<Int>,
        val subStreamSizes: List<List<Long>>,
    )

    private class Reader(val b: ByteArray, var i: Int) {
        var lastBindPairs: List<Pair<Int, Int>> = emptyList()
        private val folderPairs = ArrayList<List<Pair<Int, Int>>>()

        fun byte(): Int = (b.getOrNull(i++) ?: throw IOException("7z header truncated")).toInt() and 0xFF

        /** 7z variable-length number: leading 1-bits of the first byte say how many extra bytes follow. */
        fun num(): Long {
            val first = byte()
            var mask = 0x80
            var value = 0L
            for (n in 0 until 8) {
                if (first and mask == 0) {
                    val high = (first and (mask - 1)).toLong()
                    return value or (high shl (8 * n))
                }
                value = value or (byte().toLong() shl (8 * n))
                mask = mask shr 1
            }
            return value
        }

        fun int(): Int = num().also { if (it < 0 || it > Int.MAX_VALUE) throw IOException("7z count $it") }.toInt()

        fun bits(n: Int): BooleanArray {
            val out = BooleanArray(n)
            var mask = 0
            var cur = 0
            for (k in 0 until n) {
                if (mask == 0) { cur = byte(); mask = 0x80 }
                out[k] = cur and mask != 0
                mask = mask shr 1
            }
            return out
        }

        fun digests(n: Int): List<Long?> {
            val all = byte() != 0
            val defined = if (all) BooleanArray(n) { true } else bits(n)
            return defined.map { d ->
                if (!d) null else (byte().toLong() or (byte().toLong() shl 8) or (byte().toLong() shl 16) or (byte().toLong() shl 24))
            }
        }

        fun header(): SevenZArchive {
            var info: StreamsInfo? = null
            var entries = emptyList<SevenZEntry>()
            while (true) {
                when (val t = byte()) {
                    K_END -> break
                    K_ARCHIVE_PROPERTIES -> while (true) { val p = byte(); if (p == 0) break; i += int() }
                    K_ADDITIONAL_STREAMS -> streamsInfo()
                    K_MAIN_STREAMS -> info = streamsInfo()
                    K_FILES -> entries = files(info)
                    else -> throw IOException("7z header property $t")
                }
            }
            val s = info ?: return SevenZArchive(emptyList(), emptyList(), emptyList(), entries)
            // Pack streams follow each other after the 32-byte start header; folder f starts at its first one.
            val starts = ArrayList<Long>()
            var pos = 32 + s.packPos
            for (size in s.packSizes) { starts += pos; pos += size }
            var p = 0
            val folderStart = ArrayList<Long>()
            val folderSize = ArrayList<Long>()
            for (n in s.folderPackStreams) {
                folderStart += starts.getOrElse(p) { pos }
                folderSize += s.packSizes.subList(p, p + n).sum()
                p += n
            }
            return SevenZArchive(folderStart, folderSize, s.folders, entries)
        }

        fun streamsInfo(): StreamsInfo {
            var packPos = 0L
            var packSizes = emptyList<Long>()
            var folders = emptyList<Folder>()
            var packStreams = emptyList<Int>()
            var sub: List<List<Long>>? = null
            while (true) {
                when (val t = byte()) {
                    K_END -> break
                    K_PACK_INFO -> {
                        packPos = num()
                        val n = int()
                        while (true) {
                            when (byte()) {
                                K_END -> break
                                K_SIZE -> packSizes = List(n) { num() }
                                K_CRC -> digests(n)
                                else -> throw IOException("7z pack info")
                            }
                        }
                    }
                    K_UNPACK_INFO -> {
                        if (byte() != K_FOLDER) throw IOException("7z unpack info")
                        val n = int()
                        if (byte() != 0) throw IOException("7z external folders")
                        folderPairs.clear()
                        val raw = List(n) { folder().also { folderPairs += lastBindPairs } }
                        packStreams = raw.map { it.second }
                        if (byte() != K_UNPACK_SIZE) throw IOException("7z unpack sizes")
                        val sizes = raw.map { (outs, _) -> List(outs.second) { num() } }
                        var crcs: List<Long?> = List(n) { null }
                        while (true) {
                            when (byte()) {
                                K_END -> break
                                K_CRC -> crcs = digests(n)
                                else -> throw IOException("7z unpack info")
                            }
                        }
                        folders = raw.mapIndexed { k, (outs, _) -> Folder(outs.first, sizes[k], crcs[k], folderPairs[k]) }
                    }
                    K_SUBSTREAMS -> sub = subStreams(folders)
                    else -> throw IOException("7z streams info $t")
                }
            }
            return StreamsInfo(packPos, packSizes, folders, packStreams, sub ?: folders.map { listOf(it.unpackSize) })
        }

        /** Returns (coders to total outputs) and number of packed input streams. */
        fun folder(): Pair<Pair<List<Coder>, Int>, Int> {
            val n = int()
            val coders = ArrayList<Coder>()
            var ins = 0
            var outs = 0
            repeat(n) {
                val flags = byte()
                val idSize = flags and 0x0F
                var id = 0L
                repeat(idSize) { id = (id shl 8) or byte().toLong() }
                if (flags and 0x10 != 0) { ins += int(); outs += int() } else { ins++; outs++ }
                val props = if (flags and 0x20 != 0) { val sz = int(); b.copyOfRange(i, i + sz).also { i += sz } } else ByteArray(0)
                coders += Coder(id, props)
            }
            val pairs = List(outs - 1) { int() to int() }
            val packed = ins - pairs.size
            if (packed > 1) repeat(packed) { num() }
            lastBindPairs = pairs
            return (coders to outs) to packed
        }

        fun subStreams(folders: List<Folder>): List<List<Long>> {
            var counts = folders.map { 1 }
            var t = byte()
            if (t == K_NUM_UNPACK_STREAM) { counts = folders.map { int() }; t = byte() }
            val sizes = folders.mapIndexed { k, f -> if (counts[k] == 0) mutableListOf() else mutableListOf(f.unpackSize) }.toMutableList()
            if (t == K_SIZE) {
                folders.forEachIndexed { k, f ->
                    if (counts[k] == 0) return@forEachIndexed
                    val list = ArrayList<Long>()
                    var sum = 0L
                    repeat(counts[k] - 1) { val s = num(); list += s; sum += s }
                    list += f.unpackSize - sum
                    sizes[k] = list
                }
                t = byte()
            }
            while (t != K_END) {
                if (t == K_CRC) {
                    val unknown = folders.indices.sumOf { k -> if (counts[k] == 1 && folders[k].crc != null) 0 else counts[k] }
                    digests(unknown)
                } else {
                    throw IOException("7z substreams $t")
                }
                t = byte()
            }
            return sizes
        }

        fun files(info: StreamsInfo?): List<SevenZEntry> {
            val n = int()
            var empty = BooleanArray(n)
            var names = List(n) { "" }
            while (true) {
                val t = byte()
                if (t == K_END) break
                val size = int()
                val end = i + size
                when (t) {
                    K_EMPTY_STREAM -> empty = bits(n)
                    K_NAME -> {
                        if (byte() != 0) throw IOException("7z external names")
                        val text = String(b, i, end - i, Charsets.UTF_16LE)
                        names = text.split('\u0000').take(n)
                    }
                }
                i = end
            }
            // Non-empty files take the substreams in order, folder by folder.
            val streams = info?.subStreamSizes.orEmpty().flatMapIndexed { folder, sizes ->
                var offset = 0L
                sizes.map { s -> Triple(folder, offset, s).also { offset += s } }
            }
            var next = 0
            return (0 until n).mapNotNull { k ->
                if (empty[k]) return@mapNotNull null
                val (folder, offset, size) = streams.getOrNull(next++) ?: return@mapNotNull null
                SevenZEntry(names.getOrElse(k) { "file$k" }, size, folder, offset)
            }
        }
    }
}

/** [length] bytes of [src] starting at [start]. */
class Slice(private val src: RandomAccessSource, private val start: Long, override val length: Long) : RandomAccessSource {
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        val n = minOf(length.toLong(), this.length - position).toInt()
        return src.read(start + position, buffer, offset, n)
    }
}

/** Volumes back to back, as one source. */
class JoinedSource(private val parts: List<RandomAccessSource>) : RandomAccessSource {
    private val starts = LongArray(parts.size).also { var p = 0L; parts.forEachIndexed { k, s -> it[k] = p; p += s.length } }
    override val length: Long = parts.sumOf { it.length }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        var k = starts.indexOfLast { it <= position }
        val inPart = position - starts[k]
        val n = minOf(length.toLong(), parts[k].length - inPart).toInt()
        return parts[k].read(inPart, buffer, offset, n)
    }

    /** Which volume holds [position] (for cache hints). */
    fun partAt(position: Long): Int = starts.indexOfLast { it <= position }
}

/**
 * AES-256-CBC decryption with random access: block k is decrypted with ciphertext block k-1 as its IV,
 * so a seek only needs the 16 bytes before it.
 */
class AesCbcSource(private val cipherText: RandomAccessSource, private val key: SevenZ.AesKey, override val length: Long) : RandomAccessSource {
    private val spec = SecretKeySpec(key.key, "AES")
    private val cipher = ThreadLocal.withInitial { Cipher.getInstance("AES/CBC/NoPadding") }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        val want = minOf(length.toLong(), this.length - position).toInt()
        val blockStart = position / 16 * 16
        val end = minOf(((position + want + 15) / 16) * 16, cipherText.length)
        val iv = if (blockStart == 0L) key.iv else cipherText.readFully(blockStart - 16, 16)
        val enc = cipherText.readFully(blockStart, (end - blockStart).toInt())
        val plain = cipher.get().run {
            init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
            doFinal(enc)
        }
        val skip = (position - blockStart).toInt()
        val n = minOf(want, plain.size - skip)
        System.arraycopy(plain, skip, buffer, offset, n)
        return n
    }
}
