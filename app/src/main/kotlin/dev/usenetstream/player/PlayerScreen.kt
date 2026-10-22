@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package dev.usenetstream.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.usenetstream.MainViewModel
import dev.usenetstream.PlaySession
import dev.usenetstream.Route
import dev.usenetstream.ui.Card
import dev.usenetstream.ui.Haptic
import dev.usenetstream.ui.Muted
import dev.usenetstream.ui.Red
import dev.usenetstream.ui.formatClock
import dev.usenetstream.ui.glass
import dev.usenetstream.ui.pressable
import dev.usenetstream.ui.rememberHaptic
import kotlinx.coroutines.delay

private enum class Sheet { TRACKS, SPEED, EPISODES }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, route: Route.Player, inPip: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as Activity
    val session = route.session
    val stats = route.streamer.stats
    val showStats by vm.showStats.collectAsState()
    val haptic = rememberHaptic()
    val core = rememberPlayerCore(vm, route)
    val player = core.player
    val next = core.next

    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var seekFlash by remember { mutableStateOf<Pair<Int, Long>?>(null) } // -1/+1, when
    var gesture by remember { mutableStateOf<String?>(null) }
    var fill by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    fun touch() {
        lastTouch = System.currentTimeMillis()
        controls = true
    }

    // Fullscreen landscape, screen on; everything restored on the way out.
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, view)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // The phone UI is portrait-only (set in MainActivity); only the player rotates.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    // Auto-hide the controls while playing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            if (controls && player.isPlaying && sheet == null && scrubbing == null && System.currentTimeMillis() - lastTouch > HIDE_AFTER_MS) {
                controls = false
            }
        }
    }
    if (core.error != null) controls = true

    val statsLine = rememberStatsLine(showStats, stats, core)
    val position = core.position
    val duration = core.duration
    val countdown = core.countdown

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = true } },
            update = { it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            modifier = Modifier.fillMaxSize(),
        )

        if (!inPip) {
            // Gesture layer: tap = show/hide, double-tap sides = ±10 s, drag left = brightness, right = volume.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { if (controls) controls = false else touch() },
                            onDoubleTap = { o ->
                                val dir = if (o.x < size.width / 2) -1 else 1
                                if (dir < 0) player.seekBack() else player.seekForward()
                                seekFlash = dir to System.currentTimeMillis()
                                haptic(Haptic.TAP)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        var left = true
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { o ->
                                left = o.x < size.width / 2
                                acc = 0f
                            },
                            onDragEnd = { gesture = null },
                            onDragCancel = { gesture = null },
                        ) { _, dy ->
                            acc -= dy / size.height
                            if (left) {
                                val w = activity.window
                                val cur = w.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                                val v = (cur - dy / size.height).coerceIn(0.02f, 1f)
                                w.attributes = w.attributes.apply { screenBrightness = v }
                                gesture = "Brightness ${(v * 100).toInt()}%"
                            } else if (kotlin.math.abs(acc) > 1f / maxVol) {
                                val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val v = (cur + if (acc > 0) 1 else -1).coerceIn(0, maxVol)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                acc = 0f
                                gesture = "Volume ${v * 100 / maxVol}%"
                            }
                        }
                    },
            )

            SeekFlash(seekFlash)

            gesture?.let {
                Text(
                    it, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            if (core.state == Player.STATE_BUFFERING && core.error == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Red, strokeWidth = 3.dp)
            }

            AnimatedVisibility(controls || core.error != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(250))) {
                Controls(
                    title = session.title,
                    subtitle = session.subtitle,
                    playing = core.playing,
                    position = scrubbing?.let { (it * duration).toLong() } ?: position,
                    duration = duration,
                    buffered = core.buffered,
                    speed = core.speed,
                    fill = fill,
                    hasEpisodes = session.showKey != null,
                    nextLabel = next?.let { "Next: ${it.code}" }?.takeIf { duration > 0 && duration - position < NEXT_PROMPT_MS },
                    onBack = { vm.back() },
                    onPlayPause = { touch(); haptic(Haptic.TAP); core.playPause() },
                    onRewind = { touch(); player.seekBack(); seekFlash = -1 to System.currentTimeMillis() },
                    onForward = { touch(); player.seekForward(); seekFlash = 1 to System.currentTimeMillis() },
                    onScrub = { f -> touch(); scrubbing = f },
                    onScrubEnd = { scrubbing?.let { player.seekTo((it * duration).toLong()) }; scrubbing = null },
                    onTracks = { touch(); sheet = Sheet.TRACKS },
                    onSpeed = { touch(); sheet = Sheet.SPEED },
                    onEpisodes = { touch(); sheet = Sheet.EPISODES },
                    onFill = { touch(); fill = !fill },
                    onNext = { vm.playNext(session) },
                    onStats = { vm.setShowStats(!showStats) },
                )
            }

            if (showStats) {
                Text(
                    statsLine, color = Color(0xFFB3B3B3), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 56.dp, end = 12.dp).background(Color(0x88000000)).padding(6.dp),
                )
            }

            core.error?.let {
                Column(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xE61A1953)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Playback stopped", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(it, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(320.dp).padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill("Retry") { core.retry() }
                        Pill("Back") { vm.back() }
                    }
                }
            }

            if (countdown >= 0 && next != null) {
                Column(
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE61A1953)).padding(16.dp),
                ) {
                    Text("Up next", color = Muted, fontSize = 12.sp)
                    Text("${next.code} · ${next.name}", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Cancel") { core.countdown = -2 }
                        Pill("Play in $countdown", primary = true) { core.countdown = -1; vm.playNext(session) }
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.TRACKS -> TracksSheet(core.tracks, player) { sheet = null }
        Sheet.SPEED -> SpeedSheet(core.speed, { core.setPlaybackSpeed(it) }) { sheet = null }
        Sheet.EPISODES -> EpisodesSheet(vm, session) { sheet = null }
        null -> Unit
    }
}

