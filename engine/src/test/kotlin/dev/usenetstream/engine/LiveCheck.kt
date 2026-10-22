package dev.usenetstream.engine

import dev.usenetstream.engine.newznab.IndexerConfig
import dev.usenetstream.engine.newznab.NewznabClient
import dev.usenetstream.engine.nntp.NntpConfig
import dev.usenetstream.engine.nzb.NzbParser
import dev.usenetstream.engine.stream.readFully
import java.io.File
import kotlin.random.Random

/**
 * Manual check against real servers (not a unit test): `./gradlew :engine:liveCheck --args="<search terms>"`.
 * Credentials come from env: NNTP_HOST, NNTP_USER, NNTP_PASS, INDEXER_URL, INDEXER_KEY. NZB_FILE skips the search.
 */
fun main(args: Array<String>) {
    fun env(name: String) = System.getenv(name) ?: error("set $name")

    val nzb = System.getenv("NZB_FILE")?.let { path -> File(path).inputStream().use(NzbParser::parse) } ?: run {
        val indexer = NewznabClient(IndexerConfig(env("INDEXER_URL"), env("INDEXER_KEY")))
        val query = args.joinToString(" ").ifBlank { "big buck bunny" }
        val results = indexer.search(query)
        println("search \"$query\": ${results.size} results")
        results.take(8).forEachIndexed { i, r -> println("  [$i] ${r.title}  ${r.sizeBytes?.div(1_000_000)} MB  ${r.category}") }
        val pick = results.firstOrNull { (it.sizeBytes ?: 0) > 50_000_000 } ?: error("no result over 50 MB")
        println("using: ${pick.title}")
        indexer.fetchNzb(pick)
    }
    println("NZB: ${nzb.files.size} files, ${nzb.files.sumOf { it.segments.size }} segments")

    val config = NntpConfig(
        host = System.getenv("NNTP_HOST") ?: "news.newsdemon.com",
        username = env("NNTP_USER"),
        password = env("NNTP_PASS"),
        connections = System.getenv("NNTP_CONN")?.toInt() ?: 10,
    )
    UsenetStreamer.connect(config).use { streamer ->
        var t = System.nanoTime()
        fun lap(): String = "%.2fs".format((System.nanoTime() - t) / 1e9).also { t = System.nanoTime() }

        val media = streamer.open(nzb)
        val src = media.source
        println("opened ${media.name}: ${src.length / 1_000_000} MB in ${lap()}")

        val head = src.readFully(0, 16)
        println("magic ${head.joinToString(" ") { "%02x".format(it) }} video=${UsenetStreamer.looksLikeVideo(head + ByteArray(192))}")
        src.readFully(0, 8_000_000)
        println("first 8 MB in ${lap()}")

        for (frac in listOf(0.1, 0.5, 0.9)) {
            val pos = (src.length * frac).toLong()
            src.readFully(pos, 2_000_000)
            println("seek ${(frac * 100).toInt()}% + 2 MB in ${lap()}")
        }
        val rnd = Random(1)
        repeat(5) { src.readFully(rnd.nextLong(src.length - 100_000), 100_000) }
        println("5 random 100 KB reads in ${lap()}")
        val s = streamer.stats
        println("downloaded ${s.bytesDownloaded.get() / 1_000_000} MB, missing ${s.missingArticles.get()}, crc errors ${s.crcErrors.get()}")
    }
}
