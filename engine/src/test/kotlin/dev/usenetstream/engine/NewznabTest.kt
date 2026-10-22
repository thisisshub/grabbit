package dev.usenetstream.engine

import dev.usenetstream.engine.newznab.HttpGet
import dev.usenetstream.engine.newznab.IndexerConfig
import dev.usenetstream.engine.newznab.IndexerException
import dev.usenetstream.engine.newznab.NewznabClient
import dev.usenetstream.engine.newznab.SearchCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val KEY = "SECRETKEY123"

/** Trimmed copy of a real NZBGeek search reply. */
private val SEARCH_XML = """
<?xml version="1.0" encoding="utf-8" ?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
<channel><title>api.nzbgeek.info</title><newznab:response offset="0" total="2" />
<item><title>Big.Buck.Bunny.2008.1080p.x264-60fps.Surround</title>
<link>https://api.nzbgeek.info/api?t=get&amp;id=abc&amp;apikey=$KEY</link>
<pubDate>Mon, 29 May 2017 09:28:00 +0000</pubDate><category>Movies &gt; HD</category>
<enclosure url="https://api.nzbgeek.info/api?t=get&amp;id=abc&amp;apikey=$KEY" length="987742000" type="application/x-nzb"/>
<newznab:attr name="category" value="2040"/><newznab:attr name="size" value="987742000"/><newznab:attr name="grabs" value="42"/>
<newznab:attr name="guid" value="abc"/><newznab:attr name="coverurl" value="https://api.nzbgeek.info/covers/movies/01254207-cover.jpg"/>
<newznab:attr name="imdb" value="01254207"/><newznab:attr name="imdbtitle" value="Big Buck Bunny"/><newznab:attr name="imdbyear" value="2008"/>
<newznab:attr name="imdbscore" value="6.5"/><newznab:attr name="genre" value="Animation, Short, Comedy"/><newznab:attr name="runtime" value="10 min"/>
<newznab:attr name="password" value="0"/>
</item>
<item><title>No.Enclosure</title><link>https://x/get?id=def</link><newznab:attr name="password" value="2"/></item>
</channel></rss>
""".trimIndent()

private val NZB_XML = """
<?xml version="1.0" encoding="UTF-8"?>
<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
<file poster="p" date="1" subject="&quot;bbb.mkv&quot; yEnc (1/1)"><groups><group>a.b.test</group></groups>
<segments><segment bytes="10" number="1">m@x</segment></segments></file></nzb>
""".trimIndent()

class NewznabTest {
    private val requests = ArrayList<String>()

    private fun client(base: String = "https://api.nzbgeek.info/", reply: (String) -> String) =
        NewznabClient(IndexerConfig(base, KEY), HttpGet { url -> requests += url; reply(url).toByteArray() })

    @Test
    fun `search builds the API url and parses results`() {
        val results = client { SEARCH_XML }.search("big buck bunny", SearchCategory.MOVIES, limit = 10)

        assertEquals(
            "https://api.nzbgeek.info/api?t=search&q=big+buck+bunny&cat=2000&limit=10&extended=1&apikey=$KEY",
            requests.single(),
        )
        assertEquals(2, results.size)
        val bbb = results[0]
        assertEquals("Big.Buck.Bunny.2008.1080p.x264-60fps.Surround", bbb.title)
        assertEquals(987_742_000L, bbb.sizeBytes)
        assertEquals(42, bbb.grabs)
        assertEquals("Movies > HD", bbb.category)
        assertTrue(bbb.nzbUrl.contains("id=abc"))
        assertEquals("https://x/get?id=def", results[1].nzbUrl)
        assertEquals(null, results[1].sizeBytes)
        assertEquals("abc", bbb.guid)
        assertEquals("Big Buck Bunny", bbb.imdbTitle)
        assertEquals(2008, bbb.year)
        assertEquals(6.5, bbb.score)
        assertEquals("10 min", bbb.runtime)
        assertTrue(bbb.coverUrl!!.endsWith("01254207-cover.jpg"))
        assertFalse(bbb.passworded)
        // password=2 is "may hold an inner archive", not locked.
        assertFalse(results[1].passworded)
        assertTrue(results[1].innerArchive)
    }

    @Test
    fun `latest feeds and IMDb lookup build the right queries`() {
        val c = client { SEARCH_XML }
        c.latestMovies(genre = "Animation", limit = 20)
        c.latestTv(limit = 20)
        assertEquals(2, c.movieByImdb("01254207").size)
        assertEquals(
            listOf(
                "https://api.nzbgeek.info/api?t=movie&cat=2000&genre=Animation&limit=20&extended=1&apikey=$KEY",
                "https://api.nzbgeek.info/api?t=tvsearch&cat=5000&limit=20&extended=1&apikey=$KEY",
                "https://api.nzbgeek.info/api?t=movie&imdbid=1254207&limit=100&extended=1&apikey=$KEY",
            ),
            requests,
        )
    }

    @Test
    fun `nzbUrlFor rebuilds a grab link from the guid`() {
        assertEquals("https://api.nzbgeek.info/api?t=get&id=abc&apikey=$KEY", client { "" }.nzbUrlFor("abc"))
    }

    @Test
    fun `base url may already end in api`() {
        client(base = "https://indexer.example/api/") { SEARCH_XML }.search("x")
        assertTrue(requests.single().startsWith("https://indexer.example/api?t=search&q=x&"))
    }

    @Test
    fun `error replies become clear messages without the API key`() {
        val bad = assertFailsWith<IndexerException> {
            client { """<?xml version="1.0"?><error code="100" description="Incorrect user credentials"/>""" }.search("x")
        }
        assertEquals(100, bad.code)
        assertTrue(bad.message!!.startsWith("indexer rejected the API key"))

        val net = assertFailsWith<java.io.IOException> {
            NewznabClient(IndexerConfig("https://i.example", KEY), HttpGet { throw java.io.IOException("HTTP 503") }).search("x")
        }
        assertFalse(net.message!!.contains(KEY), net.message)
        assertTrue(net.message!!.contains("apikey=***"))
    }

    @Test
    fun `fetchNzb returns a parsed NZB and rejects an error document`() {
        val c = client { url ->
            when {
                url.contains("t=search") -> SEARCH_XML
                url.contains("id=abc") -> NZB_XML
                else -> """<error code="429" description="Request limit reached"/>"""
            }
        }
        val results = c.search("x")

        val nzb = c.fetchNzb(results[0])
        assertEquals("bbb.mkv", nzb.files.single().name)

        val limit = assertFailsWith<IndexerException> { c.fetchNzb(results[1]) }
        assertEquals(429, limit.code)
    }
}
