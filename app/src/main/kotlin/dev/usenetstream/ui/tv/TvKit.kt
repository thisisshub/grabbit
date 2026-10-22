package dev.usenetstream.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import dev.usenetstream.ui.DarkPalette
import dev.usenetstream.ui.Poster
import dev.usenetstream.ui.StreamTheme

/** TV safe area: ~5% overscan margin on a 960x540dp screen. */
val TvSafeH = 48.dp
val TvSafeV = 27.dp

private val P = DarkPalette
val FocusRing = BorderStroke(3.dp, Color.White)

/** Dark only on TV (10-foot UI): our palette for our components, the same colors for tv-material's. */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    StreamTheme(forceDark = true) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = P.primary, onPrimary = P.onPrimary,
                surface = P.surface, onSurface = P.fg, surfaceVariant = P.surface2, onSurfaceVariant = P.muted,
                background = P.bg, onBackground = P.fg, inverseSurface = P.fg, inverseOnSurface = P.bg,
                border = Color.White, borderVariant = P.divider,
            ),
        ) {
            // Big TV titles set only fontSize; let line height follow it (the inherited body line height overlaps wrapped lines).
            CompositionLocalProvider(
                dev.usenetstream.ui.LocalLowEndUi provides true,
                LocalContentColor provides P.fg,
                androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                content = content,
            )
        }
    }
}

/**
 * The one focusable surface everything on TV is built from: grows a little, gets a white ring and a soft
 * glow when focused, so the remote's position is always obvious from the sofa.
 */
@Composable
fun TvFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    container: Color = P.surface,
    focusedContainer: Color = P.surface2,
    content: Color = P.fg,
    focusedContent: Color = P.fg,
    focusedScale: Float = 1.06f,
    interaction: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    body: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container, contentColor = content,
            focusedContainerColor = focusedContainer, focusedContentColor = focusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(FocusRing, shape = shape)),
        // No glow: its shadow is costly on TV GPUs; the white ring + scale already make focus obvious.
        glow = ClickableSurfaceDefaults.glow(),
        interactionSource = interaction,
    ) {
        // tv-material sets its own content color; hand it to the Material 3 Text/Icon we use inside.
        CompositionLocalProvider(LocalContentColor provides androidx.tv.material3.LocalContentColor.current) { body() }
    }
}

/** Red CTA (Play) or a grey utility button. */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(26.dp),
        container = if (primary) P.primary else Color.White.copy(alpha = 0.12f),
        focusedContainer = if (primary) Color(0xFF4B4BFF) else Color.White,
        content = if (primary) P.onPrimary else P.fg,
        focusedContent = if (primary) P.onPrimary else Color.Black,
        focusedScale = 1.08f,
    ) {
        Row(Modifier.padding(horizontal = 26.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            leading?.let {
                it()
                Spacer(Modifier.width(10.dp))
            }
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LocalContentColor.current)
        }
    }
}

/** Selectable chip (seasons, filters). */
@Composable
fun TvChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        container = if (selected) P.fg else Color.White.copy(alpha = 0.10f),
        focusedContainer = Color.White,
        content = if (selected) P.bg else P.fg,
        focusedContent = Color.Black,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current, modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp))
    }
}

/** Poster card for rows and grids; title + caption appear under it while focused. */
@Composable
fun TvPoster(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    progress: Float? = null,
    caption: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.width(width)) {
        TvFocusable(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.width(width).height(width * 1.5f),
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.1f,
            interaction = interaction,
        ) {
            Poster(coverUrl, title, Modifier.fillMaxSize())
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(P.accent))
            }
        }
        // Reserve the space so rows don't jump when focus moves.
        Column(Modifier.height(44.dp).padding(top = 10.dp)) {
            if (focused) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                caption?.let { Text(it, fontSize = 12.sp, color = P.muted, maxLines = 1) }
            }
        }
    }
}

@Composable
fun TvRowTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = TvSafeH, top = 18.dp, bottom = 10.dp))
}

/** Fades artwork into the page from the left and bottom (hero, details). */
fun Modifier.tvScrim(): Modifier = this
    .background(Brush.horizontalGradient(0f to P.bg, 0.45f to P.bg.copy(alpha = 0.75f), 1f to Color.Transparent))
    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to P.bg))

val TvRowPadding = PaddingValues(horizontal = TvSafeH)

@Composable
fun TvBadge(text: String, accent: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = if (accent) P.onAccent else P.fg,
        modifier = Modifier.background(if (accent) P.accent else Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun RowScope.TvSpacer() = Spacer(Modifier.weight(1f))
