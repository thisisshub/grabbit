package dev.usenetstream.engine

import dev.usenetstream.engine.nntp.NntpAuthException
import dev.usenetstream.engine.nntp.NntpClient
import dev.usenetstream.engine.nntp.NntpConfig
import dev.usenetstream.engine.nntp.NntpPool
import dev.usenetstream.engine.nntp.socketConnector
import dev.usenetstream.engine.nzb.Nzb
import dev.usenetstream.engine.nzb.NzbFile
import dev.usenetstream.engine.rar.NotStreamableException
import dev.usenetstream.engine.rar.RarParser
import dev.usenetstream.engine.rar.RarStoreStream
import dev.usenetstream.engine.rar.volumeIndexFromName
import dev.usenetstream.engine.sevenz.JoinedSource
import dev.usenetstream.engine.sevenz.SevenZ
import dev.usenetstream.engine.stream.NntpSegmentFetcher
import dev.usenetstream.engine.stream.RandomAccessSource
import dev.usenetstream.engine.stream.SegmentFetcher
import dev.usenetstream.engine.stream.StreamStats
import dev.usenetstream.engine.stream.VirtualFile
import dev.usenetstream.engine.stream.readFully
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class OpenedMedia(val name: String, val source: RandomAccessSource)

/** NZB in, one playable byte stream out. */
class UsenetStreamer(
    private val fetcher: SegmentFetcher,
    private val executor: ExecutorService,
    val stats: StreamStats,
    private val onClose: () -> Unit = {},
    /** Lane for the segment the player is blocked on; see SegmentCache. */
    private val urgent: ExecutorService = executor,
) : Closeable {

    /**
     * [episode] (season to episode) picks one episode out of a season pack when the file names say which is which.
     */
    fun open(nzb: Nzb, episode: Pair<Int, Int>? = null, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): OpenedMedia {
        // Repair/info files are never the payload; skip them so we don't pull their first articles.
        val media = nzb.files.filterNot { SKIP.containsMatchIn(it.name) }
        val candidates = episode?.let { (s, e) ->
            val code = Regex("""(?i)\bs0?${s}[ .]?e0?${e}\b|\b${s}x0?${e}\b""")
            media.filter { code.containsMatchIn(it.name) }.ifEmpty { null }
        } ?: media
        if (candidates.isEmpty()) throw NotStreamableException("NZB has no media files")

        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val done = java.util.concurrent.atomic.AtomicInteger()
        onProgress(0, candidates.size)
        val opened = candidates
            .map { file ->
                executor.submit<VirtualFile?> {
                    tryOpen(file, failures).also { onProgress(done.incrementAndGet(), candidates.size) }
                }
            }
            .mapNotNull { it.getUnwrapped() }
        if (opened.isEmpty()) throw NotStreamableException("no file in the NZB could be read: ${failures.joinToString("; ")}")

        val heads = opened.associateWith { it.readFully(0, minOf(192L, it.length).toInt()) }
        val video = opened.filter { looksLikeVideo(heads.getValue(it)) }.maxByOrNull { it.length }
        if (video != null) {
            // Probing kept the first article of every file; only the chosen one is still useful.
            opened.filter { it !== video }.forEach { it.dropCache() }
            video.enableReadAhead()
            return OpenedMedia(video.name, video)
        }

        val sevenZ = opened.filter { SevenZ.isSevenZ(heads.getValue(it)) || SEVEN_Z_PART.containsMatchIn(it.name) }
        if (sevenZ.isNotEmpty() && opened.none { RarParser.isRar(heads.getValue(it)) }) return openSevenZ(sevenZ, opened, nzb.password)

        // Names like x.part07.rar already give the order; then skip RAR4's per-volume end-block read.
        val rars = opened.filter { RarParser.isRar(heads.getValue(it)) }
        val namesOrder = rars.mapNotNull { volumeIndexFromName(it.name) }.let { it.size == rars.size && it.toSet().size == it.size }
        val volumes = rars.mapNotNull { RarParser.parse(it, it.name, lookupVolumeNumber = !namesOrder) }
        if (volumes.isEmpty()) {
            val kinds = heads.values.mapNotNull(::archiveKind).toSet()
            val why = if (kinds.isNotEmpty()) "${kinds.joinToString("/")} archive, can't stream that yet" else "no video or RAR found"
            throw NotStreamableException("$why (read ${opened.size} of ${candidates.size} files)")
        }
        val (name, stream) = try {
            RarStoreStream.build(volumes)
        } catch (e: NotStreamableException) {
            if (failures.isEmpty()) throw e
            throw NotStreamableException("${e.message}; unreadable: ${failures.joinToString("; ")}")
        }
        // 64 volumes x one probed article is ~50 MB; keep only what the stream reads next (refetched on demand).
        opened.forEach { if (it !== volumes.firstOrNull()?.source) it.dropCache() }
        volumes.forEach { (it.source as? VirtualFile)?.enableReadAhead() }
        return OpenedMedia(name.substringAfterLast('/'), stream)
    }

    /** A 7z set (x.7z.001, .002, ...): stored or AES-encrypted files stream straight out of it. */
    private fun openSevenZ(parts: List<VirtualFile>, opened: List<VirtualFile>, password: String?): OpenedMedia {
        val ordered = parts.sortedBy { SEVEN_Z_PART.find(it.name)?.groupValues?.get(1)?.toInt() ?: 0 }
        val numbers = ordered.mapNotNull { SEVEN_Z_PART.find(it.name)?.groupValues?.get(1)?.toInt() }
        if (numbers.isNotEmpty() && numbers != (1..numbers.size).toList()) {
            throw NotStreamableException("7z set is missing volumes (have ${numbers.size}, last is .%03d); try another result".format(numbers.last()))
        }
        val joined = JoinedSource(ordered)
        val archive = SevenZ.parse(joined, password)
        val entry = archive.entries.filter { VIDEO_NAME.containsMatchIn(it.name) }.maxByOrNull { it.size }
            ?: archive.entries.maxByOrNull { it.size }
            ?: throw NotStreamableException("7z archive is empty")
        val stream = SevenZ.open(joined, archive, entry, password)
        // Reading the index touched the first and last volumes; keep only what playback reads next.
        opened.forEach { if (it !== ordered.first()) it.dropCache() }
        ordered.forEach { it.enableReadAhead() }
        return OpenedMedia(entry.name.substringAfterLast('/'), stream)
    }

    private fun tryOpen(file: NzbFile, failures: MutableCollection<String>): VirtualFile? = try {
        VirtualFile.open(file, fetcher, executor, stats, urgent = urgent)
    } catch (e: NntpAuthException) {
        throw e
    } catch (e: IOException) {
        failures += "${file.name}: ${e.message}"
        null // e.g. first article expired; the release may still play from the other files
    }

    /** Safe to call from a UI thread: saying QUIT to the server happens on a background thread. */
    override fun close() {
        executor.shutdownNow()
        urgent.shutdownNow()
        Thread(onClose, "nntp-close").apply { isDaemon = true }.start()
    }

    companion object {
        private const val URGENT_LANE = 2
        private val SEVEN_Z_PART = Regex("""(?i)\.7z\.(\d{3})$""")
        private val VIDEO_NAME = Regex("""(?i)\.(mkv|mp4|m4v|avi|ts|mov|webm)$""")
        private val SKIP = Regex("""\.(par2|sfv|nfo|nzb|srr|jpg|png|txt)$""", RegexOption.IGNORE_CASE)

        fun connect(config: NntpConfig): UsenetStreamer {
            val pool = NntpPool(
                newClient = {
                    NntpClient(socketConnector(config.host, config.port, config.useTls), config.username, config.password)
                },
                size = config.connections,
            )
            val stats = StreamStats()
            // Pool has `connections` permits: read-ahead may use all but URGENT_LANE, which stay free for seeks.
            val urgentThreads = if (config.connections > URGENT_LANE) URGENT_LANE else 1
            val readAhead = Executors.newFixedThreadPool(maxOf(1, config.connections - urgentThreads)) { r ->
                Thread(r, "nntp-fetch").apply { isDaemon = true }
            }
            val urgent = Executors.newFixedThreadPool(urgentThreads) { r ->
                Thread(r, "nntp-urgent").apply { isDaemon = true }
            }
            return UsenetStreamer(NntpSegmentFetcher(pool, stats), readAhead, stats, onClose = pool::close, urgent = urgent)
        }

        fun archiveKind(head: ByteArray): String? {
            fun at(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
            return when {
                at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
                at(0x50, 0x4B, 0x03, 0x04) -> "zip"
                else -> null
            }
        }

        fun looksLikeVideo(head: ByteArray): Boolean {
            fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it] == b[it].toByte() }
            return at(0, 0x1A, 0x45, 0xDF, 0xA3) || // Matroska / WebM
                at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) || // MP4 / MOV
                (at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && at(8, 'A'.code, 'V'.code, 'I'.code)) ||
                (at(0, 0x47) && at(188, 0x47)) // MPEG-TS: sync byte every 188 bytes
        }
    }
}

private fun <T> Future<T>.getUnwrapped(): T = try {
    get()
} catch (e: ExecutionException) {
    throw e.cause ?: e
}
