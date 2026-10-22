@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.usenetstream

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.usenetstream.player.PlayerScreen
import dev.usenetstream.ui.BottomNav
import dev.usenetstream.ui.HomeScreen
import dev.usenetstream.ui.Ink
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.usenetstream.ui.LocalAnimScope
import dev.usenetstream.ui.LocalHaze
import dev.usenetstream.ui.LocalSharedScope
import dev.usenetstream.ui.LookupScreen
import dev.usenetstream.ui.MovieScreen
import dev.usenetstream.ui.MyListScreen
import dev.usenetstream.ui.OpeningScreen
import dev.usenetstream.ui.SearchScreen
import dev.usenetstream.ui.SettingsTab
import dev.usenetstream.ui.ShowScreen
import dev.usenetstream.ui.StreamTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    val isTv: Boolean by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }

    /** Compose reads this to hide player chrome in picture-in-picture. */
    val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The screen's best mode (a 1080p TV can't show 4K; don't pick 4K releases for it).
        @Suppress("DEPRECATION")
        dev.usenetstream.player.Decoders.screenHeight = windowManager.defaultDisplay.supportedModes
            .maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 2160
        // TVs stay landscape; phones are portrait except inside the player.
        if (!isTv) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (savedInstanceState == null) handleViewIntent(intent)
        if (isTv) {
            vm.tvLink = vm.tvLink ?: dev.usenetstream.link.TvLink(applicationContext, vm)
            setContent {
                dev.usenetstream.ui.tv.TvApp(vm, onExit = ::finish, setupPanel = { dev.usenetstream.link.TvLinkPanel(vm) })
            }
            return
        }
        setContent {
            val mode by vm.themeMode.collectAsState()
            StreamTheme(mode) {
                // Status/nav bar icons follow the theme (dark icons on the warm-white theme).
                val dark = dev.usenetstream.ui.LocalPalette.current.isDark
                androidx.compose.runtime.LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                val accepted by vm.disclaimerOk.collectAsState()
                if (!accepted) dev.usenetstream.ui.DisclaimerScreen(vm::acceptDisclaimer, ::finish)
                else App(vm, ::openNzb, inPip.value) { if (!vm.back()) finish() }
            }
        }
    }

    // The phone link runs while the TV app is on screen.
    override fun onStart() {
        super.onStart()
        vm.tvLink?.start()
        // New release on GitHub? (throttled; back from "allow installs" settings, carry on installing)
        dev.usenetstream.update.Updater.get(this).run { checkInBackground(); resume() }
    }

    override fun onStop() {
        vm.tvLink?.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** Swiping home while a video plays shrinks it to picture-in-picture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.stack.value.lastOrNull() is Route.Player) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(::openNzb)
    }

    private fun openNzb(uri: Uri) {
        // Keep read access so "Continue Watching" can reopen this file later.
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        vm.openFile(uri, displayName(uri))
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "NZB"
}

/** Stable identity per screen so AnimatedContent animates between screens, not on every state update. */
private fun screenKey(s: Any): String = when (s) {
    is Route.Movie -> "movie:${s.group.key}"
    is Route.Show -> "show:${s.key}"
    is Route.Lookup -> "lookup:${s.key}"
    is Route.Opening -> "opening"
    is Route.Player -> "player"
    is Route.Service -> "service:${s.id}"
    is Route.OttTitle -> "ott:${s.item.serviceId}:${s.item.title.id}"
    else -> "tabs"
}

