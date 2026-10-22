package dev.usenetstream.engine.newznab

import dev.usenetstream.engine.nzb.Nzb
import dev.usenetstream.engine.nzb.NzbParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

data class IndexerConfig(val baseUrl: String, val apiKey: String)

enum class SearchCategory(val newznabId: String?) {
    ALL(null),
    MOVIES("2000"),
    TV("5000"),
}

data class NzbResult(
    val title: String,
    val nzbUrl: String,
    val sizeBytes: Long?,
    val pubDate: String?,
    val category: String?,
    val grabs: Int?,
    /** Indexer id; with [NewznabClient.nzbUrlFor] it rebuilds the NZB link even after an API key change. */
    val guid: String? = null,
    // Extended attributes (extended=1); indexers fill them for releases they matched to IMDb/TVDB.
    val coverUrl: String? = null,
    val imdbId: String? = null,
    val imdbTitle: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val genre: String? = null,
    val tagline: String? = null,
    val runtime: String? = null,
    /** Newznab password=1: the archive is password-protected (can't play). */
    val passworded: Boolean = false,
    /** Newznab password=2: may hold an archive inside the archive. Often a false alarm; the streamer checks and moves on. */
    val innerArchive: Boolean = false,
    // TV attributes: season/episode as numbers ("S02" -> 2), show ids for grouping and guide lookup.
    val season: Int? = null,
    val episode: Int? = null,
    val tvdbId: String? = null,
    val tvmazeId: String? = null,
    val showTitle: String? = null,
    val language: String? = null,
) {
    val isTv: Boolean get() = tvdbId != null || tvmazeId != null || category?.startsWith("TV") == true
}

/** Newznab error reply, e.g. 100 bad API key, 429 request limit reached. */
class IndexerException(val code: Int?, message: String) : IOException(message)

fun interface HttpGet {
    /** Response body; throws [IOException] for network errors and non-2xx statuses. */
    fun get(url: String): ByteArray
}

/** Works with any Newznab indexer (NZBGeek, NZBFinder, DrunkenSlug...). */
class NewznabClient(private val config: IndexerConfig, private val http: HttpGet = UrlConnectionGet()) {

    fun search(query: String, category: SearchCategory = SearchCategory.ALL, limit: Int = 50): List<NzbResult> {
        val params = buildList {
            add("t" to "search")
            add("q" to query)
            category.newznabId?.let { add("cat" to it) }
            add("limit" to limit.toString())
            add("extended" to "1")
        }
        return feed(params)
    }

    /** Newest movie releases, optionally one genre (e.g. "Animation"). */
    fun latestMovies(genre: String? = null, limit: Int = 100): List<NzbResult> = feed(
        buildList {
            add("t" to "movie")
            add("cat" to SearchCategory.MOVIES.newznabId!!)
            genre?.let { add("genre" to it) }
            add("limit" to limit.toString())
            add("extended" to "1")
        },
    )

    fun latestTv(limit: Int = 100): List<NzbResult> = feed(
        listOf("t" to "tvsearch", "cat" to SearchCategory.TV.newznabId!!, "limit" to limit.toString(), "extended" to "1"),
    )

    /** Every release of one movie; indexers want the IMDb number without the leading zeros. */
    fun movieByImdb(imdbId: String, limit: Int = 100): List<NzbResult> = feed(
        listOf("t" to "movie", "imdbid" to imdbId.removePrefix("tt").trimStart('0'), "limit" to limit.toString(), "extended" to "1"),
    )

    /** One season (or one episode) of a show; far fewer results than a name search. */
    fun tvSeason(tvdbId: String, season: Int, episode: Int? = null, limit: Int = 100): List<NzbResult> = feed(
        buildList {
            add("t" to "tvsearch")
            add("tvdbid" to tvdbId)
            add("season" to season.toString())
            episode?.let { add("ep" to it.toString()) }
            add("limit" to limit.toString())
            add("extended" to "1")
        },
    )

    private fun feed(params: List<Pair<String, String>>): List<NzbResult> =
        fetchXml(apiUrl(params)).documentElement.descendants("item").map(::toResult)

    fun fetchNzb(result: NzbResult): Nzb {
        val bytes = try {
            http.get(result.nzbUrl)
        } catch (e: IOException) {
            throw IOException("NZB download failed (${redact(result.nzbUrl)}): ${e.message}", e)
        }
        // Indexers answer grab-limit / bad-id problems with an <error> document instead of an NZB.
        throwIfError(parseXml(bytes))
        val nzb = NzbParser.parse(bytes.inputStream())
        if (nzb.files.isEmpty()) throw IndexerException(null, "NZB for \"${result.title}\" has no files")
        return nzb
    }

