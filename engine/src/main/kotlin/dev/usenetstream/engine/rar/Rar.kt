package dev.usenetstream.engine.rar

import dev.usenetstream.engine.stream.RandomAccessSource
import dev.usenetstream.engine.stream.VirtualFile
import dev.usenetstream.engine.stream.readFully
import java.io.IOException

class NotStreamableException(message: String) : IOException(message)

/** Where one RAR volume keeps its slice of the packed file. */
data class RarVolume<S : RandomAccessSource>(
    val source: S,
    val sourceName: String,
    /** null when the archive does not record it (old RAR4). */
    val volumeNumber: Int?,
    val isFirstVolume: Boolean,
    val fileName: String,
    val dataStart: Long,
    val dataSize: Long,
    val unpackedSize: Long,
    val stored: Boolean,
    val encrypted: Boolean,
    val splitBefore: Boolean,
)

/**
 * Reads just enough RAR4 / RAR5 headers to find the first packed file in each volume.
 * Only "store" (-m0) archives can be streamed: their packed bytes are the file bytes.
 */
object RarParser {
    private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    fun isRar(head: ByteArray) = head.startsWith(RAR5) || head.startsWith(RAR4)

    /** null if [source] is not a RAR volume or holds no file header. */
    /**
     * [lookupVolumeNumber] = false skips RAR4's end-of-volume read (one extra article per volume) when the
     * caller can order volumes by name instead.
     */
    fun <S : RandomAccessSource> parse(source: S, sourceName: String, lookupVolumeNumber: Boolean = true): RarVolume<S>? {
        if (source.length < 8) return null
        val head = source.readFully(0, 8)
        return when {
            head.startsWith(RAR5) -> parse5(source, sourceName)
            head.startsWith(RAR4) -> parse4(source, sourceName, lookupVolumeNumber)
            else -> null
        }
    }

    // ---- RAR 1.5-4.x: 7-byte base block header ----

    private fun <S : RandomAccessSource> parse4(src: S, sourceName: String, lookupVolumeNumber: Boolean): RarVolume<S>? {
        var pos = 7L
        var isFirst = false
        while (pos + 7 <= src.length) {
            val base = src.readFully(pos, 7)
            val type = base[2].toInt() and 0xFF
            val flags = base.u16(3)
            val headSize = base.u16(5)
            if (headSize < 7) throw IOException("$sourceName: bad RAR4 header size $headSize at $pos")
            when (type) {
                0x73 -> { // main header
                    if (flags and 0x0080 != 0) throw NotStreamableException("encrypted RAR (headers)")
                    isFirst = flags and 0x0100 != 0
                    pos += headSize
                }
                0x74 -> { // file header; PACK_SIZE doubles as ADD_SIZE
                    val h = src.readFully(pos, headSize)
                    var packed = h.u32(7)
                    var unpacked = h.u32(11)
                    val method = h[25].toInt() and 0xFF
                    val nameSize = h.u16(26)
                    var nameAt = 32
                    if (flags and 0x0100 != 0) { // LHD_LARGE
                        packed = packed or (h.u32(32) shl 32)
                        unpacked = unpacked or (h.u32(36) shl 32)
                        nameAt = 40
                    }
                    val rawName = h.copyOfRange(nameAt, nameAt + nameSize)
                    // LHD_UNICODE: ASCII name, NUL, then a packed Unicode form we don't need.
                    val ascii = rawName.indexOf(0).let { if (it >= 0) rawName.copyOf(it) else rawName }
                    val dataStart = pos + headSize
                    return RarVolume(
                        source = src,
                        sourceName = sourceName,
                        volumeNumber = if (lookupVolumeNumber) rar4VolumeNumber(src, dataStart + packed) else null,
                        isFirstVolume = isFirst,
                        fileName = String(ascii, Charsets.UTF_8).replace('\\', '/'),
                        dataStart = dataStart,
                        dataSize = packed,
                        unpackedSize = unpacked,
                        stored = method == 0x30,
                        encrypted = flags and 0x0004 != 0,
                        splitBefore = flags and 0x0001 != 0,
                    )
                }
                0x7B -> return null // end of archive, no file
                else -> {
                    val add = if (flags and 0x8000 != 0) src.readFully(pos + 7, 4).u32(0) else 0L
                    pos += headSize + add
                }
            }
        }
        return null
    }

