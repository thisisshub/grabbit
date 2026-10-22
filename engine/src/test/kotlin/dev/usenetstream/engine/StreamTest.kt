package dev.usenetstream.engine

import dev.usenetstream.engine.nzb.Nzb
import dev.usenetstream.engine.rar.NotStreamableException
import dev.usenetstream.engine.rar.RarParser
import dev.usenetstream.engine.rar.RarStoreStream
import dev.usenetstream.engine.stream.StreamStats
import dev.usenetstream.engine.stream.VirtualFile
import dev.usenetstream.engine.stream.readFully
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BIGFILE_CRC = 0x509ad74cL // vols/bigfile.txt in the rarfile fixtures
private const val BIGFILE_SIZE = 205_000L

class VirtualFileTest {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `random reads across segment edges match the original bytes`() {
        val content = Random(7).nextBytes(100_003) // last part shorter
        val post = FakePost("movie.mkv", content, partSize = 4_000)
        val vf = VirtualFile.open(post.file, FakeFetcher(listOf(post)), executor, StreamStats(), readAhead = 3).apply { enableReadAhead() }

        assertEquals(content.size.toLong(), vf.length)
        assertContentEquals(content, vf.readAll())
        val rnd = Random(8)
        repeat(300) {
            val pos = rnd.nextLong(content.size.toLong())
            val n = rnd.nextInt(1, 12_000).coerceAtMost((content.size - pos).toInt())
            assertContentEquals(content.copyOfRange(pos.toInt(), pos.toInt() + n), vf.readFully(pos, n))
        }
        assertEquals(-1, vf.read(vf.length, ByteArray(1), 0, 1))
    }

    @Test
    fun `missing article plays as zeros and is counted`() {
        val content = Random(9).nextBytes(10_000)
        val post = FakePost("movie.mkv", content, partSize = 2_500)
        val stats = StreamStats()
        val vf = VirtualFile.open(post.file, FakeFetcher(listOf(post), missing = setOf("movie.mkv.2@test")), executor, stats)

        val all = vf.readAll()

        assertContentEquals(content.copyOfRange(0, 2_500), all.copyOfRange(0, 2_500))
        assertTrue(all.copyOfRange(2_500, 5_000).all { it == 0.toByte() })
        assertContentEquals(content.copyOfRange(5_000, 10_000), all.copyOfRange(5_000, 10_000))
        assertEquals(1, stats.missingArticles.get())
    }
}

class DamagedSegmentTest {
    @Test
    fun `a segment one byte short is padded so later offsets stay right`() {
        val content = Random(12).nextBytes(10_000)
        val post = FakePost("movie.mkv", content, partSize = 2_500)
        val short = object : dev.usenetstream.engine.stream.SegmentFetcher {
            val inner = FakeFetcher(listOf(post))
            override fun fetch(segment: dev.usenetstream.engine.nzb.NzbSegment) = inner.fetch(segment).let { p ->
                if (segment.number != 2) p else dev.usenetstream.engine.yenc.YencPart(p.name, p.totalSize, p.begin, p.data.copyOf(p.data.size - 1), false)
            }
        }
        val stats = StreamStats()
        val ex = Executors.newFixedThreadPool(2)
        try {
            val all = VirtualFile.open(post.file, short, ex, stats).readAll()
            assertEquals(content.size, all.size)
            assertContentEquals(content.copyOfRange(0, 4_999), all.copyOfRange(0, 4_999))
            assertContentEquals(content.copyOfRange(5_000, 10_000), all.copyOfRange(5_000, 10_000))
            assertTrue(stats.crcErrors.get() >= 1)
        } finally {
            ex.shutdownNow()
        }
    }
}

class RarTest {
    private fun fixture(v: Int) = (1..3).map { p ->
        val name = "rar$v-vols.part$p.rar"
        name to resource("/rar/$name")
    }