@Composable
private fun App(vm: MainViewModel, openNzb: (Uri) -> Unit, inPip: Boolean, onExit: () -> Unit) {
    val tab by vm.tab.collectAsState()
    val stack by vm.stack.collectAsState()
    val notice by vm.notice.collectAsState()
    val top = stack.lastOrNull()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(openNzb) }

    // Predictive back on pushed pages: the page shrinks with the gesture, then pops.
    val backScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val pushedPage = top is Route.Movie || top is Route.Show || top is Route.Lookup || top is Route.Service || top is Route.OttTitle
    PredictiveBackHandler(enabled = pushedPage) { progress ->
        try {
            progress.collect { e -> backScale.snapTo(1f - 0.1f * e.progress) }
            vm.back()
            scope.launch { backScale.snapTo(1f) }
        } catch (e: CancellationException) {
            scope.launch { backScale.animateTo(1f) }
            throw e
        }
    }
    BackHandler(enabled = !pushedPage, onBack = onExit)

    Box(Modifier.fillMaxSize().background(Ink)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
                AnimatedContent(
                    targetState = top ?: tab,
                    contentKey = ::screenKey,
                    transitionSpec = {
                        val into = targetState
                        when {
                            into is Route.Player || initialState is Route.Player ->
                                fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                            into is Route.Opening ->
                                (fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 1.04f)) togetherWith fadeOut(tween(200))
                            into is Tab ->
                                (fadeIn(tween(250)) + slideInHorizontally(tween(300)) { -it / 10 }) togetherWith
                                    (fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { it / 6 } + scaleOut(tween(300), targetScale = 0.96f))
                            else ->
                                (fadeIn(tween(300)) + slideInHorizontally(tween(320)) { it / 6 }) togetherWith
                                    (fadeOut(tween(220)) + slideOutHorizontally(tween(320)) { -it / 10 })
                        }
                    },
                    label = "screens",
                ) { screen ->
                    CompositionLocalProvider(LocalAnimScope provides this) {
                        val pageModifier = Modifier.graphicsLayer {
                            scaleX = backScale.value
                            scaleY = backScale.value
                        }
                        when (screen) {
                            is Tab -> Tabs(vm, screen) { picker.launch(arrayOf("*/*")) }
                            is Route.Movie -> Box(pageModifier) { MovieScreen(vm, screen.group) }
                            is Route.Show -> Box(pageModifier) { ShowScreen(vm, screen) }
                            is Route.Lookup -> Box(pageModifier) { LookupScreen(screen) }
                            is Route.Service -> Box(pageModifier) { dev.usenetstream.ui.ServiceScreen(vm, screen.id) }
                            is Route.OttTitle -> Box(pageModifier) { dev.usenetstream.ui.OttTitleScreen(vm, screen.item) }
                            // Video screens are always dark, whatever the app theme.
                            is Route.Opening -> StreamTheme(forceDark = true) { OpeningScreen(screen) { vm.back() } }
                            is Route.Player -> StreamTheme(forceDark = true) { PlayerScreen(vm, screen, inPip) }
                        }
                    }
                }
            }
        }

        // Snackbar above the bottom nav, inside thumb reach, auto-hides.
        notice?.let { msg ->
            LaunchedEffect(msg) {
                delay(2600)
                vm.dismissNotice()
            }
            Snackbar(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                containerColor = dev.usenetstream.ui.LocalPalette.current.fg,
                contentColor = dev.usenetstream.ui.LocalPalette.current.bg,
                shape = dev.usenetstream.ui.Pill,
            ) { Text(msg) }
        }
    }
}

@Composable
private fun Tabs(vm: MainViewModel, tab: Tab, openFile: () -> Unit) {
    // Tab content scrolls under a floating frosted nav bar.
    val navHaze = remember { HazeState() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().haze(navHaze)) {
            Crossfade(tab, animationSpec = tween(220), label = "tab") { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(vm)
                    Tab.SEARCH -> SearchScreen(vm)
                    Tab.MY_LIST -> MyListScreen(vm)
                    Tab.SETTINGS -> SettingsTab(vm, openFile)
                }
            }
        }
        CompositionLocalProvider(LocalHaze provides navHaze) {
            BottomNav(tab, vm::selectTab, Modifier.align(Alignment.BottomCenter))
        }
    }
}