    /** Volume number lives in the end-of-archive block (EARC_VOLNUMBER); walk any later blocks to reach it. */
    private fun rar4VolumeNumber(src: RandomAccessSource, from: Long): Int? {
        var at = from
        repeat(64) {
            if (at + 7 > src.length) return null
            val base = src.readFully(at, 7)
            val type = base[2].toInt() and 0xFF
            val flags = base.u16(3)
            val headSize = base.u16(5)
            if (headSize < 7) return null
            if (type == 0x7B) {
                if (flags and 0x0008 == 0) return null
                val off = at + 7 + if (flags and 0x0002 != 0) 4 else 0
                if (off + 2 > src.length) return null
                return src.readFully(off, 2).u16(0)
            }
            val add = when {
                type == 0x74 -> src.readFully(at + 7, 4).u32(0) or
                    if (flags and 0x0100 != 0) src.readFully(at + 32, 4).u32(0) shl 32 else 0L
                flags and 0x8000 != 0 -> src.readFully(at + 7, 4).u32(0)
                else -> 0L
            }
            at += headSize + add
        }
        return null
    }

    // ---- RAR 5.x: CRC32 + vint-sized headers ----

    private fun <S : RandomAccessSource> parse5(src: S, sourceName: String): RarVolume<S>? {
        var pos = 8L
        var volumeNumber: Int? = null
        while (pos + 5 <= src.length) {
            val pre = src.readFully(pos, minOf(4 + 10, (src.length - pos).toInt()))
            val sizeField = Cursor(pre, 4)
            val headSize = sizeField.vint()
            val headerStart = pos + sizeField.at
            val h = Cursor(src.readFully(headerStart, headSize.toInt()), 0)
            val type = h.vint().toInt()
            val flags = h.vint()
            val extraSize = if (flags and 0x0001L != 0L) h.vint() else 0L
            val dataSize = if (flags and 0x0002L != 0L) h.vint() else 0L
            when (type) {
                1 -> { // main
                    val archiveFlags = h.vint()
                    volumeNumber = if (archiveFlags and 0x0002L != 0L) h.vint().toInt() else 0
                }
                2 -> { // file
                    val fileFlags = h.vint()
                    val unpacked = h.vint()
                    h.vint() // attributes
                    if (fileFlags and 0x0002L != 0L) h.skip(4) // mtime
                    if (fileFlags and 0x0004L != 0L) h.skip(4) // data CRC32
                    val compression = h.vint()
                    h.vint() // host OS
                    val nameLength = h.vint().toInt()
                    val name = String(h.bytes, h.at, nameLength, Charsets.UTF_8)
                    return RarVolume(
                        source = src,
                        sourceName = sourceName,
                        volumeNumber = volumeNumber,
                        isFirstVolume = volumeNumber == 0,
                        fileName = name,
                        dataStart = headerStart + headSize,
                        dataSize = dataSize,
                        unpackedSize = unpacked,
                        stored = (compression shr 7) and 0x7 == 0L,
                        encrypted = hasEncryptionRecord(h.bytes, (headSize - extraSize).toInt(), headSize.toInt()),
                        splitBefore = flags and 0x0008L != 0L,
                    )
                }
                4 -> throw NotStreamableException("encrypted RAR (headers)")
                5 -> return null // end of archive
            }
            pos = headerStart + headSize + dataSize
        }
        return null
    }

    /** File-header extra area: records of (size vint, type vint, ...); type 1 = file encryption. */
    private fun hasEncryptionRecord(bytes: ByteArray, from: Int, to: Int): Boolean {
        val c = Cursor(bytes, from)
        while (c.at < to) {
            val size = c.vint().toInt()
            val next = c.at + size
            if (c.vint() == 1L) return true
            c.at = next
        }
        return false
    }

