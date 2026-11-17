package com.chaoswares.chaosplay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware design tokens, spec section 2.1.
 *
 * [ChaosColors] holds the raw palette. Screens must use [LocalChaosTokens]
 * instead so dark, light and AMOLED all read correctly: the previous build
 * painted sheets and the player with the dark constants directly, which
 * made light and AMOLED themes look broken on those surfaces.
 */
data class ChaosTokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accent2: Color,
    val danger: Color,
    val success: Color,
    val scrim: Color,
    val playerScrim: Color,
    val isDark: Boolean
)

val LocalChaosTokens = staticCompositionLocalOf {
    ChaosTokens(
        bg = ChaosColors.Bg,
        surface = ChaosColors.Surface,
        surface2 = ChaosColors.Surface2,
        text = ChaosColors.Text,
        textMuted = ChaosColors.TextMuted,
        accent = ChaosColors.Accent,
        accent2 = ChaosColors.Accent2,
        danger = ChaosColors.Danger,
        success = ChaosColors.Success,
        scrim = Color(0xCC000000),
        playerScrim = ChaosColors.PlayerScrim,
        isDark = true
    )
}

/** Tokens for a given theme mode, built once here rather than per screen. */
fun tokensFor(mode: ThemeMode): ChaosTokens = when (mode) {
    ThemeMode.DARK -> ChaosTokens(
        bg = ChaosColors.Bg,
        surface = ChaosColors.Surface,
        surface2 = ChaosColors.Surface2,
        text = ChaosColors.Text,
        textMuted = ChaosColors.TextMuted,
        accent = ChaosColors.Accent,
        accent2 = ChaosColors.Accent2,
        danger = ChaosColors.Danger,
        success = ChaosColors.Success,
        scrim = Color(0xCC000000),
        playerScrim = ChaosColors.PlayerScrim,
        isDark = true
    )
    ThemeMode.LIGHT -> ChaosTokens(
        bg = ChaosColors.LightBg,
        surface = ChaosColors.LightSurface,
        surface2 = ChaosColors.LightSurface2,
        text = ChaosColors.LightText,
        textMuted = ChaosColors.LightTextMuted,
        accent = ChaosColors.LightAccent,
        accent2 = ChaosColors.LightAccent2,
        danger = ChaosColors.LightDanger,
        success = ChaosColors.LightSuccess,
        scrim = Color(0xCC000000),
        playerScrim = ChaosColors.PlayerScrim,
        isDark = false
    )
    ThemeMode.AMOLED -> ChaosTokens(
        bg = ChaosColors.AmoledBg,
        surface = ChaosColors.AmoledBg,
        surface2 = ChaosColors.Surface2,
        text = ChaosColors.Text,
        textMuted = ChaosColors.TextMuted,
        accent = ChaosColors.Accent,
        accent2 = ChaosColors.Accent2,
        danger = ChaosColors.Danger,
        success = ChaosColors.Success,
        scrim = Color(0xE6000000),
        playerScrim = ChaosColors.PlayerScrim,
        isDark = true
    )
}

/** Reads the active tokens inside a composable. */
@Composable
fun chaosTokens(): ChaosTokens = LocalChaosTokens.current

/** Convenience for previews and tests that need an explicit theme. */
@Composable
fun ProvideChaosTokens(mode: ThemeMode, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChaosTokens provides tokensFor(mode), content = content)
}