/** "1.4 MB/s · got 120 MB · ..." refreshed every second while stats are on. */
@Composable
fun rememberStatsLine(show: Boolean, stats: dev.usenetstream.engine.stream.StreamStats, core: PlayerCore): String {
    var line by remember { mutableStateOf("") }
    LaunchedEffect(show) {
        var last = stats.bytesDownloaded.get()
        while (show) {
            delay(1000)
            val now = stats.bytesDownloaded.get()
            line = "%.1f MB/s · got %d MB · missing %d · damaged %d · skipped %d".format(
                (now - last) / 1e6, now / 1_000_000, stats.missingArticles.get(), stats.crcErrors.get(), core.skips,
            )
            last = now
        }
    }
    return line
}

@Composable
private fun Controls(
    title: String,
    subtitle: String?,
    playing: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    speed: Float,
    fill: Boolean,
    hasEpisodes: Boolean,
    nextLabel: String?,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onTracks: () -> Unit,
    onSpeed: () -> Unit,
    onEpisodes: () -> Unit,
    onFill: () -> Unit,
    onNext: () -> Unit,
    onStats: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0xAA000000), 0.25f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color(0xCC000000)),
        ),
    ) {
        Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        IconButton(onStats, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.Filled.Info, "Stats", tint = Color(0x99FFFFFF))
        }

        // Center cluster: play/pause in the middle, ±10 inside the thumb's arc on either side.
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton("−10", 56.dp, onRewind)
            Box(
                Modifier.size(80.dp).glass(shape = CircleShape, fill = Color.White.copy(alpha = 0.16f), edge = Color.White.copy(alpha = 0.45f)).pressable(pressedScale = 0.9f, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) PauseGlyph() else Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(56.dp))
            }
            RoundButton("+10", 56.dp, onForward)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    // Buffered range behind the scrubber.
                    if (duration > 0) {
                        Box(
                            Modifier.padding(horizontal = 10.dp).fillMaxWidth((buffered.toFloat() / duration).coerceIn(0f, 1f)).height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF)),
                        )
                    }
                    Slider(
                        value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = onScrub,
                        onValueChangeFinished = onScrubEnd,
                        colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Color(0x33FFFFFF)),
                    )
                }
                Text("${formatClock(position)} / ${formatClock(duration)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Audio & Subtitles", icon = { Icon(Icons.Filled.Menu, null, tint = Color.White, modifier = Modifier.size(18.dp)) }, onClick = onTracks)
                Pill(if (speed == 1f) "Speed" else "${speed}×", onClick = onSpeed)
                if (hasEpisodes) Pill("Episodes", icon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = Color.White, modifier = Modifier.size(18.dp)) }, onClick = onEpisodes)
                Pill(if (fill) "Fit" else "Fill", onClick = onFill)
                Spacer(Modifier.weight(1f))
                nextLabel?.let { Pill(it, primary = true, onClick = onNext) }
            }
        }
    }
}