    @Test
    fun `stitches real RAR4 and RAR5 store volumes back into the packed file`() {
        for (v in listOf(3, 5)) {
            // Shuffled + obfuscated names: order must come from the headers.
            val volumes = fixture(v).shuffled(Random(v)).mapIndexed { i, (_, bytes) ->
                requireNotNull(RarParser.parse(ByteArraySource(bytes), "obf$i"))
            }

            val (name, stream) = RarStoreStream.build(volumes)

            assertEquals("vols/bigfile.txt", name, "rar$v")
            assertEquals(BIGFILE_SIZE, stream.length, "rar$v")
            assertEquals(BIGFILE_CRC, CRC32().apply { update(stream.readAll()) }.value, "rar$v")
            // Random offset reads agree with a full read.
            val all = stream.readAll()
            val rnd = Random(v * 31)
            repeat(100) {
                val pos = rnd.nextLong(BIGFILE_SIZE)
                val n = rnd.nextInt(1, 30_000).coerceAtMost((BIGFILE_SIZE - pos).toInt())
                assertContentEquals(all.copyOfRange(pos.toInt(), pos.toInt() + n), stream.readFully(pos, n), "rar$v")
            }
        }
    }

    @Test
    fun `missing volume, compressed and non-RAR inputs fail cleanly`() {
        val vols = fixture(5).map { (n, b) -> requireNotNull(RarParser.parse(ByteArraySource(b), n)) }
        assertFailsWith<NotStreamableException> { RarStoreStream.build(vols.filter { it.sourceName != "rar5-vols.part2.rar" }) }
        assertFailsWith<NotStreamableException> { RarStoreStream.build(listOf(vols[0].copy(stored = false))) }
        assertEquals(null, RarParser.parse(ByteArraySource(Random(1).nextBytes(500)), "noise"))
    }
}

class ProbeTest {
    @Test
    fun `opening a RAR set reads only first articles and orders volumes by name`() {
        val posts = (1..3).map { p ->
            FakePost("rel.part0$p.rar", resource("/rar/rar3-vols.part$p.rar"), partSize = 4_000)
        }
        val fetcher = FakeFetcher(posts)
        val steps = ArrayList<Pair<Int, Int>>()
        UsenetStreamer(fetcher, Executors.newFixedThreadPool(4), StreamStats()).use { s ->
            val media = s.open(Nzb(posts.map { it.file })) { d, t -> synchronized(steps) { steps += d to t } }
            // Probing: no read-ahead, no RAR4 end-of-volume reads; just the header article(s) per volume.
            val probed = fetcher.fetched.values.sumOf { it.get() }
            assertTrue(probed <= 6, "fetched $probed articles while probing")
            assertEquals(BIGFILE_CRC, CRC32().apply { update(media.source.readAll()) }.value)
            assertEquals(0 to 3, steps.first())
            assertEquals(3 to 3, steps.maxBy { it.first })
        }
    }
}

class UsenetStreamerTest {
    @Test
    fun `NZB of RAR volumes plus par2 streams the inner file end to end`() {
        val posts = (1..3).map { p ->
            FakePost("a8f3e1c$p", resource("/rar/rar5-vols.part$p.rar"), partSize = 16_000) // obfuscated names
        } + FakePost("release.vol00+01.par2", Random(3).nextBytes(5_000), partSize = 16_000)
        val fetcher = FakeFetcher(posts)
        val executor = Executors.newFixedThreadPool(4)

        UsenetStreamer(fetcher, executor, StreamStats()).use { streamer ->
            val media = streamer.open(Nzb(posts.map { it.file }))

            assertEquals("bigfile.txt", media.name)
            assertEquals(BIGFILE_CRC, CRC32().apply { update(media.source.readAll()) }.value)
            assertTrue(fetcher.fetched.keys.none { it.startsWith("release.vol00") }, "par2 must not be fetched")
        }
    }

    @Test
    fun `plain MKV post is picked over smaller files`() {
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + Random(4).nextBytes(50_000)
        val posts = listOf(FakePost("sample.mkv", mkv.copyOf(9_000), 4_000), FakePost("main.mkv", mkv, 4_000))
        UsenetStreamer(FakeFetcher(posts), Executors.newFixedThreadPool(2), StreamStats()).use { streamer ->
            val media = streamer.open(Nzb(posts.map { it.file }))
            assertEquals("main.mkv", media.name)
            assertContentEquals(mkv, media.source.readAll())
        }
    }
}

