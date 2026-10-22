package dev.usenetstream.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/** The page's blur source; glass bars inside it blur what scrolls behind them. Null = tint-only glass. */
val LocalHaze = compositionLocalOf<HazeState?> { null }

val Pill = RoundedCornerShape(50)

/**
 * Frosted glass: [fill] (translucent), a top-lit sheen, a bright hairline edge and a soft shadow.
 * With [blur] the content behind is blurred too (Android 12+; older phones keep the tint).
 */
@Composable
fun Modifier.glass(
    shape: Shape = Pill,
    fill: Color = LocalPalette.current.glass,
    edge: Color = LocalPalette.current.glassEdge,
    elevation: Dp = 6.dp,
    blur: HazeState? = null,
): Modifier {
    val p = LocalPalette.current
    val base = this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = if (p.isDark) 0.5f else 0.12f), spotColor = Color.Black.copy(alpha = if (p.isDark) 0.5f else 0.16f))
        .clip(shape)
    val blurred = if (blur != null) {
        base.hazeChild(blur, HazeStyle(backgroundColor = p.bg, tint = HazeTint(fill), blurRadius = 24.dp, noiseFactor = 0.04f))
    } else {
        base.background(fill)
    }
    return blurred
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (p.isDark) 0.10f else 0.35f), 0.55f to Color.Transparent))
        .border(1.dp, Brush.verticalGradient(listOf(edge, edge.copy(alpha = edge.alpha * 0.25f))), shape)
}

/**
 * Glass button. Primary = ink glass (inverted in dark mode), secondary = clear glass.
 * Content color follows, so icons/text inside need no color of their own.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    height: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    val haptic = rememberHaptic()
    val fill = if (primary) p.primary.copy(alpha = 0.94f) else p.glass
    val onFill = if (primary) p.onPrimary else p.fg
    Row(
        modifier
            .height(height)
            .glass(fill = fill, edge = if (primary) Color.White.copy(alpha = 0.45f) else p.glassEdge, blur = if (primary) null else LocalHaze.current)
            .pressable(pressedScale = 0.96f) { haptic(Haptic.CONFIRM); onClick() }
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides onFill) { content() }
    }
}

/** Selectable glass chip (replaces Material FilterChip). Keeps the selectable role for a11y and tests. */
@Composable
fun GlassChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val fill by animateColorAsState(if (selected) p.fg else p.glass, label = "chip-fill")
    val onFill by animateColorAsState(if (selected) p.bg else p.fg, label = "chip-text")
    Box(
        modifier
            .glass(fill = fill, elevation = if (selected) 4.dp else 1.dp)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides onFill) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

/** Small glass label used where a chip is display-only. */
@Composable
fun GlassTag(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.glass(elevation = 0.dp).padding(horizontal = 10.dp, vertical = 4.dp))
}
