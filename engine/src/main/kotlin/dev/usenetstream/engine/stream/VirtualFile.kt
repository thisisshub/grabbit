package dev.usenetstream.engine.stream

import dev.usenetstream.engine.nntp.ArticleNotFoundException
import dev.usenetstream.engine.nntp.NntpPool
import dev.usenetstream.engine.nzb.NzbFile
import dev.usenetstream.engine.nzb.NzbSegment
import dev.usenetstream.engine.yenc.YencDecoder
import dev.usenetstream.engine.yenc.YencException
import dev.usenetstream.engine.yenc.YencPart
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Byte-addressable read-only file. [read] blocks until data is there; returns -1 at end. */
interface RandomAccessSource {
    val length: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

fun RandomAccessSource.readFully(position: Long, count: Int): ByteArray {
    if (position < 0 || position + count > length) throw EOFException("read $count at $position past end $length")
    val out = ByteArray(count)
    var done = 0
    while (done < count) {
        val n = read(position + done, out, done, count - done)
        if (n <= 0) throw EOFException("short read at ${position + done}")
        done += n
    }
    return out
}

class StreamStats {
    val bytesDownloaded = AtomicLong()
    val missingArticles = AtomicInteger()
    val crcErrors = AtomicInteger()
}

fun interface SegmentFetcher {
    /** Throws [ArticleNotFoundException] when the server no longer has the article. */
    fun fetch(segment: NzbSegment): YencPart
}

class NntpSegmentFetcher(private val pool: NntpPool, private val stats: StreamStats) : SegmentFetcher {
    override fun fetch(segment: NzbSegment): YencPart {
        val part = pool.use { it.bodyYenc(segment.messageId) }
        stats.bytesDownloaded.addAndGet(part.data.size.toLong())
        if (part.crcOk == false) stats.crcErrors.incrementAndGet()
        return part
    }
}

/**
 * One posted file (e.g. `movie.mkv` or `movie.part03.rar`) read straight from its articles.
 *
 * Posters split files into equal-size parts, so segment i starts at i * partSize; the first article tells
 * us partSize and the real file size. Every later article's `=ypart begin` is checked against that.
 */
class VirtualFile private constructor(
    val nzbFile: NzbFile,
    val name: String,
    override val length: Long,
    private val partSize: Long,
    private val cache: SegmentCache,
) : RandomAccessSource {

    val segmentCount: Int get() = nzbFile.segments.size

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        if (length == 0) return 0
        val index = (position / partSize).toInt()
        val data = cache.get(index)
        val inPart = (position - index * partSize).toInt()
        val n = minOf(length, data.size - inPart)
        System.arraycopy(data, inPart, buffer, offset, n)
        return n
    }

    /** Free cached segments (e.g. the first article read while probing); they are fetched again if needed. */
    fun dropCache() = cache.trim()

    /** Turn on read-ahead for the file we are going to play (header probes leave it off). */
    fun enableReadAhead() {
        cache.readAheadEnabled = true
    }

    /** Start fetching around [position] without waiting, e.g. the next RAR volume before we reach it. */
    fun warm(position: Long) = cache.warm((position.coerceIn(0, length - 1) / partSize).toInt())

    companion object {
        fun open(
            file: NzbFile,
            fetcher: SegmentFetcher,
            executor: java.util.concurrent.Executor,
            stats: StreamStats,
            readAhead: Int = 8,
            urgent: java.util.concurrent.Executor = executor,
        ): VirtualFile {
            val first = fetcher.fetch(file.segments.first())
            if (first.begin != 0L) throw YencException("${file.name}: first segment starts at ${first.begin}")
            val length = first.totalSize
            val partSize = first.data.size.toLong()
            if (partSize == 0L) throw YencException("${file.name}: empty first segment")
            val expectedSegments = ((length + partSize - 1) / partSize).toInt()
            if (expectedSegments != file.segments.size) {
                throw YencException(
                    "${file.name}: ${file.segments.size} segments in NZB but size $length / part $partSize " +
                        "needs $expectedSegments (missing segments in NZB?)",
                )
            }

            fun expectedSize(index: Int) = minOf(partSize, length - index * partSize).toInt()

            val loader = { index: Int ->
                try {
                    val part = fetcher.fetch(file.segments[index])
                    val start = index * partSize
                    if (part.begin != start) {
                        throw YencException("${file.name} segment ${index + 1}: starts at ${part.begin}, expected $start")
                    }
                    val want = expectedSize(index)
                    if (part.data.size == want) {
                        part.data
                    } else {
                        // Damaged on the server (a byte or two lost). Keep later offsets right and play through it.
                        stats.crcErrors.incrementAndGet()
                        part.data.copyOf(want)
                    }
                } catch (e: ArticleNotFoundException) {
                    // No par2 while streaming: play through the hole as zeros.
                    stats.missingArticles.incrementAndGet()
                    ByteArray(expectedSize(index))
                }
            }
            val cache = SegmentCache(file.segments.size, loader, executor, urgent, readAhead)
            cache.seed(0, first.data)
            return VirtualFile(file, first.name.ifEmpty { file.name }, length, partSize, cache)
        }
    }
}

internal class IoFailure(cause: Throwable) : IOException(cause.message, cause)