    fun nzbUrlFor(guid: String): String = apiUrl(listOf("t" to "get", "id" to guid))

    internal fun apiUrl(params: List<Pair<String, String>>): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val api = if (base.endsWith("/api")) base else "$base/api"
        val query = (params + ("apikey" to config.apiKey)).joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$api?$query"
    }

    private fun fetchXml(url: String): Document {
        val bytes = try {
            http.get(url)
        } catch (e: IOException) {
            throw IOException("indexer request failed (${redact(url)}): ${e.message}", e)
        }
        return parseXml(bytes).also(::throwIfError)
    }

    private fun throwIfError(doc: Document) {
        val root = doc.documentElement
        if (root.name() != "error") return
        val code = root.getAttribute("code").toIntOrNull()
        val description = root.getAttribute("description").ifBlank { "unknown error" }
        val friendly = when (code) {
            100, 101, 102 -> "indexer rejected the API key"
            429 -> "indexer request limit reached"
            else -> "indexer error"
        }
        throw IndexerException(code, "$friendly ($code: $description)")
    }

    private fun toResult(item: Element): NzbResult {
        val attrs = item.children("attr").associate { it.getAttribute("name") to it.getAttribute("value") }
        val enclosure = item.children("enclosure").firstOrNull()
        val link = enclosure?.getAttribute("url")?.ifBlank { null } ?: item.childText("link")
            ?: throw IndexerException(null, "result without NZB link")
        return NzbResult(
            title = item.childText("title").orEmpty(),
            nzbUrl = link,
            sizeBytes = attrs["size"]?.toLongOrNull() ?: enclosure?.getAttribute("length")?.toLongOrNull(),
            pubDate = item.childText("pubDate"),
            category = item.childText("category"),
            grabs = attrs["grabs"]?.toIntOrNull(),
            guid = attrs["guid"]?.ifBlank { null },
            coverUrl = attrs["coverurl"]?.ifBlank { null },
            imdbId = attrs["imdb"]?.ifBlank { null },
            imdbTitle = attrs["imdbtitle"]?.ifBlank { null },
            year = attrs["imdbyear"]?.toIntOrNull() ?: attrs["year"]?.toIntOrNull(),
            score = attrs["imdbscore"]?.toDoubleOrNull(),
            genre = attrs["genre"]?.ifBlank { null },
            tagline = attrs["imdbtagline"]?.ifBlank { null },
            runtime = attrs["runtime"]?.ifBlank { null },
            passworded = attrs["password"] == "1",
            innerArchive = attrs["password"] == "2",
            season = attrs["season"]?.filter(Char::isDigit)?.toIntOrNull(),
            episode = attrs["episode"]?.filter(Char::isDigit)?.toIntOrNull(),
            tvdbId = attrs["tvdbid"]?.takeIf { it.isNotBlank() && it != "0" },
            tvmazeId = attrs["tvmazeid"]?.takeIf { it.isNotBlank() && it != "0" },
            showTitle = attrs["tvtitle"]?.ifBlank { null },
            language = attrs["language"]?.ifBlank { null },
        )
    }

    companion object {
        private val API_KEY = Regex("""(?i)(apikey=)[^&]*""")

        /** Never put the API key in logs or error text. */
        fun redact(url: String): String = API_KEY.replace(url, "$1***")
    }
}

class UrlConnectionGet(private val timeoutMs: Int = 20_000) : HttpGet {
    override fun get(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Grabbit/0.1")
            val status = conn.responseCode
            if (status !in 200..299) {
                // Newznab sends <error> documents with 200 mostly, but some use 4xx; keep the body for the caller.
                val body = conn.errorStream?.readBytes()
                if (body != null && body.isNotEmpty() && String(body, 0, minOf(body.size, 200)).contains("<error")) {
                    return body
                }
                throw IOException("HTTP $status")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

private fun parseXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return try {
        builder.parse(bytes.inputStream())
    } catch (e: org.xml.sax.SAXException) {
        throw IndexerException(null, "indexer sent something that isn't XML")
    }
}

private fun Node.name(): String = localName ?: nodeName.substringAfter(':')

private fun Element.children(name: String): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && n.name() == name) out += n as Element
    }
    return out
}

private fun Element.descendants(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(e: Element) {
        val nodes = e.childNodes
        for (i in 0 until nodes.length) {
            val c = nodes.item(i) as? Element ?: continue
            if (c.name() == name) out += c else walk(c)
        }
    }
    walk(this)
    return out
}

private fun Element.childText(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }
