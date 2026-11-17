package com.chaoswares.chaosplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Theme mode, spec section 4.8: "theme (dark, light, AMOLED)". */
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    AMOLED("AMOLED black");

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

/** Exposes the active theme mode to composables that need raw tokens. */
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.DARK }

private val DarkScheme = darkColorScheme(
    primary = ChaosColors.Accent,
    onPrimary = Color.Black,
    secondary = ChaosColors.Accent2,
    background = ChaosColors.Bg,
    onBackground = ChaosColors.Text,
    surface = ChaosColors.Surface,
    onSurface = ChaosColors.Text,
    surfaceVariant = ChaosColors.Surface2,
    onSurfaceVariant = ChaosColors.TextMuted,
    error = ChaosColors.Danger
)

private val LightScheme = lightColorScheme(
    primary = ChaosColors.LightAccent,
    onPrimary = Color.White,
    secondary = ChaosColors.LightAccent2,
    background = ChaosColors.LightBg,
    onBackground = ChaosColors.LightText,
    surface = ChaosColors.LightSurface,
    onSurface = ChaosColors.LightText,
    surfaceVariant = ChaosColors.LightSurface2,
    onSurfaceVariant = ChaosColors.LightTextMuted,
    error = ChaosColors.LightDanger
)

private val AmoledScheme = DarkScheme.copy(
    background = ChaosColors.AmoledBg,
    surface = ChaosColors.AmoledBg
)

/** Type scale from spec section 2.2. */
object ChaosType {
    val Title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    /** Top-of-screen header (Library, Network, Playlists, Settings); see HeaderTitle. */
    val HeaderTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val Heading = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val Label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    // Player time uses tabular numerals so digits do not jitter
    val PlayerTime = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
fun ChaosPlayTheme(
    mode: ThemeMode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val scheme = when (mode) {
        ThemeMode.DARK -> DarkScheme
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.AMOLED -> AmoledScheme
    }
    CompositionLocalProvider(
        LocalThemeMode provides mode,
        LocalChaosTokens provides tokensFor(mode)
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
