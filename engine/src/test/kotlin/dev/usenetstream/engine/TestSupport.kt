package dev.usenetstream.engine

import dev.usenetstream.engine.nntp.ArticleNotFoundException
import dev.usenetstream.engine.nzb.NzbFile
import dev.usenetstream.engine.nzb.NzbSegment
import dev.usenetstream.engine.stream.RandomAccessSource
import dev.usenetstream.engine.stream.SegmentFetcher
import dev.usenetstream.engine.yenc.YencDecoder
import dev.usenetstream.engine.yenc.YencPart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** yEnc encoder used only to build test articles (posters' side). */
object YencTestEncoder {
    fun encodePart(name: String, total: Long, part: Int, begin: Long, data: ByteArray, lineLength: Int = 128): List<ByteArray> {
        val lines = ArrayList<ByteArray>()
        lines += "=ybegin part=$part line=$lineLength size=$total name=$name".toByteArray()
        lines += "=ypart begin=${begin + 1} end=${begin + data.size}".toByteArray()
        var line = java.io.ByteArrayOutputStream()
        for (b in data) {
            val o = ((b.toInt() and 0xFF) + 42) and 0xFF
            val atStart = line.size() == 0
            val critical = o == 0x00 || o == 0x0A || o == 0x0D || o == 0x3D ||
                (atStart && (o == 0x2E || o == 0x09 || o == 0x20))
            if (critical) {
                line.write('='.code)
                line.write((o + 64) and 0xFF)
            } else {
                line.write(o)
            }
            if (line.size() >= lineLength) {
                lines += line.toByteArray()
                line = java.io.ByteArrayOutputStream()
            }
        }
        if (line.size() > 0) lines += line.toByteArray()
        val crc = CRC32().apply { update(data) }.value
        lines += "=yend size=${data.size} part=$part pcrc32=%08x".format(crc).toByteArray()
        return lines
    }
}

/** Posts [content] as a fake NZB file split into [partSize] articles, served from memory. */
class FakePost(val name: String, val content: ByteArray, partSize: Int) {
    private val articles = HashMap<String, List<ByteArray>>()
    val file: NzbFile

    init {
        val segments = ArrayList<NzbSegment>()
        var offset = 0
        var number = 1
        while (offset < content.size) {
            val end = minOf(content.size, offset + partSize)
            val id = "$name.$number@test"
            val lines = YencTestEncoder.encodePart(name, content.size.toLong(), number, offset.toLong(), content.copyOfRange(offset, end))
            articles[id] = lines
            segments += NzbSegment(number, lines.sumOf { it.size + 2L }, id)
            offset = end
            number++
        }
        file = NzbFile("test [1/1] \"$name\" yEnc (1/${segments.size})", listOf("alt.binaries.test"), segments)
    }

    fun articles(): Map<String, List<ByteArray>> = articles
}

class FakeFetcher(posts: List<FakePost>, private val missing: Set<String> = emptySet()) : SegmentFetcher {
    private val articles = posts.flatMap { it.articles().entries }.associate { it.key to it.value }
    val fetched = ConcurrentHashMap<String, AtomicInteger>()

    override fun fetch(segment: NzbSegment): YencPart {
        if (segment.messageId in missing) throw ArticleNotFoundException(segment.messageId)
        fetched.getOrPut(segment.messageId) { AtomicInteger() }.incrementAndGet()
        return YencDecoder.decode(articles.getValue(segment.messageId))
    }
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val length: Long get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
}

fun RandomAccessSource.readAll(chunk: Int = 7919): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(chunk)
    var pos = 0L
    while (true) {
        val n = read(pos, buf, 0, buf.size)
        if (n < 0) break
        out.write(buf, 0, n)
        pos += n
    }
    return out.toByteArray()
}

fun resource(path: String): ByteArray =
    requireNotNull(object {}.javaClass.getResourceAsStream(path)) { "missing test resource $path" }.readBytes()
