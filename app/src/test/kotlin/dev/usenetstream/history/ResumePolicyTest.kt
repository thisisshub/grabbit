package dev.usenetstream.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumePolicyTest {
    private fun entry(pos: Long, dur: Long = 600_000) =
        WatchEntry("imdb:1", "T", "T.2008.480p", null, SourceKind.INDEXER, "guid", pos, dur, 0)

    @Test
    fun `resumes a few seconds back, but not when barely started or finished`() {
        assertEquals(0L, ResumePolicy.startAt(null))
        assertEquals(0L, ResumePolicy.startAt(entry(10_000)))
        assertEquals(325_000L, ResumePolicy.startAt(entry(330_000)))
        assertEquals(0L, ResumePolicy.startAt(entry(580_000))) // 96.7% = finished, start over
    }

    @Test
    fun `continue watching holds only started, unfinished titles`() {
        assertFalse(ResumePolicy.inContinueWatching(entry(10_000)))
        assertTrue(ResumePolicy.inContinueWatching(entry(300_000)))
        assertFalse(ResumePolicy.inContinueWatching(entry(599_000)))
        assertTrue(entry(599_000).finished)
        assertFalse(entry(599_000, dur = 0).finished) // unknown duration never counts as watched
        assertEquals(0.5f, entry(300_000).progress)
    }

    @Test
    fun `shows collapse to their latest episode and stay in continue watching when it is finished`() {
        fun epi(code: Int, pos: Long, at: Long, show: String = "tvdb:1") =
            WatchEntry("$show:S01E0$code", "Show", "r", null, SourceKind.INDEXER, "g", pos, 600_000, at, showKey = show, season = 1, episode = code)
        val movieDone = entry(599_000).copy(id = "imdb:9", updatedAt = 50)
        val movieHalf = entry(300_000).copy(id = "imdb:8", updatedAt = 40)
        val rows = listOf(epi(1, 600_000, 10), epi(2, 600_000, 30), epi(3, 5_000, 20), epi(1, 100_000, 60, show = "tvdb:2"), movieDone, movieHalf)

        val cards = ResumePolicy.cards(rows)
        assertEquals(listOf("tvdb:2:S01E01", "imdb:9", "imdb:8", "tvdb:1:S01E02"), cards.map { it.id })

        // Show 1's latest is a finished episode -> still listed (next one is up); finished movie is not.
        assertEquals(listOf("tvdb:2:S01E01", "imdb:8", "tvdb:1:S01E02"), ResumePolicy.continueWatching(rows).map { it.id })
        assertEquals("S01E02", cards.last().code)
    }
}
