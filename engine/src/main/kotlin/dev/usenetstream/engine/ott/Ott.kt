package dev.usenetstream.engine.ott

import dev.usenetstream.engine.tv.Json
import dev.usenetstream.engine.tv.int
import dev.usenetstream.engine.tv.list
import dev.usenetstream.engine.tv.obj
import dev.usenetstream.engine.tv.str
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** A streaming service (Netflix, Prime Video...) with the colours and logos its lane is drawn with. */
data class OttService(
    val id: String,
    val name: String,
    /** Brand colour as 0xAARRGGBB; near-black brands get a visible replacement, see [Brand]. */
    val color: Long,
    /** Second gradient stop for the lane hero. */
    val color2: Long,
    val logoWhite: String?,
    val logoOnDark: String?,
)

/** Where a title streams on one service. */
data class OttLink(val serviceId: String, val type: String, val link: String, val videoLink: String?)

data class OttTitle(
    /** Movie of the Night's own id; stable key for rows. */
    val id: String,
    val imdbId: String?,
    val title: String,
    val overview: String?,
    val year: Int?,
    val isSeries: Boolean,
    val genres: List<String>,
    /** 0..100 */
    val rating: Int?,
    val runtimeMin: Int?,
    val poster: String?,
    /** 16:9 key art with the title on it; best for wide cards. */
    val wide: String?,
    /** 16:9 key art without text; best behind a hero. */
    val backdrop: String?,
    val links: List<OttLink>,
) {
    fun linkOn(serviceId: String): OttLink? = links.firstOrNull { it.serviceId == serviceId && it.type != "rent" && it.type != "buy" }
        ?: links.firstOrNull { it.serviceId == serviceId }
}

/** One entry of a lane: a title, on which service, and why it is there. */
data class OttItem(val title: OttTitle, val serviceId: String, val rank: Int? = null, val since: Long? = null)

class OttException(message: String) : IOException(message)

/** Brand colours from the API, with fixes for those that would vanish on a dark page. */
object Brand {
    /** Apple TV and JioHotstar are listed as pure black. */
    private val OVERRIDES = mapOf(
        "apple" to (0xFF3A3A3CL to 0xFF8E8E93L),
        "hotstar" to (0xFF1F3FD6L to 0xFF7B2FF2L),
        "mubi" to (0xFF001588L to 0xFF3D5AFEL),
    )

    fun colors(id: String, apiHex: String?): Pair<Long, Long> {
        OVERRIDES[id]?.let { return it }
        val c = parseHex(apiHex) ?: 0xFF2F2FE4L
        return if (luminance(c) < 0.02) 0xFF3A3A3CL to 0xFF8E8E93L else c to darken(c, 0.45)
    }

    /** Black or white, whichever reads better on [argb]. */
    fun onColor(argb: Long): Long = if (contrast(0xFFFFFFFFL, argb) >= contrast(0xFF000000L, argb)) 0xFFFFFFFFL else 0xFF000000L

