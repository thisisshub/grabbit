package dev.usenetstream.player

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import dev.usenetstream.engine.newznab.DeviceCaps

/** Which formats this device can decode (platform codecs, plus the bundled FFmpeg audio decoders), for "will it play?". */
object Decoders {
    /** Set once from the app (needs a Context) before [caps] is first read. */
    @Volatile var screenHeight: Int = 2160

    @OptIn(UnstableApi::class)
    val caps: DeviceCaps by lazy {
        val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }
            .toSet()
        val ffmpeg = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        fun ff(mime: String) = ffmpeg && runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
        DeviceCaps(
            hevc = "video/hevc" in types,
            av1 = "video/av01" in types,
            dolbyVision = "video/dolby-vision" in types,
            hdr10 = "video/hevc" in types,
            dts = "audio/vnd.dts" in types || "audio/vnd.dts.hd" in types || ff(MimeTypes.AUDIO_DTS),
            trueHd = "audio/true-hd" in types || ff(MimeTypes.AUDIO_TRUEHD),
            maxScreenHeight = screenHeight,
        )
    }

    /** For the Settings "this device decodes" line. */
    @OptIn(UnstableApi::class)
    val softwareAudio: List<String> by lazy {
        if (runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false).not()) return@lazy emptyList()
        listOf(MimeTypes.AUDIO_AC3 to "Dolby Digital", MimeTypes.AUDIO_E_AC3 to "Dolby Digital+", MimeTypes.AUDIO_DTS to "DTS", MimeTypes.AUDIO_TRUEHD to "TrueHD")
            .filter { (m, _) -> runCatching { FfmpegLibrary.supportsFormat(m) }.getOrDefault(false) }
            .map { it.second }
    }
}
