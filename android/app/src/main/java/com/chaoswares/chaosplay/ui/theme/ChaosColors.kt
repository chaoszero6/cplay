package com.chaoswares.chaosplay.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens from the UI/UX spec section 2.1.
 * Dark theme is the default.
 */
object ChaosColors {
    // Dark (default)
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val Surface2 = Color(0xFF1F242D)
    val Text = Color(0xFFEDEFF3)
    val TextMuted = Color(0xFF9AA3B2)
    val Accent = Color(0xFF4DA3FF)
    val Accent2 = Color(0xFFFFB84D)
    val Danger = Color(0xFFFF5C5C)
    val Success = Color(0xFF3DD68C)
    val PlayerScrim = Color(0x8C000000) // rgba(0,0,0,0.55)

    // Light
    val LightBg = Color(0xFFF6F7F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFECEEF2)
    val LightText = Color(0xFF14171C)
    val LightTextMuted = Color(0xFF5C6573)
    val LightAccent = Color(0xFF1473E6)
    val LightAccent2 = Color(0xFFC77700)
    val LightDanger = Color(0xFFD93030)
    val LightSuccess = Color(0xFF14925A)

    // AMOLED
    val AmoledBg = Color(0xFF000000)
}

/** 4 dp base grid, spec section 2.3. */
object ChaosSpacing {
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
}

/** Corner radii, spec section 2.3. */
object ChaosShape {
    const val CARD = 12
    const val BUTTON = 8
}

/** Motion timings, spec section 2.5. */
object ChaosMotion {
    const val CONTROLS_FADE_MS = 200
    const val AUTO_HIDE_DELAY_MS = 3000
    const val AUTO_HIDE_MIN_MS = 2000
    const val AUTO_HIDE_MAX_MS = 8000
    const val SHEET_SLIDE_MS = 220
    const val GESTURE_FEEDBACK_FADE_MS = 600
    const val FEEDBACK_IN_MS = 90
    const val SCREEN_MS = 220
    const val BUFFERING_DELAY_MS = 400L
}