package dev.usenetstream.engine

import dev.usenetstream.engine.newznab.AudioLayout
import dev.usenetstream.engine.newznab.Codec
import dev.usenetstream.engine.newznab.DeviceCaps
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.PlayPrefs
import dev.usenetstream.engine.newznab.Playability
import dev.usenetstream.engine.newznab.Quality
import dev.usenetstream.engine.newznab.Ranker
import dev.usenetstream.engine.newznab.ReleaseFilter
import dev.usenetstream.engine.newznab.ReleaseParser
import dev.usenetstream.engine.newznab.TitleFilter
import dev.usenetstream.engine.newznab.TitleSort
import dev.usenetstream.engine.newznab.Titles
import dev.usenetstream.engine.newznab.key
import dev.usenetstream.engine.newznab.playabilityOn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rel(
    title: String,
    size: Long? = 1_000_000_000,
    grabs: Int? = 10,
    pw: Boolean = false,
    date: String? = null,
    genre: String? = null,
    year: Int? = null,
    score: Double? = null,
    imdb: String? = null,
) = NzbResult(title, "u:$title", size, date, null, grabs, guid = "g:$title", passworded = pw, genre = genre, year = year, score = score, imdbId = imdb)

class ReleaseParserTest {
    @Test
    fun `reads quality, codec, HDR, audio and languages from scene names`() {
        val a = ReleaseParser.parse("Blade.Runner.2049.2017.2160p.UHD.BluRay.x265.HDR.DV.TrueHD.7.1.Atmos-GRP")
        assertEquals(Quality.UHD, a.quality)
        assertEquals(Codec.HEVC, a.codec)
        assertTrue(a.hdr && a.dolbyVision && a.trueHd)
        assertEquals(AudioLayout.ATMOS, a.audio)

        val b = ReleaseParser.parse("Movie.2010.1080p.WEB-DL.DDP5.1.H.264-X")
        assertEquals(Quality.FHD, b.quality)
        assertEquals(Codec.H264, b.codec)
        assertEquals(AudioLayout.SURROUND, b.audio)
        assertFalse(b.hdr)

        val c = ReleaseParser.parse("Film.2001.GERMAN.720p.BluRay.DTS.x264")
        assertEquals(Quality.HD, c.quality)
        assertEquals(setOf("German"), c.languages)
        assertTrue(c.dts)

        assertEquals(setOf("Multi"), ReleaseParser.parse("Show.S01E01.MULTi.1080p.WEB.H265-GL0P").languages)
        assertEquals(Quality.UNKNOWN, ReleaseParser.parse("big buck bunny").quality)
    }

    @Test
    fun `reads episodes, alternate numbering and season packs`() {
        ReleaseParser.parse("The.Office.2001.S02E06.Interview.1080p").let {
            assertEquals(2, it.season); assertEquals(6, it.episode); assertFalse(it.seasonPack)
        }
        ReleaseParser.parse("show_name.3x07.hdtv").let { assertEquals(3, it.season); assertEquals(7, it.episode) }
        ReleaseParser.parse("Show.S04.COMPLETE.1080p.WEB").let { assertEquals(4, it.season); assertTrue(it.seasonPack) }
        ReleaseParser.parse("Show.Season.2.720p").let { assertEquals(2, it.season); assertTrue(it.seasonPack) }
        // Indexer attributes win over the name.
        ReleaseParser.parse("Obfuscated.abc", attrSeason = 1, attrEpisode = 9).let { assertEquals(1, it.season); assertEquals(9, it.episode) }
    }

    @Test
    fun `playability follows device decoders`() {
        val phone = DeviceCaps(hevc = true, av1 = false, dts = false)
        assertEquals(Playability.OK, ReleaseParser.parse("X.1080p.x265").playabilityOn(phone))
        assertEquals(Playability.NO, ReleaseParser.parse("X.1080p.AV1").playabilityOn(phone))
        assertEquals(Playability.NO_AUDIO, ReleaseParser.parse("X.1080p.x264.DTS-HD.MA.5.1").playabilityOn(phone))
        assertEquals(Playability.NO, ReleaseParser.parse("X.1080p.x265").playabilityOn(phone.copy(hevc = false)))
        // A 1080p TV: 4K is out, 1080p is fine.
        val tv1080 = phone.copy(maxScreenHeight = 1080)
        assertEquals(Playability.NO, ReleaseParser.parse("X.2160p.WEB-DL.DD5.1.HEVC").playabilityOn(tv1080))
        assertEquals(Playability.OK, ReleaseParser.parse("X.1080p.WEB-DL.DD5.1.HEVC").playabilityOn(tv1080))
    }
}

class FilterAndRankTest {
    private val caps = DeviceCaps(hevc = true, av1 = false, dts = false)
    private val now = Instant.parse("2026-10-22T00:00:00Z")

