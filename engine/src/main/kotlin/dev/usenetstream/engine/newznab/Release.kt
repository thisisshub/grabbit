package dev.usenetstream.engine.newznab

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class Quality(val label: String) { SD("480p"), HD("720p"), FHD("1080p"), UHD("4K"), UNKNOWN("?") }
enum class Codec(val label: String) { H264("H.264"), HEVC("HEVC"), AV1("AV1"), XVID("XviD"), UNKNOWN("?") }
enum class AudioLayout(val label: String) { STEREO("2.0"), SURROUND("5.1"), ATMOS("Atmos"), UNKNOWN("?") }

/** What a scene name like `Show.S02E06.1080p.WEB.H265.DDP5.1.Atmos-GRP` tells us. */
data class ReleaseInfo(
    val quality: Quality,
    val codec: Codec,
    val hdr: Boolean,
    val dolbyVision: Boolean,
    val audio: AudioLayout,
    val dts: Boolean,
    val trueHd: Boolean,
    val languages: Set<String>,
    val season: Int?,
    val episode: Int?,
    /** A whole season (or several) in one release, no single episode number. */
    val seasonPack: Boolean,
)

object ReleaseParser {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val UHD = rx("""\b(2160p|4k|uhd)\b""")
    private val FHD = rx("""\b1080[pi]\b""")
    private val HD = rx("""\b720p\b""")
    private val SD = rx("""\b(480p|576p|360p|sd|dvdrip|xvid)\b""")
    private val HEVC = rx("""\b(x\.?265|h\.?265|hevc)\b""")
    private val AV1 = rx("""\bav1\b""")
    private val H264 = rx("""\b(x\.?264|h\.?264|avc)\b""")
    private val XVID = rx("""\b(xvid|divx)\b""")
    private val DV = rx("""\b(dv|dovi|dolby[ .]?vision)\b""")
    private val HDR = rx("""\b(hdr10\+?|hdr)\b""")
    private val ATMOS = rx("""\batmos\b""")
    private val SURROUND = rx("""\b((dd|ddp|eac3|ac3|dts|aac|truehd|flac)[ .+]?(5[ .]1|7[ .]1)|5[ .]1|7[ .]1)\b""")
    private val STEREO = rx("""\b(2[ .]0|stereo)\b""")
    private val DTS = rx("""\bdts(-?hd|-?x|-?ma)?\b""")
    private val TRUEHD = rx("""\btruehd\b""")
    private val EPISODE = rx("""\bs(\d{1,2})[ .]?e(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b""")
    private val SEASON_ONLY = rx("""\b(s(\d{1,2})|season[ .]?(\d{1,2}))\b""")
    private val LANGUAGES = listOf(
        "MULTi" to "Multi", "GERMAN" to "German", "FRENCH" to "French", "SPANISH" to "Spanish", "ITALIAN" to "Italian",
        "JAPANESE" to "Japanese", "KOREAN" to "Korean", "HINDI" to "Hindi", "DUTCH" to "Dutch", "NORDIC" to "Nordic",
        "POLISH" to "Polish", "RUSSIAN" to "Russian", "TAMIL" to "Tamil", "TELUGU" to "Telugu",
    ).map { (tag, name) -> rx("""\b$tag\b""") to name }

    fun parse(r: NzbResult): ReleaseInfo = parse(r.title, r.season, r.episode, r.language)

    fun parse(title: String, attrSeason: Int? = null, attrEpisode: Int? = null, attrLanguage: String? = null): ReleaseInfo {
        val t = title.replace('_', '.')
        val ep = EPISODE.find(t)
        val season = attrSeason ?: ep?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toInt() }
            ?: SEASON_ONLY.find(t)?.let { (it.groupValues[2].ifEmpty { it.groupValues[3] }).toIntOrNull() }
        val episode = attrEpisode ?: ep?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).toInt() }
        val languages = LANGUAGES.filter { (re, _) -> re.containsMatchIn(t) }.map { it.second }.toMutableSet()
        attrLanguage?.split(',', '-')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "English" }?.let(languages::addAll)
        return ReleaseInfo(
            quality = when {
                UHD.containsMatchIn(t) -> Quality.UHD
                FHD.containsMatchIn(t) -> Quality.FHD
                HD.containsMatchIn(t) -> Quality.HD
                SD.containsMatchIn(t) -> Quality.SD
                else -> Quality.UNKNOWN
            },
            codec = when {
                AV1.containsMatchIn(t) -> Codec.AV1
                HEVC.containsMatchIn(t) -> Codec.HEVC
                H264.containsMatchIn(t) -> Codec.H264
                XVID.containsMatchIn(t) -> Codec.XVID
                else -> Codec.UNKNOWN
            },
            hdr = HDR.containsMatchIn(t) || DV.containsMatchIn(t),
            dolbyVision = DV.containsMatchIn(t),
            audio = when {
                ATMOS.containsMatchIn(t) -> AudioLayout.ATMOS
                SURROUND.containsMatchIn(t) -> AudioLayout.SURROUND
                STEREO.containsMatchIn(t) -> AudioLayout.STEREO
                else -> AudioLayout.UNKNOWN
            },
            dts = DTS.containsMatchIn(t),
            trueHd = TRUEHD.containsMatchIn(t),
            languages = languages,
            season = season,
            episode = episode,
            seasonPack = season != null && episode == null,
        )
    }

    fun ageDays(pubDate: String?, now: Instant = Instant.now()): Long? = try {
        pubDate?.let { Duration.between(ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(), now).toDays() }
    } catch (e: Exception) {
        null
    }
}