@Composable
private fun PauseGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { Box(Modifier.width(9.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) }
    }
}

@Composable
private fun RoundButton(label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).glass(shape = CircleShape, fill = Color.White.copy(alpha = 0.12f), edge = Color.White.copy(alpha = 0.45f))
            .pressable(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .glass(fill = if (primary) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.14f), edge = Color.White.copy(alpha = 0.4f), elevation = 2.dp)
            .pressable(pressedScale = 0.92f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            it()
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (primary) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** "« 10s" / "10s »" flash on the side that was double-tapped. */
@Composable
private fun SeekFlash(flash: Pair<Int, Long>?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash == null) return@LaunchedEffect
        visible = true
        delay(550)
        visible = false
    }
    val dir = flash?.first ?: 1
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible,
            Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.35f),
            enter = fadeIn(tween(80)) + scaleIn(tween(200), initialScale = 0.8f),
            exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.1f),
        ) {
            val shape = if (dir < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
            Box(Modifier.fillMaxSize().clip(shape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                Text(if (dir < 0) "« 10s" else "10s »", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------- sheets

@OptIn(UnstableApi::class)
@Composable
private fun TracksSheet(tracks: Tracks, player: ExoPlayer, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            for ((type, title) in listOf(C.TRACK_TYPE_AUDIO to "Audio", C.TRACK_TYPE_TEXT to "Subtitles")) {
                val groups = tracks.groups.filter { it.type == type }
                item { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }
                if (type == C.TRACK_TYPE_TEXT) {
                    val off = groups.none { it.isSelected }
                    item {
                        TrackRow("Off", off) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            onDismiss()
                        }
                    }
                }
                if (groups.isEmpty()) item { Text("None in this file", color = Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                groups.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        val label = listOfNotNull(
                            f.label,
                            f.language?.uppercase(),
                            f.sampleMimeType?.substringAfter('/')?.uppercase(),
                            f.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                        ).joinToString(" · ").ifEmpty { "Track ${i + 1}" }
                        item {
                            TrackRow(label, g.isTrackSelected(i), enabled = g.isTrackSupported(i)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(type, false)
                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                    .build()
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { if (enabled) onClick() }.padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = Red) }
        Text(if (enabled) label else "$label (unsupported here)", color = if (enabled) Color.White else Muted, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SpeedSheet(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        Text("Playback speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                TrackRow(if (s == 1f) "Normal" else "${s}×", s == current) { onPick(s); onDismiss() }
            }
        }
    }
}

@Composable
private fun EpisodesSheet(vm: MainViewModel, session: PlaySession, onDismiss: () -> Unit) {
    val show by vm.show.collectAsState()
    val eps = show?.guide?.seasons?.get(session.season ?: 1).orEmpty()
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        Text("Season ${session.season}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            items(eps, key = { it.code }) { e ->
                val p = show?.progress?.get(e.code)
                TrackRow("${e.number}. ${e.name}" + if (p?.finished == true) "  ✓" else "", selected = e.number == session.episode) {
                    onDismiss()
                    vm.playEpisode(e)
                }
            }
        }
    }
}

private const val HIDE_AFTER_MS = 3_500L
