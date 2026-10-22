package dev.usenetstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import dev.usenetstream.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** True on TVs: skip effects that a weak GPU/CPU can't keep at 60 fps (shimmer, glow, fades). */
val LocalLowEndUi = staticCompositionLocalOf { false }

// Theme-aware color names the screens use everywhere (kept from the first design so call sites stay small).
val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
val Card2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
val Fg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fg
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val Red: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val Divider: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.divider

// Alata ships one weight. Every weight maps to it so Compose never fakes a smeared bold; hierarchy comes from size.
private fun alata(w: Int) = Font(R.font.alata, FontWeight(w))

/** Alata throughout. */
val Sans = FontFamily(alata(400), alata(500), alata(600), alata(700), alata(800), alata(900))

private val Type = Typography().let { t ->
    fun TextStyle.w(weight: Int, tracking: Double = 0.0) = copy(fontFamily = Sans, fontWeight = FontWeight(weight), letterSpacing = tracking.sp)
    Typography(
        // Alata is wide and geometric: titles get a little negative tracking, small labels a little air.
        displayLarge = t.displayLarge.w(900, -1.0), displayMedium = t.displayMedium.w(900, -0.8), displaySmall = t.displaySmall.w(800, -0.6),
        headlineLarge = t.headlineLarge.w(800, -0.5), headlineMedium = t.headlineMedium.w(800, -0.4), headlineSmall = t.headlineSmall.w(700, -0.3),
        titleLarge = t.titleLarge.w(700, -0.2), titleMedium = t.titleMedium.w(600), titleSmall = t.titleSmall.w(600),
        bodyLarge = t.bodyLarge.w(400), bodyMedium = t.bodyMedium.w(400), bodySmall = t.bodySmall.w(400, 0.1),
        labelLarge = t.labelLarge.w(600, 0.1), labelMedium = t.labelMedium.w(500, 0.2), labelSmall = t.labelSmall.w(500, 0.3),
    )
}

/** Picks light/dark, cross-fades every color when it changes, and feeds Material the same palette. */
@Composable
fun StreamTheme(mode: ThemeMode = ThemeMode.SYSTEM, forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = animatePalette(if (dark) DarkPalette else LightPalette)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.onAccent,
        background = p.bg, onBackground = p.fg,
        surface = p.bg, onSurface = p.fg,
        surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface2,
        secondaryContainer = p.surface2, onSecondaryContainer = p.fg,
        outline = p.divider, outlineVariant = p.divider,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = Type) {
            // Default text/icon color for everything that doesn't set its own (Material only does this inside a Surface).
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides p.fg, content = content)
        }
    }
}

@Composable
private fun animatePalette(target: Palette): Palette {
    val spec = tween<Color>(450)
    @Composable fun a(c: Color) = animateColorAsState(c, spec, label = "palette").value
    return target.copy(
        bg = a(target.bg), surface = a(target.surface), surface2 = a(target.surface2), fg = a(target.fg), muted = a(target.muted),
        accent = a(target.accent), onAccent = a(target.onAccent), primary = a(target.primary), onPrimary = a(target.onPrimary),
        divider = a(target.divider), glass = a(target.glass), glassEdge = a(target.glassEdge), bar = a(target.bar),
    )
}

/** "Grabbit" wordmark in the accent indigo. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        "Grabbit",
        color = LocalPalette.current.accent,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        modifier = modifier,
    )
}

/** Poster image, or the title on a dark gradient when the indexer has no cover. */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Card2, Card))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = Fg,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Card)) {
        if (url == null) {
            fallback()
        } else if (LocalLowEndUi.current) {
            // Weak TV chips: no per-card subcomposition, no shimmer animation, no fade, 16-bit decode.
            coil.compose.AsyncImage(
                model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).crossfade(false).allowRgb565(true).build() },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SubcomposeAsyncImage(
                model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).crossfade(220).build() },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                loading = { ShimmerBox(Modifier.fillMaxSize()) },
                error = { fallback() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Poster with an accent progress bar along the bottom. [sharedKey] lets it fly into the details page. */
@Composable
fun PosterCard(url: String?, title: String, progress: Float?, width: Dp, modifier: Modifier = Modifier, sharedKey: String? = null) {
    Column(modifier.width(width)) {
        Poster(url, title, Modifier.fillMaxWidth().height(width * 1.5f).let { if (sharedKey != null) it.sharedPoster(sharedKey) else it })
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                drawStopIndicator = {},
                gapSize = 0.dp,
                color = Red,
                trackColor = Card2,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "${bytes / 1_000_000} MB"
}

fun formatLeft(positionMs: Long, durationMs: Long): String {
    val left = ((durationMs - positionMs).coerceAtLeast(0) / 60_000).toInt()
    return when {
        left >= 60 -> "${left / 60}h ${left % 60}m left"
        left >= 1 -> "${left}m left"
        else -> "<1m left"
    }
}

fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatAge(days: Long?): String? = when {
    days == null -> null
    days >= 365 -> "${days / 365}y"
    days >= 1 -> "${days}d"
    else -> "new"
}
