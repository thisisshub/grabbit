package dev.usenetstream.player

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.usenetstream.MainViewModel
import dev.usenetstream.Route
import dev.usenetstream.engine.tv.GuideEpisode
import dev.usenetstream.ui.DarkPalette
import dev.usenetstream.ui.formatClock
import dev.usenetstream.ui.tv.TvButton
import dev.usenetstream.ui.tv.TvFocusable
import dev.usenetstream.ui.tv.TvSafeH
import dev.usenetstream.ui.tv.TvSafeV
import kotlinx.coroutines.delay

private val P = DarkPalette

/** What a remote key does in the TV player; pure so it can be unit-tested. */
enum class TvAction { PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, SHOW_TRACKS, SHOW_EPISODES, SHOW_CONTROLS, NONE }

object TvKeys {
    /** [repeat] = how many auto-repeats the key has fired (holding speeds seeking up). */
    fun seekStepMs(repeat: Int): Long = when {
        repeat < 4 -> 10_000L
        repeat < 12 -> 30_000L
        else -> 60_000L
    }

    /** [barFocused]: focus is on the control bar's buttons (then D-pad moves between them). */
    fun action(keyCode: Int, barFocused: Boolean, panelOpen: Boolean): TvAction = when {
        panelOpen -> TvAction.NONE // the open panel handles its own D-pad
        keyCode == AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == AndroidKey.KEYCODE_MEDIA_PLAY ||
            keyCode == AndroidKey.KEYCODE_MEDIA_PAUSE -> TvAction.PLAY_PAUSE
        keyCode == AndroidKey.KEYCODE_MEDIA_REWIND -> TvAction.SEEK_BACK
        keyCode == AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> TvAction.SEEK_FORWARD
        // While the control bar has focus, D-pad and OK belong to its buttons.
        barFocused -> TvAction.NONE
        keyCode == AndroidKey.KEYCODE_DPAD_CENTER || keyCode == AndroidKey.KEYCODE_ENTER -> TvAction.PLAY_PAUSE
        keyCode == AndroidKey.KEYCODE_DPAD_LEFT -> TvAction.SEEK_BACK
        keyCode == AndroidKey.KEYCODE_DPAD_RIGHT -> TvAction.SEEK_FORWARD
        keyCode == AndroidKey.KEYCODE_DPAD_UP -> TvAction.SHOW_TRACKS
        keyCode == AndroidKey.KEYCODE_DPAD_DOWN -> TvAction.SHOW_CONTROLS
        keyCode == AndroidKey.KEYCODE_MENU -> TvAction.SHOW_EPISODES
        else -> TvAction.NONE
    }
}

private enum class Panel { TRACKS, EPISODES }

