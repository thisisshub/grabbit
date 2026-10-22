package dev.usenetstream.feedback

import android.content.Context
import android.os.Build
import dev.usenetstream.BuildConfig
import dev.usenetstream.engine.newznab.DeviceCaps
import java.net.URLEncoder

/**
 * Beta feedback goes to GitHub issues. Each kind opens its issue form (.github/ISSUE_TEMPLATE/<kind>.yml), which
 * applies its own labels; fields are pre-filled through the form's field ids. Nothing secret is sent: no server,
 * user name, password or API key, and error text is scrubbed of anything key-like.
 */
object Feedback {
    enum class Kind(val template: String, val label: String) {
        PLAYBACK("playback.yml", "Playback problem"),
        BUG("bug.yml", "Something else is broken"),
        IDEA("idea.yml", "Suggest an idea"),
    }

    /** The last playback failure, offered to the playback report so testers don't have to retype it. */
    data class Failure(val title: String, val release: String?, val reason: String, val at: Long = System.currentTimeMillis())

    val enabled: Boolean get() = BuildConfig.FEEDBACK_REPO.contains('/')

    fun issueUrl(context: Context, kind: Kind, caps: DeviceCaps, isTv: Boolean, failure: Failure? = null): String? {
        if (!enabled) return null
        val fields = linkedMapOf(
            "version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "device" to if (isTv) "Android TV" else "Phone",
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "diagnostics" to diagnostics(context, caps),
        )
        var title = ""
        if (kind == Kind.PLAYBACK && failure != null) {
            title = "Can't play ${failure.title}"
            fields["title-name"] = failure.title
            failure.release?.let { fields["release"] = it }
            fields["error"] = scrub(failure.reason)
        }
        val query = buildList {
            add("template=${kind.template}")
            if (title.isNotEmpty()) add("title=${enc(title)}")
            fields.forEach { (k, v) -> add("$k=${enc(v)}") }
        }.joinToString("&")
        return "https://github.com/${BuildConfig.FEEDBACK_REPO}/issues/new?$query"
    }

    private fun diagnostics(context: Context, caps: DeviceCaps): String {
        val dm = context.resources.displayMetrics
        return listOf(
            "screen ${dm.widthPixels}x${dm.heightPixels}, max video ${caps.maxScreenHeight}p",
            "decodes: " + listOfNotNull(
                "HEVC".takeIf { caps.hevc }, "AV1".takeIf { caps.av1 }, "HDR10".takeIf { caps.hdr10 },
                "Dolby Vision".takeIf { caps.dolbyVision }, "DTS".takeIf { caps.dts }, "TrueHD".takeIf { caps.trueHd },
            ).joinToString(", ").ifEmpty { "H.264 only" },
            "heap ${Runtime.getRuntime().maxMemory() / 1_000_000} MB",
        ).joinToString("\n")
    }

    /** Error text can quote URLs; drop anything that looks like a key or credential. */
    fun scrub(text: String): String = text
        .replace(Regex("""(?i)(apikey|api_key|key|token|pass(word)?|user(name)?)=[^&\s"]+"""), "$1=…")
        .replace(Regex("""motn-key-\S+"""), "…")
        .replace(Regex("""https?://\S+"""), "<link>")
        .take(1500)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
