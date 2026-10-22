package dev.usenetstream.engine

import dev.usenetstream.engine.subs.OpenSubtitlesClient
import dev.usenetstream.engine.subs.SubtitleQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenSubtitlesTest {
    // Shape of a real /subtitles reply, trimmed.
    private val reply = """{"total_count":3,"data":[
      {"attributes":{"language":"en","download_count":120,"machine_translated":true,"from_trusted":false,"release":"Movie.2010.WEB","files":[{"file_id":11,"file_name":"a.srt"}]}},
      {"attributes":{"language":"hi","download_count":900,"from_trusted":true,"release":"Movie.2010.1080p","files":[{"file_id":22,"file_name":"b.srt"}]}},
      {"attributes":{"language":"en","download_count":80,"from_trusted":true,"hearing_impaired":true,"release":"Movie.2010.1080p.BluRay.x264","files":[{"file_id":33,"file_name":"c.srt"}]}}
    ]}"""

    @Test
    fun `wanted languages first, then human-made, trusted, popular`() {
        val hits = OpenSubtitlesClient.parseSearch(reply, listOf("en", "hi"))
        assertEquals(listOf(33L, 11L, 22L), hits.map { it.fileId })
        assertTrue(hits.first().hearingImpaired)
        assertTrue(hits[1].machineTranslated)
    }

    @Test
    fun `search urls are lowercase, alphabetical and use bare imdb numbers`() {
        val c = OpenSubtitlesClient("k", "Grabbit v1") { _, _, _ -> "{}" }
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?episode_number=2&languages=en%2Chi&parent_imdb_id=1748166&season_number=1",
            c.searchUrl(SubtitleQuery(parentImdbId = "tt1748166", season = 1, episode = 2, languages = listOf("hi", "en"))),
        )
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?imdb_id=31969655&languages=en", c.searchUrl(SubtitleQuery(imdbId = "tt31969655")))
    }

    @Test
    fun `download follows the short-lived link`() {
        val calls = ArrayList<String>()
        val c = OpenSubtitlesClient("k", "Grabbit v1") { url, headers, body ->
            calls += url
            // The key goes to the API only, never to the file host.
            assertEquals(if (url.startsWith("https://api.")) "k" else null, headers["Api-Key"])
            when {
                url.endsWith("/download") -> { assertEquals("""{"file_id":33}""", body); """{"link":"https://dl.example/x.srt","remaining":4}""" }
                else -> "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
            }
        }
        assertTrue(c.download(33).contains("Hello"))
        assertEquals(listOf("https://api.opensubtitles.com/api/v1/download", "https://dl.example/x.srt"), calls)
    }

    @Test
    fun `release match score counts shared words`() {
        assertEquals(5, OpenSubtitlesClient.matchScore("Movie.2010.1080p.BluRay.x264-GRP", "Movie.2010.1080p.BluRay.x264-OTHER"))
    }
}