@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(vm: MainViewModel, route: Route.Player) {
    val view = LocalView.current
    val core = rememberPlayerCore(vm, route)
    val player = core.player
    val session = route.session
    val showStats by vm.showStats.collectAsState()
    val statsLine = rememberStatsLine(showStats, route.streamer.stats, core)

    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var barFocused by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    // Held/rapid seeks add up here and land as ONE seek when the keys stop (a seek storm can exhaust a TV's memory).
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    fun queueSeek(deltaMs: Long) {
        val d = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = ((pendingSeek ?: player.currentPosition) + deltaMs).coerceIn(0, d)
        pendingSeek = target
        val diff = target - player.currentPosition
        seekHint = (if (diff >= 0) "» " else "« ") + formatClock(target)
    }
    LaunchedEffect(pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(500)
        player.seekTo(target)
        core.position = target
        pendingSeek = null
    }
    val surface = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { surface.requestFocus() } }
    // Auto-hide the control bar; focus returns to the video so arrows seek again.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            if (controls && panel == null && player.isPlaying && System.currentTimeMillis() - lastInput > 4_000) {
                controls = false
                runCatching { surface.requestFocus() }
            }
        }
    }
    LaunchedEffect(seekHint, pendingSeek) { if (seekHint != null && pendingSeek == null) { delay(700); seekHint = null } }

    BackHandler {
        when {
            panel != null -> { panel = null; runCatching { surface.requestFocus() } }
            // Visible controls always go first (paused or not); a second Back leaves the player.
            controls && core.error == null -> { controls = false; runCatching { surface.requestFocus() } }
            else -> vm.back()
        }
    }

    fun showControls() {
        controls = true
        lastInput = System.currentTimeMillis()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                val native = e.nativeKeyEvent
                when (TvKeys.action(e.key.nativeKeyCode, barFocused, panel != null)) {
                    TvAction.PLAY_PAUSE -> { core.playPause(); showControls(); true }
                    TvAction.SEEK_BACK -> { queueSeek(-TvKeys.seekStepMs(native.repeatCount)); true }
                    TvAction.SEEK_FORWARD -> { queueSeek(TvKeys.seekStepMs(native.repeatCount)); true }
                    TvAction.SHOW_TRACKS -> { panel = Panel.TRACKS; true }
                    TvAction.SHOW_EPISODES -> { if (session.showKey != null) panel = Panel.EPISODES; true }
                    TvAction.SHOW_CONTROLS -> {
                        showControls()
                        runCatching { playButton.requestFocus() }
                        true
                    }
                    TvAction.NONE -> false
                }
            },
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxSize().focusRequester(surface).focusable(),
        )

        if (core.state == Player.STATE_BUFFERING && core.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = P.primary, strokeWidth = 4.dp)
        }
        seekHint?.let {
            Text(it, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 14.dp))
        }

        AnimatedVisibility(controls || core.error != null, Modifier.fillMaxSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xB0000000), 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xD0000000)))) {
                Column(Modifier.align(Alignment.TopStart).padding(start = TvSafeH, top = TvSafeV)) {
                    Text(session.title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    session.subtitle?.let { Text(it, fontSize = 18.sp, color = P.muted) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = TvSafeH, vertical = TvSafeV)) {
                    val d = core.duration
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(6.dp).background(Color(0x40FFFFFF), RoundedCornerShape(3.dp))) {
                            if (d > 0) {
                                Box(Modifier.fillMaxWidth((core.buffered.toFloat() / d).coerceIn(0f, 1f)).height(6.dp).background(Color(0x60FFFFFF), RoundedCornerShape(3.dp)))
                                Box(Modifier.fillMaxWidth((core.position.toFloat() / d).coerceIn(0f, 1f)).height(6.dp).background(P.primary, RoundedCornerShape(3.dp)))
                            }
                        }
                        Text("${formatClock(core.position)} / ${formatClock(d)}", fontSize = 16.sp, color = Color.White, modifier = Modifier.padding(start = 16.dp))
                    }
                    Row(Modifier.padding(top = 16.dp).onFocusChanged { barFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvButton(if (core.playing) "Pause" else "Play", { core.playPause(); showControls() }, Modifier.focusRequester(playButton), primary = true)
                        TvButton("−10s", { core.seekBy(-10_000); showControls() })
                        TvButton("+10s", { core.seekBy(10_000); showControls() })
                        TvButton("Audio & Subtitles", { panel = Panel.TRACKS })
                        if (session.showKey != null) TvButton("Episodes", { panel = Panel.EPISODES })
                        core.next?.takeIf { d > 0 && d - core.position < NEXT_PROMPT_MS }?.let { n -> TvButton("Next: ${n.code}", { vm.playNext(session) }, primary = true) }
                    }
                    Text("◀ ▶ seek  ·  OK play/pause  ·  ▲ audio & subtitles  ·  Back hide", fontSize = 13.sp, color = P.muted, modifier = Modifier.padding(top = 12.dp))
                }
                if (showStats) Text(statsLine, fontSize = 12.sp, color = P.muted, modifier = Modifier.align(Alignment.TopEnd).padding(end = TvSafeH, top = TvSafeV))
            }
        }

        core.error?.let { err ->
            Column(Modifier.align(Alignment.Center).background(Color(0xF01A1953), RoundedCornerShape(16.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Playback stopped", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(err, color = P.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(520.dp).padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Retry", { core.retry() }, primary = true)
                    TvButton("Back", { vm.back() })
                }
            }
        }

        if (core.countdown >= 0 && core.next != null) UpNextCard(core.next, core.countdown, onPlay = { core.countdown = -1; vm.playNext(session) }, onCancel = { core.countdown = -2 })

        when (panel) {
            Panel.TRACKS -> TvTracksPanel(core) { panel = null; runCatching { surface.requestFocus() } }
            Panel.EPISODES -> TvEpisodesPanel(vm, session) { panel = null; runCatching { surface.requestFocus() } }
            null -> Unit
        }
    }
}