    fun parseHex(hex: String?): Long? {
        val h = hex?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
        return h.toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    fun luminance(argb: Long): Double {
        fun ch(v: Long): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun darken(argb: Long, by: Double): Long {
        fun ch(shift: Int) = ((argb shr shift and 0xFF) * (1 - by)).toLong().coerceIn(0, 255) shl shift
        return 0xFF000000L or ch(16) or ch(8) or ch(0)
    }
}

fun interface HeaderGet {
    fun get(url: String, headers: Map<String, String>): ByteArray
}

class UrlHeaderGet(private val timeoutMs: Int = 20_000) : HeaderGet {
    override fun get(url: String, headers: Map<String, String>): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val status = conn.responseCode
            if (status !in 200..299) {
                throw OttException(
                    when (status) {
                        401, 403 -> "streaming guide key was refused"
                        429 -> "streaming guide daily limit reached"
                        else -> "streaming guide HTTP $status"
                    },
                )
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Keeps each response on disk for [ttlMs] so the free API quota lasts (a lane refresh is ~10 calls).
 * A stale copy is still served when the network call fails.
 */
class DiskCachedGet(
    private val dir: File,
    private val ttlMs: Long,
    private val inner: HeaderGet,
    private val now: () -> Long = System::currentTimeMillis,
) : HeaderGet {
    override fun get(url: String, headers: Map<String, String>): ByteArray {
        dir.mkdirs()
        val file = File(dir, sha(url))
        if (file.exists() && now() - file.lastModified() < ttlMs) return file.readBytes()
        return try {
            inner.get(url, headers).also {
                file.writeBytes(it)
                file.setLastModified(now())
            }
        } catch (e: IOException) {
            if (file.exists()) file.readBytes() else throw e
        }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

/** Movie of the Night (Streaming Availability API v4): what's new and what's top on each service, per country. */
class MotnClient(private val apiKey: String, private val http: HeaderGet = UrlHeaderGet()) {
    private val base = "https://api.movieofthenight.com/v4"

    fun services(country: String): List<OttService> =
        parseServices(fetch("$base/countries/${enc(country.lowercase())}"))

    /** Today's top list (Netflix, Prime, Apple... others return nothing). */
    fun top(country: String, serviceId: String, series: Boolean? = null): List<OttItem> {
        val type = series?.let { "&show_type=" + if (it) "series" else "movie" }.orEmpty()
        return parseTop(fetch("$base/shows/top?country=${enc(country)}&service=${enc(serviceId)}$type"), serviceId, country)
    }

    /** Titles added in the last ~31 days, newest first. */
    fun newOn(country: String, serviceIds: List<String>, pages: Int = 2): List<OttItem> = changes(country, "new", serviceIds, pages, newestFirst = true)

    /** Titles leaving in the next ~31 days, soonest first. */
    fun leaving(country: String, serviceIds: List<String>, pages: Int = 1): List<OttItem> = changes(country, "expiring", serviceIds, pages, newestFirst = false)

    private fun changes(country: String, type: String, serviceIds: List<String>, pages: Int, newestFirst: Boolean): List<OttItem> {
        val out = ArrayList<OttItem>()
        var cursor: String? = null
        for (page in 0 until pages) {
            val url = buildString {
                append("$base/changes?country=${enc(country)}&change_type=$type&item_type=show")
                append("&order_direction=" + if (newestFirst) "desc" else "asc")
                append("&catalogs=${enc(serviceIds.joinToString(","))}")
                cursor?.let { append("&cursor=${enc(it)}") }
            }
            val (items, next) = parseChanges(fetch(url), country)
            out += items
            cursor = next ?: break
        }
        return out.distinctBy { it.title.id to it.serviceId }
    }

    private fun fetch(url: String): String = String(http.get(url, mapOf("X-API-Key" to apiKey, "Accept" to "application/json")))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        fun parseServices(json: String): List<OttService> =
            Json.parse(json).obj()?.get("services").list().mapNotNull { serviceOf(it.obj()) }

        fun parseTop(json: String, serviceId: String, country: String): List<OttItem> {
            val root = Json.parse(json)
            root.obj()?.str("message")?.let { throw OttException(it) }
            return root.list().mapNotNull { titleOf(it.obj(), country) }
                .mapIndexed { i, t -> OttItem(t, serviceId, rank = i + 1) }
        }

        /** Returns the items and the next cursor (null when there are no more pages). */
        fun parseChanges(json: String, country: String): Pair<List<OttItem>, String?> {
            val root = Json.parse(json).obj() ?: throw OttException("unexpected reply")
            root.str("message")?.let { throw OttException(it) }
            val shows = root["shows"].obj().orEmpty()
            val items = root["changes"].list().mapNotNull { c ->
                val ch = c.obj() ?: return@mapNotNull null
                val service = ch["service"].obj()?.str("id") ?: ch.str("service") ?: return@mapNotNull null
                val title = titleOf(shows[ch.str("showId")].obj(), country) ?: return@mapNotNull null
                OttItem(title, service, since = (ch["timestamp"] as? Double)?.toLong())
            }
            val next = root.str("nextCursor").takeIf { root["hasMore"] == true }
            return items to next
        }

        private fun serviceOf(s: Map<String, Any?>?): OttService? {
            s ?: return null
            val id = s.str("id") ?: return null
            val (c1, c2) = Brand.colors(id, s.str("themeColorCode"))
            val logos = s["imageSet"].obj()
            return OttService(id, s.str("name") ?: id, c1, c2, logos?.str("whiteImage"), logos?.str("darkThemeImage"))
        }

        private fun titleOf(s: Map<String, Any?>?, country: String): OttTitle? {
            s ?: return null
            val images = s["imageSet"].obj()
            fun img(kind: String, vararg sizes: String) = images?.get(kind).obj()?.let { m -> sizes.firstNotNullOfOrNull { m.str(it) } }
            val links = s["streamingOptions"].obj()?.get(country.lowercase()).list().mapNotNull { o ->
                val opt = o.obj() ?: return@mapNotNull null
                val sid = opt["service"].obj()?.str("id") ?: return@mapNotNull null
                OttLink(sid, opt.str("type") ?: "subscription", opt.str("link") ?: return@mapNotNull null, opt.str("videoLink"))
            }
            return OttTitle(
                id = s.str("id") ?: return null,
                imdbId = s.str("imdbId"),
                title = s.str("title") ?: return null,
                overview = s.str("overview"),
                year = s.int("releaseYear") ?: s.int("firstAirYear"),
                isSeries = s.str("showType") == "series",
                genres = s["genres"].list().mapNotNull { it.obj()?.str("name") },
                rating = s.int("rating"),
                runtimeMin = s.int("runtime"),
                poster = img("verticalPoster", "w480", "w360", "w600", "w240"),
                wide = img("horizontalPoster", "w720", "w1080", "w480"),
                backdrop = img("horizontalBackdrop", "w1080", "w1440", "w720"),
                links = links,
            )
        }
    }
}
