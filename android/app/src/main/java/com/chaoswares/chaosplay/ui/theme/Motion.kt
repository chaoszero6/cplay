package com.chaoswares.chaosplay.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Motion helpers, spec 2.5: "Controls fade in/out in 200 ms", "Gesture
 * feedback appears instantly and fades 600 ms after release", "Respect the
 * system reduce motion setting". Compose animations already follow the
 * system animator duration scale, so "Remove animations" turns these off.
 */

/**
 * Shows [content] while [value] is non-null and keeps the last value on
 * screen while it fades out, so feedback does not vanish mid-frame.
 */
@Composable
fun <T> Fading(
    value: T?,
    enter: EnterTransition = fadeIn(tween(ChaosMotion.FEEDBACK_IN_MS)),
    exit: ExitTransition = fadeOut(tween(ChaosMotion.GESTURE_FEEDBACK_FADE_MS)),
    content: @Composable (T) -> Unit
) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    AnimatedVisibility(visible = value != null, enter = enter, exit = exit) {
        last?.let { content(it) }
    }
}

/** Skeleton shimmer for loading placeholders (spec 4.1 "loading skeletons"). */
fun Modifier.shimmer(base: Color, highlight: Color): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX"
    )
    background(
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = androidx.compose.ui.geometry.Offset(x * 600f, 0f),
            end = androidx.compose.ui.geometry.Offset(x * 600f + 400f, 200f)
        )
    )
}

@Composable
fun SkeletonBlock(modifier: Modifier, corner: Dp = 6.dp) {
    val t = chaosTokens()
    Box(
        modifier
            .background(t.surface2, RoundedCornerShape(corner))
            .shimmer(t.surface2, t.surface.copy(alpha = 0.9f).compositeOver(t.surface2))
    )
}

private fun Color.compositeOver(other: Color): Color {
    val a = alpha + other.alpha * (1 - alpha)
    if (a == 0f) return Color.Transparent
    return Color(
        (red * alpha + other.red * other.alpha * (1 - alpha)) / a,
        (green * alpha + other.green * other.alpha * (1 - alpha)) / a,
        (blue * alpha + other.blue * other.alpha * (1 - alpha)) / a,
        a
    )
}

/** Light haptic tick, honouring the user's setting (spec 7 "switchable"). */
class Haptics(private val enabled: Boolean, private val perform: (HapticFeedbackType) -> Unit) {
    fun tick() { if (enabled) perform(HapticFeedbackType.TextHandleMove) }
    fun strong() { if (enabled) perform(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val haptic = LocalHapticFeedback.current
    return remember(enabled, haptic) { Haptics(enabled) { haptic.performHapticFeedback(it) } }
}