@Composable
private fun UpNextCard(next: GuideEpisode, seconds: Int, onPlay: () -> Unit, onCancel: () -> Unit) {
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { play.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomEnd).padding(end = TvSafeH, bottom = TvSafeV + 120.dp).background(Color(0xF01A1953), RoundedCornerShape(16.dp)).padding(22.dp)) {
            Text("Up next", color = P.muted, fontSize = 15.sp)
            Text("${next.code} · ${next.name}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(420.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Play now ($seconds)", onPlay, Modifier.focusRequester(play), primary = true)
                TvButton("Cancel", onCancel)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TvTracksPanel(core: PlayerCore, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    val player = core.player
    SidePanel("Audio & Subtitles") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var firstUsed = false
            fun mod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            for ((type, title) in listOf(C.TRACK_TYPE_AUDIO to "Audio", C.TRACK_TYPE_TEXT to "Subtitles")) {
                val groups = core.tracks.groups.filter { it.type == type }
                item { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                if (type == C.TRACK_TYPE_TEXT) {
                    val m = mod()
                    item {
                        PanelRow("Off", groups.none { it.isSelected }, m) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); onClose()
                        }
                    }
                }
                groups.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        val label = listOfNotNull(f.label, f.language?.uppercase(), f.sampleMimeType?.substringAfter('/')?.uppercase(), f.channelCount.takeIf { it > 0 }?.let { "${it}ch" })
                            .joinToString(" · ").ifEmpty { "Track ${i + 1}" }
                        val m = mod()
                        item {
                            PanelRow(if (g.isTrackSupported(i)) label else "$label (unsupported)", g.isTrackSelected(i), m) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                                onClose()
                            }
                        }
                    }
                }
            }
            item {
                Text("Speed", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            }
            items(listOf(0.75f, 1f, 1.25f, 1.5f)) { s -> PanelRow(if (s == 1f) "Normal" else "${s}×", core.speed == s) { core.setPlaybackSpeed(s); onClose() } }
        }
    }
}

@Composable
private fun TvEpisodesPanel(vm: MainViewModel, session: dev.usenetstream.PlaySession, onClose: () -> Unit) {
    val show by vm.show.collectAsState()
    val eps = show?.guide?.seasons?.get(session.season ?: 1).orEmpty()
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { current.requestFocus() } }
    SidePanel("Season ${session.season}") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(eps, key = { it.code }) { e ->
                val done = show?.progress?.get(e.code)?.finished == true
                PanelRow("${e.number}. ${e.name}${if (done) "  ✓" else ""}", e.number == session.episode, if (e.number == session.episode) Modifier.focusRequester(current) else Modifier) {
                    onClose(); vm.playEpisode(e)
                }
            }
        }
    }
}

@Composable
private fun SidePanel(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(440.dp).fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xF0080616), Color(0xFA080616))))
                .padding(start = 60.dp, end = TvSafeH, top = TvSafeV, bottom = TvSafeV),
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

@Composable
private fun PanelRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvFocusable(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.03f, container = if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent, focusedContainer = Color.White, focusedContent = Color.Black) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "✓" else "", fontSize = 16.sp, modifier = Modifier.width(24.dp), color = androidx.compose.material3.LocalContentColor.current)
            Text(label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = androidx.compose.material3.LocalContentColor.current)
        }
    }
}