    private class Cursor(val bytes: ByteArray, var at: Int) {
        fun vint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (at >= bytes.size) throw IOException("truncated RAR5 header")
                val b = bytes[at++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 63) throw IOException("bad RAR5 vint")
            }
        }

        fun skip(n: Int) {
            at += n
        }
    }

    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(i: Int): Long =
        (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/** The packed file stitched back together from its volume slices. */
private val PART_N = Regex("""(?i)\.part(\d+)\.rar$""")
private val OLD_STYLE = Regex("""(?i)\.(rar|r(\d{2,3}))$""")

/** `x.part07.rar` -> 7; old style `x.rar` -> -1, `x.r00` -> 0. null if the name says nothing. */
fun volumeIndexFromName(name: String): Int? {
    PART_N.find(name)?.let { return it.groupValues[1].toInt() }
    val m = OLD_STYLE.find(name) ?: return null
    return m.groupValues[2].toIntOrNull() ?: -1
}

class RarStoreStream(volumes: List<RarVolume<out RandomAccessSource>>) : RandomAccessSource {
    private val parts = volumes.filter { it.dataSize > 0 }
    private val starts = LongArray(parts.size)
    override val length: Long

    init {
        var total = 0L
        parts.forEachIndexed { i, v ->
            starts[i] = total
            total += v.dataSize
        }
        length = total
    }

    @Volatile private var current = -1

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        var i = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }
        if (i != current) {
            // Entered another volume: free the ones we left (each holds ~15 MB of segments; a 64-volume
            // set otherwise grows until a small TV heap runs out). Neighbours stay for small back-seeks.
            current = i
            parts.forEachIndexed { j, v -> if (j < i - 1 || j > i + 1) (v.source as? VirtualFile)?.dropCache() }
        }
        val part = parts[i]
        val local = position - starts[i]
        val n = minOf(length.toLong(), part.dataSize - local).toInt()
        // Fetch the next volume's start before the player hits the boundary.
        if (part.dataSize - local < PREFETCH_NEXT_BYTES && ++i < parts.size) {
            (parts[i].source as? VirtualFile)?.warm(parts[i].dataStart)
        }
        return part.source.read(part.dataStart + local, buffer, offset, n)
    }

    companion object {
        private const val PREFETCH_NEXT_BYTES = 16L * 1024 * 1024

        /**
         * Picks the largest packed file, orders its volumes, and checks the set is complete and streamable.
         */
        fun build(volumes: List<RarVolume<out RandomAccessSource>>): Pair<String, RarStoreStream> {
            if (volumes.isEmpty()) throw NotStreamableException("no RAR volumes")
            val byFile = volumes.groupBy { it.fileName }
            val target = byFile.maxBy { (_, vs) -> vs.maxOf { it.unpackedSize } }
            val vols = order(target.value)
            val first = vols.first()

            if (vols.any { it.encrypted }) throw NotStreamableException("encrypted RAR")
            if (vols.any { !it.stored }) throw NotStreamableException("compressed RAR (not -m0); download needed")
            if (first.splitBefore) throw NotStreamableException("first RAR volume is missing")
            val packed = vols.sumOf { it.dataSize }
            if (packed != first.unpackedSize) {
                throw NotStreamableException(
                    "release is missing RAR volumes (have $packed of ${first.unpackedSize} bytes); try another result",
                )
            }
            return target.key to RarStoreStream(vols)
        }

        private fun order(vols: List<RarVolume<out RandomAccessSource>>): List<RarVolume<out RandomAccessSource>> {
            if (vols.all { it.volumeNumber != null }) {
                val sorted = vols.sortedBy { it.volumeNumber }
                if (sorted.zipWithNext().all { (a, b) -> a.volumeNumber != b.volumeNumber }) return sorted
            }
            // Old naming (.rar, .r00, .r01...) sorts wrong by name alone, so the flagged first volume leads.
            return vols.sortedWith(compareBy({ !it.isFirstVolume }, { volumeIndexFromName(it.sourceName) ?: Int.MAX_VALUE }, { it.sourceName }))
        }
    }
}
