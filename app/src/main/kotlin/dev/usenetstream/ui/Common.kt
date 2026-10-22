@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.usenetstream.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.haze

// ---- shared element plumbing: screens read these to animate a poster from row to details ----

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Marks a poster so it flies between screens that show the same [key]. No-op outside a transition. */
fun Modifier.sharedPoster(key: String): Modifier = composed {
    val shared = LocalSharedScope.current
    val anim = LocalAnimScope.current
    if (shared == null || anim == null) this
    else with(shared) {
        this@composed.sharedElement(
            rememberSharedContentState("poster-$key"),
            animatedVisibilityScope = anim,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 380f) },
        )
    }
}

// ---- touch feel ----

enum class Haptic { TAP, CONFIRM, LONG }

@Composable
fun rememberHaptic(): (Haptic) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { h ->
            view.performHapticFeedback(
                when (h) {
                    Haptic.TAP -> HapticFeedbackConstants.CLOCK_TICK
                    Haptic.CONFIRM -> HapticFeedbackConstants.CONFIRM
                    Haptic.LONG -> HapticFeedbackConstants.LONG_PRESS
                },
            )
        }
    }
}

/** Shrinks a little while pressed and springs back; click + optional long-press with haptics. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = 0.95f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val haptic = rememberHaptic()
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick?.let { lc -> { haptic(Haptic.LONG); lc() } },
        )
}

// ---- loading placeholders ----

@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    return Brush.linearGradient(
        listOf(Card, Card2, Card),
        start = Offset(x, 0f),
        end = Offset(x + 400f, 400f),
    )
}

@Composable
fun ShimmerBox(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
}

// ---- small pieces ----

@Composable
fun Badge(text: String, color: Color = Card2, textColor: Color = Fg) {
    Text(
        text,
        color = textColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Bottom-docked actions for one-handed use; sits above the gesture bar. */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, blur: dev.chrisbanes.haze.HazeState? = null, content: @Composable RowScope.() -> Unit) {
    // Floating frosted dock: the page blurs behind it.
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .glass(shape = RoundedCornerShape(28.dp), fill = LocalPalette.current.bar, elevation = 10.dp, blur = blur)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

val Emphasized = FastOutSlowInEasing

/** Marks the content that glass bars blur; no-op without a state. */
fun Modifier.hazeSource(state: dev.chrisbanes.haze.HazeState?): Modifier = if (state == null) this else this.then(Modifier.haze(state))