/** What this phone can decode; the app fills it from MediaCodecList. */
data class DeviceCaps(
    val hevc: Boolean = true,
    val av1: Boolean = false,
    val dolbyVision: Boolean = false,
    val hdr10: Boolean = true,
    val dts: Boolean = false,
    val trueHd: Boolean = false,
    /** Tallest picture the screen can show; 4K releases on a 1080p screen only cost bandwidth (and garble on weak TV chips). */
    val maxScreenHeight: Int = 2160,
)

enum class Playability { OK, NO_AUDIO, NO }

fun ReleaseInfo.playabilityOn(caps: DeviceCaps): Playability = when {
    quality == Quality.UHD && caps.maxScreenHeight < 2160 -> Playability.NO
    codec == Codec.HEVC && !caps.hevc -> Playability.NO
    codec == Codec.AV1 && !caps.av1 -> Playability.NO
    // DV profile 5 has no HDR10 fallback layer; other profiles still play as HDR10/SDR.
    dolbyVision && !hdr && !caps.dolbyVision -> Playability.NO
    // Releases usually carry only one audio track; unsupported audio = silent video.
    (dts && !caps.dts) || (trueHd && !caps.trueHd) -> Playability.NO_AUDIO
    else -> Playability.OK
}

/** Release-level filter (details page). Empty sets mean "any". */
data class ReleaseFilter(
    val qualities: Set<Quality> = emptySet(),
    val codecs: Set<Codec> = emptySet(),
    val audio: Set<AudioLayout> = emptySet(),
    val hdr: Boolean? = null,
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val language: String? = null,
    val maxAgeDays: Long? = null,
    val hideLocked: Boolean = true,
    val hideUnplayable: Boolean = false,
) {
    val active: Boolean get() = this != ReleaseFilter()

    fun matches(r: NzbResult, info: ReleaseInfo, caps: DeviceCaps, now: Instant = Instant.now()): Boolean {
        if (hideLocked && r.passworded) return false
        if (qualities.isNotEmpty() && info.quality !in qualities) return false
        if (codecs.isNotEmpty() && info.codec !in codecs) return false
        if (audio.isNotEmpty() && info.audio !in audio) return false
        if (hdr != null && info.hdr != hdr) return false
        val size = r.sizeBytes
        if (minBytes != null && size != null && size < minBytes) return false
        if (maxBytes != null && size != null && size > maxBytes) return false
        if (language != null) {
            val english = info.languages.isEmpty()
            if (language == "English" && !english) return false
            if (language != "English" && language !in info.languages && "Multi" !in info.languages) return false
        }
        if (maxAgeDays != null) {
            val age = ReleaseParser.ageDays(r.pubDate, now)
            if (age != null && age > maxAgeDays) return false
        }
        if (hideUnplayable && info.playabilityOn(caps) == Playability.NO) return false
        return true
    }
}

/** What the user prefers when we pick for them. */
data class PlayPrefs(val quality: Quality = Quality.FHD, val maxBytes: Long? = null)

object Ranker {
    /**
     * Best-first: never failed > plays on this device > no inner-archive flag > closest to preferred quality (not above the size cap)
     * > most grabbed > smaller. [failed] holds release keys (guid or link) that failed to stream before.
     */
    fun rank(releases: List<NzbResult>, caps: DeviceCaps, prefs: PlayPrefs, failed: Set<String>): List<NzbResult> {
        val order = listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD)
        fun qualityDistance(q: Quality) =
            if (q == Quality.UNKNOWN) 2 else kotlin.math.abs(order.indexOf(q) - order.indexOf(prefs.quality))
        return releases.filterNot { it.passworded }.sortedWith(
            compareBy<NzbResult> { it.key in failed }
                .thenBy { ReleaseParser.parse(it).playabilityOn(caps).ordinal }
                .thenBy { it.innerArchive }
                .thenBy { prefs.maxBytes != null && (it.sizeBytes ?: 0) > prefs.maxBytes }
                .thenBy { qualityDistance(ReleaseParser.parse(it).quality) }
                .thenByDescending { it.grabs ?: 0 }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE },
        )
    }
}

/** Stable id for remembering a release (failures, history). */
val NzbResult.key: String get() = guid ?: nzbUrl

enum class TitleSort(val label: String) { BEST("Best match"), NEWEST("Newest"), POPULAR("Most grabbed"), RATING("Top rated") }

/** Title-level filter (search / browse). */
data class TitleFilter(
    val genres: Set<String> = emptySet(),
    val minYear: Int? = null,
    val maxYear: Int? = null,
    val minRating: Double? = null,
    val sort: TitleSort = TitleSort.BEST,
) {
    val active: Boolean get() = this != TitleFilter()

    fun apply(groups: List<TitleGroup>): List<TitleGroup> {
        val kept = groups.filter { g ->
            (genres.isEmpty() || g.genres.any { it in genres }) &&
                (minYear == null || (g.year ?: 0) >= minYear) &&
                (maxYear == null || (g.year ?: Int.MAX_VALUE) <= maxYear) &&
                (minRating == null || (g.score ?: 0.0) >= minRating)
        }
        return when (sort) {
            TitleSort.BEST -> kept
            TitleSort.NEWEST -> kept.sortedBy { g -> g.releases.minOf { ReleaseParser.ageDays(it.pubDate) ?: Long.MAX_VALUE } }
            TitleSort.POPULAR -> kept.sortedByDescending { g -> g.releases.sumOf { it.grabs ?: 0 } }
            TitleSort.RATING -> kept.sortedByDescending { it.score ?: 0.0 }
        }
    }
}
