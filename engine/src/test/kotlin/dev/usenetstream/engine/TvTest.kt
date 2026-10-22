package dev.usenetstream.engine

import dev.usenetstream.engine.newznab.HttpGet
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.Titles
import dev.usenetstream.engine.tv.EpisodeProgress
import dev.usenetstream.engine.tv.GuideEpisode
import dev.usenetstream.engine.tv.Json
import dev.usenetstream.engine.tv.NextUp
import dev.usenetstream.engine.tv.TvMazeClient
import dev.usenetstream.engine.tv.episodeKey
import dev.usenetstream.engine.tv.nextUp
import dev.usenetstream.engine.tv.splitSeason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvTest {
    private fun ep(s: Int, e: Int) = GuideEpisode(s, e, "Ep $s-$e", 30, null, null, null)
    private val guide = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1), ep(2, 2))
    private fun p(pos: Long, finished: Boolean, at: Long) = EpisodeProgress(pos, 1_800_000, finished, at)

    @Test
    fun `next up resumes, advances across seasons, starts at the top, ends at all watched`() {
        assertEquals(NextUp.Play(ep(1, 1)), nextUp(guide, emptyMap()))

        val half = nextUp(guide, mapOf("S01E02" to p(600_000, false, 5)))
        assertIs<NextUp.Resume>(half)
        assertEquals(ep(1, 2), half.episode)

        assertEquals(NextUp.Play(ep(2, 1)), nextUp(guide, mapOf("S01E01" to p(1, true, 1), "S01E03" to p(1, true, 9))))
        // The most recently touched episode decides, even if an earlier one is half-watched.
        assertEquals(NextUp.Play(ep(2, 2)), nextUp(guide, mapOf("S01E02" to p(9, false, 1), "S02E01" to p(1, true, 7))))
        // Skips episodes already finished after the last one.
        assertEquals(NextUp.Play(ep(2, 2)), nextUp(guide, mapOf("S02E01" to p(1, true, 2), "S01E03" to p(1, true, 8))))
        assertEquals(NextUp.AllWatched, nextUp(guide, mapOf("S02E02" to p(1, true, 3))))
        assertEquals("tvdb:78107:S02E06", episodeKey("tvdb:78107", 2, 6))
    }

    @Test
    fun `episodes group under one show card and split into episodes and packs`() {
        fun tv(t: String, s: Int?, e: Int?) = NzbResult(t, "u:$t", 1, null, "TV > HD", 1, season = s, episode = e, tvdbId = "78107", showTitle = "The Office")
        val rs = listOf(
            tv("The.Office.S02E01.720p", 2, 1), tv("The.Office.S02E01.1080p", 2, 1),
            tv("The.Office.S02E06.1080p", 2, 6), tv("The.Office.S02.COMPLETE.1080p", 2, null),
            tv("The.Office.S01E01.1080p", 1, 1),
        )
        val show = Titles.group(rs).single()
        assertEquals("tvdb:78107", show.key)
        assertEquals("The Office", show.title)
        assertTrue(show.isShow)

        val s2 = splitSeason(rs, 2)
        assertEquals(setOf(1, 6), s2.byEpisode.keys)
        assertEquals(2, s2.byEpisode.getValue(1).size)
        assertEquals(listOf("The.Office.S02.COMPLETE.1080p"), s2.packs.map { it.title })
    }

    @Test
    fun `tvmaze show search maps hits to tvdb ids and posters`() {
        val json = """[{"score":1.1,"show":{"name":"Pioneer One","premiered":"2010-06-16","genres":["Drama"],
            "rating":{"average":7.4},"externals":{"thetvdb":170551},"image":{"medium":"m.jpg","original":"o.jpg"}}},
            {"score":0.5,"show":{"name":"No Ids","externals":{},"image":null,"rating":{"average":null}}}]"""
        val hits = TvMazeClient(HttpGet { json.toByteArray() }).searchShows("pioneer one")
        assertEquals("Pioneer One", hits[0].name)
        assertEquals("170551", hits[0].tvdbId)
        assertEquals("o.jpg", hits[0].posterUrl)
        assertEquals(2010, hits[0].year)
        assertEquals(7.4, hits[0].rating)
        assertNull(hits[1].tvdbId)
    }

    @Test
    fun `json reader handles the TVmaze shapes`() {
        val v = Json.parse("""{"a":[1,2.5,-3e2],"b":{"c":null,"d":true},"e":"x\"yé\n"}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals(mapOf("c" to null, "d" to true), v["b"])
        assertEquals("x\"yé\n", v["e"])
    }

    @Test
    fun `tvmaze client builds a season guide without specials`() {
        val show = """{"id":1292,"name":"The Office","genres":["Comedy"],"premiered":"2001-07-09",
            "image":{"medium":"m.jpg","original":"o.jpg"},"summary":"<p>Paper.</p>"}"""
        val eps = """[
            {"id":1,"season":1,"number":1,"name":"Downsize","runtime":30,"airdate":"2001-07-09","type":"regular","image":{"medium":"s1.jpg"},"summary":"<p>Hi</p>"},
            {"id":2,"season":1,"number":2,"name":"Work Experience","runtime":30,"airdate":null,"type":"regular","image":null,"summary":null},
            {"id":3,"season":0,"number":1,"name":"Special","runtime":45,"type":"significant_special"},
            {"id":4,"season":2,"number":1,"name":"Merger","runtime":30,"type":"regular"}]"""
        val urls = ArrayList<String>()
        val client = TvMazeClient(HttpGet { url ->
            urls += url
            (if (url.contains("/episodes")) eps else show).toByteArray()
        })

        val g = client.guideByTvdb("78107")!!

        assertEquals(listOf("https://api.tvmaze.com/lookup/shows?thetvdb=78107", "https://api.tvmaze.com/shows/1292/episodes"), urls)
        assertEquals("The Office", g.name)
        assertEquals("o.jpg", g.posterUrl)
        assertEquals("Paper.", g.summary)
        assertEquals(listOf(1, 2), g.seasons.keys.toList())
        assertEquals(listOf("Downsize", "Work Experience"), g.seasons.getValue(1).map { it.name })
        assertEquals("s1.jpg", g.seasons.getValue(1)[0].stillUrl)
        assertNull(g.seasons.getValue(1)[1].stillUrl)
        assertEquals("S02E01", g.seasons.getValue(2)[0].code)

        assertNull(TvMazeClient(HttpGet { throw java.io.IOException("HTTP 404") }).guideByTvdb("0"))
    }
}
