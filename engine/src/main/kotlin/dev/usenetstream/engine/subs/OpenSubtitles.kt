package dev.usenetstream.engine.subs

import dev.usenetstream.engine.tv.Json
import dev.usenetstream.engine.tv.int
import dev.usenetstream.engine.tv.list
import dev.usenetstream.engine.tv.obj
import dev.usenetstream.engine.tv.str
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** One downloadable subtitle file. */
data class SubtitleHit(
    val fileId: Long,
    /** ISO 639-1, e.g. "en". */
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
    val fromTrusted: Boolean,
)

/** What to look for: a movie by IMDb id, an episode by its show's IMDb id + numbers, or loose text. */
data class SubtitleQuery(
    val imdbId: String? = null,
    val parentImdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val text: String? = null,
    val languages: List<String> = listOf("en"),
)

class SubtitleException(message: String) : IOException(message)

fun interface Http {
    /** [body] null = GET. Returns the response body; throws [SubtitleException] with the API's message on errors. */
    fun send(url: String, headers: Map<String, String>, body: String?): String
}

class UrlHttp(private val timeoutMs: Int = 20_000) : Http {
    override fun send(url: String, headers: Map<String, String>, body: String?): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { String(it.readBytes()) }.orEmpty()
                val msg = runCatching { Json.parse(err).obj()?.str("message") }.getOrNull()
                throw SubtitleException(
                    when (code) {
                        401, 403 -> msg ?: "OpenSubtitles refused the key"
                        406, 429 -> msg ?: "OpenSubtitles daily download limit reached"
                        else -> msg ?: "OpenSubtitles HTTP $code"
                    },
                )
            }
            return conn.inputStream.use { String(it.readBytes()) }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * OpenSubtitles.com REST API v1: search, then turn a file id into a short-lived link and fetch the .srt.
 * Needs a (free) API key; logging in with a free account raises the daily download quota.
 */
class OpenSubtitlesClient(private val apiKey: String, private val userAgent: String, private val http: Http = UrlHttp()) {
    private val base = "https://api.opensubtitles.com/api/v1"
    private var token: String? = null

    private fun headers() = buildMap {
        put("Api-Key", apiKey)
        put("User-Agent", userAgent)
        put("Accept", "application/json")
        token?.let { put("Authorization", "Bearer $it") }
    }

    fun login(username: String, password: String) {
        val res = http.send("$base/login", headers(), """{"username":${quote(username)},"password":${quote(password)}}""")
        token = Json.parse(res).obj()?.str("token") ?: throw SubtitleException("OpenSubtitles login failed")
    }

    fun search(q: SubtitleQuery): List<SubtitleHit> = parseSearch(http.send(searchUrl(q), headers(), null), q.languages)

    /** The subtitle file's text (SRT/VTT as published). */
    fun download(fileId: Long): String {
        val res = Json.parse(http.send("$base/download", headers(), """{"file_id":$fileId}""")).obj()
        val link = res?.str("link") ?: throw SubtitleException(res?.str("message") ?: "no download link")
        return http.send(link, mapOf("User-Agent" to userAgent), null)
    }

    fun searchUrl(q: SubtitleQuery): String {
        // Parameters must be alphabetical and lowercase, or the API answers with a redirect.
        val params = sortedMapOf<String, String>()
        q.imdbId?.let { params["imdb_id"] = it.removePrefix("tt").trimStart('0') }
        q.parentImdbId?.let { params["parent_imdb_id"] = it.removePrefix("tt").trimStart('0') }
        q.season?.let { params["season_number"] = "$it" }
        q.episode?.let { params["episode_number"] = "$it" }
        if (q.imdbId == null && q.parentImdbId == null) q.text?.let { params["query"] = it.lowercase() }
        params["languages"] = q.languages.map { it.lowercase() }.sorted().joinToString(",")
        return "$base/subtitles?" + params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    companion object {
        /** Best first: wanted language order, then not machine-translated, trusted uploader, most downloaded. */
        fun parseSearch(json: String, languages: List<String> = emptyList()): List<SubtitleHit> =
            Json.parse(json).obj()?.get("data").list().mapNotNull { d ->
                val a = d.obj()?.get("attributes").obj() ?: return@mapNotNull null
                val file = a["files"].list().firstOrNull().obj() ?: return@mapNotNull null
                SubtitleHit(
                    fileId = (file["file_id"] as? Double)?.toLong() ?: return@mapNotNull null,
                    language = a.str("language") ?: "?",
                    release = a.str("release") ?: file.str("file_name").orEmpty(),
                    downloads = a.int("download_count") ?: 0,
                    hearingImpaired = a["hearing_impaired"] == true,
                    machineTranslated = a["machine_translated"] == true || a["ai_translated"] == true,
                    fromTrusted = a["from_trusted"] == true,
                )
            }.sortedWith(
                compareBy<SubtitleHit> { h -> languages.indexOf(h.language).let { if (it < 0) Int.MAX_VALUE else it } }
                    .thenBy { it.machineTranslated }
                    .thenByDescending { it.fromTrusted }
                    .thenByDescending { it.downloads },
            )

        /** How closely a subtitle's release name matches the video's (shared words); for the "best match" badge. */
        fun matchScore(subRelease: String, videoRelease: String): Int {
            fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
            return (words(subRelease) intersect words(videoRelease)).size
        }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