    @Test
    fun `release filter by quality, size, age, language, lock and playability`() {
        val rs = listOf(
            rel("A.720p.x264", size = 1_000_000_000, date = "Mon, 19 Oct 2026 10:00:00 +0000"),
            rel("B.1080p.x264", size = 4_000_000_000, date = "Mon, 19 Oct 2026 10:00:00 +0000"),
            rel("C.1080p.x264", size = 2_000_000_000, date = "Wed, 25 Jan 2017 20:40:26 +0000"),
            rel("D.1080p.GERMAN.x264", size = 2_000_000_000),
            rel("E.1080p.x264", pw = true),
            rel("F.1080p.AV1"),
        )
        fun pass(f: ReleaseFilter) = rs.filter { f.matches(it, ReleaseParser.parse(it), caps, now) }.map { it.title.first() }

        assertEquals(listOf('A', 'B', 'C', 'D', 'F'), pass(ReleaseFilter())) // locked hidden by default
        assertEquals(listOf('B', 'C', 'D', 'F'), pass(ReleaseFilter(qualities = setOf(Quality.FHD))))
        assertEquals(listOf('A', 'C', 'D', 'F'), pass(ReleaseFilter(maxBytes = 3_000_000_000)))
        assertEquals(listOf('A', 'B', 'D', 'F'), pass(ReleaseFilter(maxAgeDays = 30))) // unknown age kept
        assertEquals(listOf('D'), pass(ReleaseFilter(language = "German")))
        assertEquals(listOf('A', 'B', 'C', 'F'), pass(ReleaseFilter(language = "English")))
        assertEquals(listOf('A', 'B', 'C', 'D'), pass(ReleaseFilter(hideUnplayable = true)))
        assertFalse(ReleaseFilter().active)
        assertTrue(ReleaseFilter(maxAgeDays = 30).active)
    }

    @Test
    fun `ranker puts failed and unplayable last, then nearest quality, grabs, size`() {
        val rs = listOf(
            rel("Popular.2160p.x265", grabs = 900),
            rel("Broken.1080p.x264", grabs = 5000),
            rel("NoDecoder.1080p.AV1", grabs = 4000),
            rel("Good.1080p.x264", grabs = 300, size = 3_000_000_000),
            rel("Good2.1080p.x265", grabs = 300, size = 2_000_000_000),
            rel("Small.720p.x264", grabs = 2000),
            rel("Locked.1080p", pw = true, grabs = 9999),
        )
        val failed = setOf(rel("Broken.1080p.x264").key)

        val ranked = Ranker.rank(rs, caps, PlayPrefs(quality = Quality.FHD), failed).map { it.title.substringBefore('.') }

        assertEquals(listOf("Good2", "Good", "Small", "Popular", "NoDecoder", "Broken"), ranked)
        val capped = Ranker.rank(rs, caps, PlayPrefs(quality = Quality.FHD, maxBytes = 2_500_000_000), failed)
        assertEquals("Good2", capped.first().title.substringBefore('.'))
    }

    @Test
    fun `title filter by genre, year, rating and sorts`() {
        val groups = Titles.group(
            listOf(
                rel("Old.Cartoon.1990.1080p", imdb = "1", genre = "Animation, Comedy", year = 1990, score = 7.0, grabs = 50),
                rel("New.Thriller.2024.1080p", imdb = "2", genre = "Thriller", year = 2024, score = 8.1, grabs = 10),
                rel("Mid.Cartoon.2010.1080p", imdb = "3", genre = "Animation", year = 2010, score = 5.5, grabs = 500),
            ),
        )
        fun names(f: TitleFilter) = f.apply(groups).map { it.year }

        assertEquals(listOf(1990, 2010), names(TitleFilter(genres = setOf("Animation"))))
        assertEquals(listOf(2024, 2010), names(TitleFilter(minYear = 2000)))
        assertEquals(listOf(1990, 2024), names(TitleFilter(minRating = 6.0)))
        assertEquals(listOf(2010, 1990, 2024), names(TitleFilter(sort = TitleSort.POPULAR)))
        assertEquals(listOf(2024, 1990, 2010), names(TitleFilter(sort = TitleSort.RATING)))
    }

    @Test
    fun `a 1080p TV plays a 1080p copy before any 4K one`() {
        val names = listOf(
            "The.Paradise.2026.2160p.NF.WEB-DL.DUAL.DDP5.1.H.265-QAsH",
            "The.Paradise.2026.2160p.NF.WEB-DL.DDP5.1.H.265-RUDR",
            "The.Paradise.2026.2160p.NF.WEB-DL.MULTi.DDP5.1.DV.HDR.H.265-RUDR",
            "The.Paradise.2026.1080p.NF.WEB-DL.DD+5.1.x264-snAck",
            "The.Paradise.2026.1080p.NF.WEB-DL.Multi.DD+5.1.x264-CPTN5DW",
        )
        // As NZBGeek sends them: only two 4K copies unflagged, the 1080p ones "may hold an inner archive".
        val releases = names.mapIndexed { i, n -> NzbResult(n, "u$i", 5_000_000_000L - i, null, null, grabs = 100 - i * 10, innerArchive = i >= 2) }
        val tv = DeviceCaps(hevc = true, maxScreenHeight = 1080)
        val ranked = Ranker.rank(releases, tv, PlayPrefs(quality = Quality.UHD), emptySet())
        assertTrue(ranked.first().title.contains("1080p"), ranked.first().title)
        assertEquals(5, ranked.size) // flagged releases are ranked, not dropped
        // On a 4K device the unflagged 4K copy comes first.
        assertEquals("The.Paradise.2026.2160p.NF.WEB-DL.DUAL.DDP5.1.H.265-QAsH", Ranker.rank(releases, tv.copy(maxScreenHeight = 2160), PlayPrefs(quality = Quality.UHD), emptySet()).first().title)
    }
}
