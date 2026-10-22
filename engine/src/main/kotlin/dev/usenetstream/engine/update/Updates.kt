package dev.usenetstream.engine.update

import dev.usenetstream.engine.tv.Json
import dev.usenetstream.engine.tv.list
import dev.usenetstream.engine.tv.obj
import dev.usenetstream.engine.tv.str

/**
 * "0.1.0-beta.3" style versions. A release beats any pre-release of the same number (0.1.0 > 0.1.0-beta.9);
 * pre-releases compare by their numeric parts (beta.3 < beta.10). Build suffixes like "-friends" are ignored.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val pre: List<Int>?) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (pre == null || other.pre == null) return if (pre == other.pre) 0 else if (pre == null) 1 else -1
        for (i in 0 until maxOf(pre.size, other.pre.size)) {
            val c = compareValues(pre.getOrElse(i) { -1 }, other.pre.getOrElse(i) { -1 })
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        private val RE = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.]+))?""")

        fun parse(text: String): AppVersion? {
            val m = RE.find(text.trim()) ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { tag ->
                // "beta.3" -> [3]; "friends" alone is a build flavour, not a pre-release.
                tag.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() || !tag.startsWith("friends") }
            }
            return AppVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 0, pre)
        }
    }
}

/** A published release with an installable APK. */
data class AppRelease(val tag: String, val version: AppVersion, val name: String, val notes: String, val apkUrl: String, val apkBytes: Long, val publishedAt: String?)

object Releases {
    /**
     * Newest release (pre-releases included, drafts skipped) that ships a public APK.
     * Assets meant for one group (names containing "friends") are never offered.
     */
    fun newest(githubReleasesJson: String): AppRelease? =
        Json.parse(githubReleasesJson).list().mapNotNull { r ->
            val o = r.obj() ?: return@mapNotNull null
            if (o["draft"] == true) return@mapNotNull null
            val tag = o.str("tag_name") ?: return@mapNotNull null
            val version = AppVersion.parse(tag) ?: return@mapNotNull null
            val apk = o["assets"].list().mapNotNull { it.obj() }.firstOrNull { a ->
                val n = a.str("name").orEmpty().lowercase()
                n.endsWith(".apk") && "friends" !in n
            } ?: return@mapNotNull null
            AppRelease(
                tag = tag,
                version = version,
                name = o.str("name") ?: tag,
                notes = o.str("body").orEmpty().trim(),
                apkUrl = apk.str("browser_download_url") ?: return@mapNotNull null,
                apkBytes = (apk["size"] as? Double)?.toLong() ?: 0L,
                publishedAt = o.str("published_at"),
            )
        }.maxByOrNull { it.version }

    /** The update to offer, or null when [current] is already up to date. */
    fun updateFor(current: String, githubReleasesJson: String): AppRelease? {
        val mine = AppVersion.parse(current) ?: return null
        return newest(githubReleasesJson)?.takeIf { it.version > mine }
    }
}