class SeekPriorityTest {
    @Test
    fun `a seek is served on the urgent lane while read-ahead is busy`() {
        val content = Random(11).nextBytes(64 * 1_000)
        val post = FakePost("movie.mkv", content, partSize = 1_000)
        val slow = object : dev.usenetstream.engine.stream.SegmentFetcher {
            val inner = FakeFetcher(listOf(post))
            override fun fetch(segment: dev.usenetstream.engine.nzb.NzbSegment) = inner.fetch(segment).also { Thread.sleep(150) }
        }
        val readAhead = Executors.newFixedThreadPool(1)
        val urgent = Executors.newFixedThreadPool(1)
        try {
            val vf = VirtualFile.open(post.file, slow, readAhead, StreamStats(), readAhead = 8, urgent = urgent).apply { enableReadAhead() }
            vf.readFully(0, 10) // queues 8 slow read-ahead segments on the single read-ahead thread

            val t0 = System.nanoTime()
            val got = vf.readFully(40_000, 1_000)
            val ms = (System.nanoTime() - t0) / 1_000_000

            assertContentEquals(content.copyOfRange(40_000, 41_000), got)
            // Old behaviour waited behind the queue (~8 x 150 ms); now ~1 fetch.
            assertTrue(ms < 600, "seek took $ms ms")
        } finally {
            readAhead.shutdownNow()
            urgent.shutdownNow()
        }
    }
}

class EpisodePickTest {
    @Test
    fun `season pack plays the requested episode's file`() {
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        val posts = (1..3).map { e -> FakePost("Show.S01E0$e.1080p.mkv", mkv + Random(e).nextBytes(20_000 + e * 1000), 8_000) }
        UsenetStreamer(FakeFetcher(posts), Executors.newFixedThreadPool(2), StreamStats()).use { s ->
            assertEquals("Show.S01E02.1080p.mkv", s.open(Nzb(posts.map { it.file }), episode = 1 to 2).name)
            // No match in names (obfuscated): falls back to the largest file.
            assertEquals("Show.S01E03.1080p.mkv", s.open(Nzb(posts.map { it.file }), episode = 1 to 9).name)
        }
    }
}

class ProbeMemoryTest {
    @Test
    fun `after opening a RAR set only the playing volume keeps probe data`() {
        val posts = (1..3).map { p -> FakePost("rel.part0$p.rar", resource("/rar/rar5-vols.part$p.rar"), partSize = 4_000) }
        val fetcher = FakeFetcher(posts)
        UsenetStreamer(fetcher, Executors.newFixedThreadPool(2), StreamStats()).use { s ->
            val media = s.open(Nzb(posts.map { it.file }))
            val before = fetcher.fetched.values.sumOf { it.get() }
            // Volumes 2 and 3 dropped their probe article, so reading the whole file fetches them again (still correct).
            assertEquals(BIGFILE_CRC, CRC32().apply { update(media.source.readAll()) }.value)
            assertTrue(fetcher.fetched.getValue("rel.part02.rar.1@test").get() >= 2, "part02 probe article should have been dropped and refetched")
            assertTrue(before >= 3)
        }
    }
}

class VolumeCacheTest {
    @Test
    fun `reading through a RAR set frees volumes left behind`() {
        val vols = (1..3).map { p -> FakePost("v.part0$p.rar", resource("/rar/rar5-vols.part$p.rar"), partSize = 4_000) }
        val ex = Executors.newFixedThreadPool(2)
        try {
            val files = vols.map { VirtualFile.open(it.file, FakeFetcher(vols), ex, StreamStats()).apply { enableReadAhead() } }
            val stream = RarStoreStream.build(files.mapIndexed { i, f -> requireNotNull(RarParser.parse(f, "v.part0${i + 1}.rar")) }).second
            assertEquals(BIGFILE_CRC, CRC32().apply { update(stream.readAll()) }.value)
            // Seek back to the start after reading to the end: still byte-exact (dropped volumes refetch).
            assertContentEquals(stream.readFully(0, 5_000), stream.readFully(0, 5_000))
            assertEquals(BIGFILE_CRC, CRC32().apply { update(stream.readAll()) }.value)
        } finally {
            ex.shutdownNow()
        }
    }
}
