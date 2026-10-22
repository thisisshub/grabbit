package dev.usenetstream.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import dev.usenetstream.engine.stream.RandomAccessSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class SubtitleSize(val label: String, val scale: Float) { SMALL("S", 0.8f), MEDIUM("M", 1f), LARGE("L", 1.3f), HUGE("XL", 1.7f) }

enum class SubtitleBackground(val label: String) { SHADOW("Shadow"), BOX("Box") }

/** How subtitles look, shared by the phone and TV players; remembered across sessions. */
object SubtitleStyle {
    private lateinit var prefs: android.content.SharedPreferences
    private val _size = MutableStateFlow(SubtitleSize.MEDIUM)
    val size: StateFlow<SubtitleSize> = _size
    private val _background = MutableStateFlow(SubtitleBackground.SHADOW)
    val background: StateFlow<SubtitleBackground> = _background

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("subtitles", Context.MODE_PRIVATE)
        _size.value = runCatching { SubtitleSize.valueOf(prefs.getString("size", null)!!) }.getOrDefault(SubtitleSize.MEDIUM)
        _background.value = runCatching { SubtitleBackground.valueOf(prefs.getString("bg", null)!!) }.getOrDefault(SubtitleBackground.SHADOW)
    }

    fun setSize(s: SubtitleSize) {
        _size.value = s
        prefs.edit().putString("size", s.name).apply()
    }

    fun setBackground(b: SubtitleBackground) {
        _background.value = b
        prefs.edit().putString("bg", b.name).apply()
    }

    /** Applies the current style; embedded sizes/colours are ignored so the user's choice always wins. */
    @OptIn(UnstableApi::class)
    fun apply(view: PlayerView, tv: Boolean) {
        val sv = view.subtitleView ?: return
        sv.setApplyEmbeddedFontSizes(false)
        sv.setApplyEmbeddedStyles(false)
        // TVs are read from the sofa: start bigger than a phone at the same setting.
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * _size.value.scale * if (tv) 1.15f else 1f)
        sv.setBottomPaddingFraction(if (tv) 0.09f else 0.08f)
        sv.setStyle(
            when (_background.value) {
                SubtitleBackground.SHADOW -> CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, Typeface.DEFAULT_BOLD)
                SubtitleBackground.BOX -> CaptionStyleCompat(Color.WHITE, 0xB3000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.DEFAULT_BOLD)
            },
        )
    }
}

/** Results of an OpenSubtitles search for what's playing. */
sealed interface OnlineSubs {
    data object Idle : OnlineSubs
    data object Searching : OnlineSubs
    data class Found(val hits: List<dev.usenetstream.engine.subs.SubtitleHit>, val bestFor: Long?) : OnlineSubs
    data class Loading(val fileId: Long) : OnlineSubs
    data class Failed(val message: String) : OnlineSubs
}

/** Language codes to search, from the phone/TV's locale, always with English as a fallback. */
fun subtitleLanguages(): List<String> {
    val l = java.util.Locale.getDefault().language.lowercase().takeIf { it.length == 2 } ?: "en"
    return listOf(l, "en").distinct()
}

/** "en" -> "English" for the result list. */
fun languageName(code: String): String = java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { code.uppercase() }

/**
 * Adds a downloaded subtitle file to what's playing and switches to it. ExoPlayer needs the media item rebuilt,
 * so playback re-prepares from the current position (a second or two on Usenet).
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.addSubtitle(file: File, language: String) {
    val current = currentMediaItem ?: return
    val at = currentPosition
    val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
        .setMimeType(if (file.name.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
        .setLanguage(language)
        .setLabel("${languageName(language)} (OpenSubtitles)")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
    val existing = current.localConfiguration?.subtitleConfigurations.orEmpty()
    setMediaItem(current.buildUpon().setSubtitleConfigurations(existing + sub).build(), at)
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setPreferredTextLanguage(language)
        .build()
    prepare()
    play()
}

/** usenet:// goes to the engine stream; anything else (downloaded subtitle files) to the normal file reader. */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val usenet: UsenetDataSource, private val other: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        usenet.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == "usenet") usenet else other
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }

    companion object {
        fun factory(context: Context, source: RandomAccessSource) =
            DataSource.Factory { RoutingDataSource(UsenetDataSource(source), DefaultDataSource.Factory(context).createDataSource()) }
    }
}
