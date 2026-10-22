package dev.usenetstream.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.usenetstream.MainViewModel
import dev.usenetstream.Route
import dev.usenetstream.engine.tv.GuideEpisode
import kotlinx.coroutines.delay

/** Everything about playback that phone and TV share; the screens only draw it and route input to it. */
@Stable
class PlayerCore(val player: ExoPlayer, val next: GuideEpisode?) {
    var error by mutableStateOf<String?>(null)
    var skips by mutableIntStateOf(0)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var state by mutableIntStateOf(Player.STATE_IDLE)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var speed by mutableFloatStateOf(1f)
    /** Seconds left before the next episode starts; -1 = not counting, -2 = cancelled. */
    var countdown by mutableIntStateOf(-1)

    internal var streak = 0
    internal var lastSkipTo = Long.MIN_VALUE

    fun playPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (state == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun seekBy(ms: Long) {
        val d = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, d))
        position = player.currentPosition
    }

    fun retry() {
        error = null
        player.prepare()
        player.play()
    }

    fun setPlaybackSpeed(s: Float) {
        speed = s
        player.setPlaybackSpeed(s)
    }
}

@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, route: Route.Player): ExoPlayer =
    ExoPlayer.Builder(context)
        // Platform decoders first; FFmpeg (AC3/E-AC3/DTS/TrueHD) only where the device has none (e.g. the Hisense TV).
        .setRenderersFactory(DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
        .setMediaSourceFactory(ProgressiveMediaSource.Factory { UsenetDataSource(route.media.source) })
        .setLoadControl(loadControl(context))
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build().apply {
            setMediaItem(MediaItem.fromUri("usenet://stream/" + Uri.encode(route.media.name)), route.session.startMs)
            prepare()
            playWhenReady = true
        }

/**
 * Buffer by device memory. ExoPlayer's default video buffer may grow to ~128 MB, which alone overflows a TV's
 * 192 MB heap (seen on the Hisense: OutOfMemoryError mid-stream). Phones keep a bigger, longer buffer.
 */
@OptIn(UnstableApi::class)
private fun loadControl(context: Context): DefaultLoadControl {
    val heapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
    val small = heapMb < 300
    val bytes = if (small) 40 * 1024 * 1024 else 96 * 1024 * 1024
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(if (small) 20_000 else 30_000, if (small) 60_000 else 120_000, 2_000, 4_000)
        .setTargetBufferBytes(bytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}

/** Creates the player for [route], keeps [PlayerCore] in sync, saves progress, and releases it on exit. */
@Composable
fun rememberPlayerCore(vm: MainViewModel, route: Route.Player): PlayerCore {
    val context = LocalContext.current
    val session = route.session
    val core = remember(route.media) { PlayerCore(buildPlayer(context, route), vm.nextEpisodeAfter(session)).also { it.position = session.startMs } }
    val player = core.player

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // A missing article plays as zeros, which the parser rejects. Jump past the hole instead of dying;
                // failing again just past the last skip means the hole is bigger, so jump further each time.
                val damaged = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                val at = player.currentPosition
                core.streak = if (at <= core.lastSkipTo + STREAK_WINDOW_MS) core.streak + 1 else 0
                if (damaged && core.streak < MAX_STREAK) {
                    core.skips++
                    core.lastSkipTo = at + minOf(SKIP_MS shl core.streak, MAX_SKIP_MS)
                    player.seekTo(core.lastSkipTo)
                    player.prepare()
                    player.play()
                    return
                }
                core.error = "${e.errorCodeName}: ${e.cause?.message ?: e.message}"
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                core.playing = isPlaying
            }

            override fun onPlaybackStateChanged(s: Int) {
                core.state = s
                if (s == Player.STATE_ENDED && core.next != null && core.countdown != -2) core.countdown = AUTOPLAY_S
            }

            override fun onTracksChanged(t: Tracks) {
                core.tracks = t
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            vm.saveProgress(session, player.currentPosition, player.duration)
            player.release()
        }
    }

    // Position ticker + periodic progress save.
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(250)
            core.position = player.currentPosition
            core.duration = player.duration.takeIf { it > 0 } ?: 0
            core.buffered = player.bufferedPosition
            if (++tick % (SAVE_EVERY_S * 4) == 0 && player.isPlaying) vm.saveProgress(session, core.position, player.duration)
        }
    }

    // Next-episode countdown after the end.
    LaunchedEffect(core.countdown) {
        if (core.countdown > 0) {
            delay(1000)
            core.countdown--
        } else if (core.countdown == 0) {
            core.countdown = -1
            vm.playNext(session)
        }
    }
    return core
}

private const val SKIP_MS = 3_000L
private const val MAX_SKIP_MS = 30_000L
private const val STREAK_WINDOW_MS = 10_000L
private const val MAX_STREAK = 6
private const val SAVE_EVERY_S = 5
const val AUTOPLAY_S = 5
const val NEXT_PROMPT_MS = 30_000L
