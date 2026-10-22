package dev.usenetstream.feedback

import android.content.Context
import android.os.Build
import dev.usenetstream.BuildConfig
import dev.usenetstream.engine.newznab.DeviceCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Sends anonymous feedback and playback-error reports to the relay (a Supabase function that files GitHub issues).
 * Identity is a random per-install id used only for rate limiting; no account, server, title or release ever leaves.
 */
class Relay(context: Context, private val caps: DeviceCaps, private val isTv: Boolean) {
    private val prefs = context.applicationContext.getSharedPreferences("feedback", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val enabled: Boolean get() = BuildConfig.FEEDBACK_URL.startsWith("https://")

    var autoReports: Boolean
        get() = prefs.getBoolean("auto_reports", true)
        set(v) = prefs.edit().putBoolean("auto_reports", v).apply()

    private val install: String by lazy {
        prefs.getString("install", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install", it).apply() }
    }

    /** The hand-written form. Returns null on success, else a short reason to show. */
    suspend fun sendFeedback(category: String, mood: String?, message: String): String? = withContext(Dispatchers.IO) {
        val body = base("feedback").put("category", category).put("message", Feedback.scrub(message.trim()))
        mood?.let { body.put("mood", it) }
        post(body)
    }

    /** Fire-and-forget; queued for later when offline. Only the error class goes out. */
    fun reportPlayback(category: String, code: String?) {
        if (!enabled || !autoReports) return
        val body = base("playback").put("category", category).put("code", code.orEmpty())
        scope.launch { if (post(body) != null) queue(body) }
    }

    /** Retries reports that couldn't be sent (no network at the time). */
    fun flush() {
        if (!enabled) return
        val pending = JSONArray(prefs.getString("queue", "[]"))
        if (pending.length() == 0) return
        prefs.edit().putString("queue", "[]").apply()
        scope.launch { for (i in 0 until pending.length()) if (post(pending.getJSONObject(i)) != null) queue(pending.getJSONObject(i)) }
    }

    @Synchronized
    private fun queue(body: JSONObject) {
        val q = JSONArray(prefs.getString("queue", "[]"))
        if (q.length() < 20) q.put(body)
        prefs.edit().putString("queue", q.toString()).apply()
    }

    private fun base(kind: String) = JSONObject()
        .put("kind", kind)
        .put("install", install)
        .put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        .put("isTv", isTv)
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        .put(
            "decodes",
            listOfNotNull("HEVC".takeIf { caps.hevc }, "AV1".takeIf { caps.av1 }, "HDR10".takeIf { caps.hdr10 }, "DV".takeIf { caps.dolbyVision })
                .joinToString(", ").ifEmpty { "H.264" } + ", max ${caps.maxScreenHeight}p",
        )

    private fun post(body: JSONObject): String? = try {
        val conn = URI(BuildConfig.FEEDBACK_URL).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-grabbit-app", "grabbit-beta")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                null
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                runCatching { JSONObject(err).optString("error") }.getOrNull()?.ifEmpty { null } ?: "Couldn't send ($code)"
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "No connection. Try again in a bit."
    }

    companion object {
        /** Error text from opening a release -> a coarse, title-free category for the auto report. */
        fun categoryOf(reason: String): String {
            val r = reason.lowercase()
            return when {
                "compressed rar" in r -> "compressed-rar"
                "compressed 7z" in r -> "compressed-7z"
                "7z archive" in r || "zip archive" in r -> "unsupported-archive"
                "password" in r -> "archive-password"
                "missing rar volumes" in r || "first rar volume is missing" in r || "missing volumes" in r -> "missing-volumes"
                "article not found" in r || "missing articles" in r -> "missing-articles"
                "login" in r || "480" in r || "auth" in r -> "usenet-login"
                "nzb download failed" in r || "indexer" in r || "grab" in r -> "indexer"
                "no video" in r || "no media" in r || "no file in the nzb" in r -> "no-video"
                "timeout" in r || "timed out" in r || "connection" in r || "unable to resolve" in r -> "network"
                else -> "other"
            }
        }
    }
}
