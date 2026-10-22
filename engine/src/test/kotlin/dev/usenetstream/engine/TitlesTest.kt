package dev.usenetstream.engine

import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.Titles
import kotlin.test.Test
import kotlin.test.assertEquals

class TitlesTest {
    private fun r(title: String, imdb: String? = null, grabs: Int? = null, size: Long? = null, pw: Boolean = false, cover: String? = null) =
        NzbResult(title, "u:$title", size, null, null, grabs, imdbId = imdb, imdbTitle = imdb?.let { "Big Buck Bunny" },
            year = imdb?.let { 2008 }, coverUrl = cover, passworded = pw)

    @Test
    fun `groups by IMDb id, else by cleaned name, keeping search order`() {
        val groups = Titles.group(
            listOf(
                r("Big.Buck.Bunny.2008.1080p.x264-60fps", imdb = "01254207", grabs = 2413),
                r("Some.Show.S01E02.720p.WEB", grabs = 5),
                r("big buck bunny 480p h264", imdb = "01254207", grabs = 217, cover = "c.jpg"),
                r("Some.Show.S01E03.1080p.WEB", grabs = 9),
            ),
        )

        assertEquals(listOf("Big Buck Bunny", "Some Show"), groups.map { it.title })
        assertEquals(2, groups[0].releases.size)
        assertEquals(2008, groups[0].year)
        assertEquals("c.jpg", groups[0].coverUrl) // taken from whichever release has it
        assertEquals(2, groups[1].releases.size)
    }

    @Test
    fun `a movie with one TV-filed release stays a movie`() {
        val movie = r("Big.Buck.Bunny.2008.1080p", imdb = "01254207").copy(category = "Movies > HD")
        val oddOne = r("big buck bunny 480p h264", imdb = "01254207").copy(category = "TV > Documentary")
        assertEquals(false, Titles.group(listOf(movie, oddOne)).single().isShow)
        val allTv = r("Some.Show.S01E01.720p").copy(category = "TV > HD")
        assertEquals(true, Titles.group(listOf(allTv)).single().isShow)
    }

    @Test
    fun `untagged releases join the IMDb group with the same name`() {
        val groups = Titles.group(
            listOf(
                r("BigBuckBunny.640x360.m4v"),
                r("Big.Buck.Bunny.2008.1080p", imdb = "01254207"),
                r("big buck bunny 480p h264"),
            ),
        )
        assertEquals(listOf("Big Buck Bunny"), groups.map { it.title })
        assertEquals(3, groups.single().releases.size)
    }

    @Test
    fun `best release skips passworded, prefers grabs then smaller size`() {
        val g = Titles.group(
            listOf(
                r("X.2001.1080p", imdb = "1", grabs = 900, pw = true),
                r("X.2001.720p", imdb = "1", grabs = 50, size = 2_000),
                r("X.2001.480p", imdb = "1", grabs = 50, size = 1_000),
            ),
        ).single()
        assertEquals("X.2001.480p", g.best.title)
    }

    @Test
    fun `clean name strips year, episode and quality tags`() {
        assertEquals("Big Buck Bunny", Titles.cleanName("Big.Buck.Bunny.2008.1080p.x264-60fps"))
        assertEquals("big buck bunny", Titles.cleanName("big buck bunny 480p h264"))
        assertEquals("The Show", Titles.cleanName("The_Show.S02E10.HDTV"))
        assertEquals("1917", Titles.cleanName("1917"))
    }
}
