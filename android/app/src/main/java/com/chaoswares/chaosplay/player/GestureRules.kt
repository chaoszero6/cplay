package com.chaoswares.chaosplay.player

/**
 * Gesture interpretation, spec section 4.2. Kept free of Compose so every
 * branch can be unit tested, including the "all gestures can be switched
 * off individually" and "edge zones are ignored to avoid system-gesture
 * conflicts" rules.
 */
object GestureRules {

    /** Which gestures the user has enabled (spec section 4.8). */
    data class GestureSettings(
        val brightnessEnabled: Boolean = true,
        val volumeEnabled: Boolean = true,
        val seekEnabled: Boolean = true,
        val doubleTapSkipEnabled: Boolean = true,
        val pinchZoomEnabled: Boolean = true,
        val twoFingerSpeedEnabled: Boolean = true,
        val swipeDownPipEnabled: Boolean = true,
        val longPressSpeedEnabled: Boolean = true
    )

    enum class Gesture {
        NONE,
        BRIGHTNESS,
        VOLUME,
        SEEK,
        SKIP_FORWARD,
        SKIP_BACK,
        PLAY_PAUSE,
        ZOOM,
        SPEED,
        PIP
    }

    /** Screen halves used for the brightness/volume split. */
    enum class ScreenHalf { LEFT, RIGHT }

    /** Edge zone width as a fraction of screen width, ignored for gestures. */
    const val EDGE_ZONE_FRACTION = 0.05f

    /** Minimum drag distance before a vertical/horizontal drag registers. */
    const val MIN_DRAG_PX = 24f

    /** Speed multiplier while long-pressing for temporary fast playback. */
    const val LONG_PRESS_SPEED = 2.0f

    fun halfOf(x: Float, screenWidth: Float): ScreenHalf {
        if (screenWidth <= 0f) return ScreenHalf.LEFT
        return if (x < screenWidth / 2f) ScreenHalf.LEFT else ScreenHalf.RIGHT
    }

    /** True when the touch is inside the ignored edge zones. */
    fun inEdgeZone(x: Float, screenWidth: Float): Boolean {
        if (screenWidth <= 0f) return false
        val edge = screenWidth * EDGE_ZONE_FRACTION
        return x < edge || x > screenWidth - edge
    }

    /**
     * Classifies a drag gesture from its raw deltas.
     * Horizontal dominates when |dx| exceeds |dy|, matching the spec's
     * "Drag horizontally: seek".
     */
    fun classifyDrag(
        dx: Float,
        dy: Float,
        x: Float,
        screenWidth: Float,
        settings: GestureSettings
    ): Gesture {
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        if (horizontal) {
            if (!settings.seekEnabled) return Gesture.NONE
            return if (kotlin.math.abs(dx) >= MIN_DRAG_PX) Gesture.SEEK else Gesture.NONE
        }
        if (kotlin.math.abs(dy) < MIN_DRAG_PX) return Gesture.NONE
        return when (halfOf(x, screenWidth)) {
            ScreenHalf.LEFT -> if (settings.brightnessEnabled) Gesture.BRIGHTNESS else Gesture.NONE
            ScreenHalf.RIGHT -> if (settings.volumeEnabled) Gesture.VOLUME else Gesture.NONE
        }
    }

    /**
     * Double tap zones, spec section 4.2:
     * left/right = skip back/forward, centre = play/pause.
     * With double-tap skipping switched off, any double tap is play/pause.
     */
    fun classifyDoubleTap(
        x: Float,
        screenWidth: Float,
        settings: GestureSettings
    ): Gesture {
        if (screenWidth <= 0f) return Gesture.PLAY_PAUSE
        if (!settings.doubleTapSkipEnabled) return Gesture.PLAY_PAUSE
        val third = screenWidth / 3f
        // Zones are [0, third), [third, 2*third), [2*third, width]. The
        // boundary at exactly two thirds belongs to the forward zone, which
        // needs >= rather than > or the right edge of the centre zone wins.
        return when {
            x < third -> Gesture.SKIP_BACK
            x >= third * 2f -> Gesture.SKIP_FORWARD
            else -> Gesture.PLAY_PAUSE
        }
    }

    /**
     * Applies a brightness/volume drag to a starting level.
     * A full-height drag covers the whole range.
     */
    fun levelFromDrag(startLevel: Float, dragFraction: Float): Float {
        if (dragFraction.isNaN()) return startLevel.coerceIn(0f, 1f)
        return (startLevel - dragFraction).coerceIn(0f, 1f)
    }

    /**
     * Volume can be boosted past 100% up to 200% (spec section 6:
     * "Volume boost up to 200%"). Brightness cannot.
     */
    fun volumeFromDrag(startVolume: Float, dragFraction: Float): Float {
        return (startVolume - dragFraction).coerceIn(0f, 2.0f)
    }

    /** True when the volume indicator should use the boost warning colour. */
    fun isBoosted(volume: Float): Boolean = volume > 1.0f

    /** Relative span change above which a two-finger gesture is a pinch, not a swipe. */
    const val PINCH_SPAN_THRESHOLD = 0.08f

    /** Two-finger vertical travel (fraction of height) before it counts as a speed swipe. */
    const val TWO_FINGER_SWIPE_THRESHOLD = 0.04f

    /**
     * Classifies a two-finger gesture, spec 4.2: "Pinch: zoom" and
     * "Two-finger vertical swipe: speed". A pinch changes the distance
     * between the fingers; a swipe moves both fingers together.
     */
    fun classifyTwoFinger(
        startSpan: Float,
        currentSpan: Float,
        centroidDy: Float,
        screenHeight: Float,
        settings: GestureSettings
    ): Gesture {
        if (startSpan <= 0f || screenHeight <= 0f) return Gesture.NONE
        val spanChange = kotlin.math.abs(currentSpan - startSpan) / startSpan
        val travel = kotlin.math.abs(centroidDy) / screenHeight
        return when {
            spanChange >= PINCH_SPAN_THRESHOLD && settings.pinchZoomEnabled -> Gesture.ZOOM
            travel >= TWO_FINGER_SWIPE_THRESHOLD && spanChange < PINCH_SPAN_THRESHOLD &&
                settings.twoFingerSpeedEnabled -> Gesture.SPEED
            else -> Gesture.NONE
        }
    }

    /**
     * Swipe down to enter Picture-in-Picture (spec 4.2). Only a long,
     * mostly-vertical downward swipe in the middle third counts, so normal
     * brightness/volume drags on the left and right never trigger it.
     */
    fun isPipSwipe(
        startX: Float,
        totalDx: Float,
        totalDy: Float,
        screenWidth: Float,
        screenHeight: Float,
        settings: GestureSettings
    ): Boolean {
        if (!settings.swipeDownPipEnabled || screenWidth <= 0f || screenHeight <= 0f) return false
        val third = screenWidth / 3f
        if (startX < third || startX > third * 2f) return false
        if (totalDy < screenHeight * 0.3f) return false
        return kotlin.math.abs(totalDx) < totalDy * 0.5f
    }

    /** True when a vertical drag starting at [startX] should be reserved for the PiP swipe. */
    fun inPipZone(startX: Float, screenWidth: Float, settings: GestureSettings): Boolean {
        if (!settings.swipeDownPipEnabled || screenWidth <= 0f) return false
        val third = screenWidth / 3f
        return startX >= third && startX <= third * 2f
    }
}
/**
 * Double-tap skip feedback that adds up, so three quick taps on the right
 * read "+30 s" instead of three separate "+10 s" flashes.
 */
object SkipAccumulator {

    const val WINDOW_MS = 800L

    data class Skip(val forward: Boolean, val totalMs: Long, val atMs: Long, val count: Int)

    fun next(previous: Skip?, forward: Boolean, stepMs: Long, nowMs: Long): Skip {
        val continues = previous != null && previous.forward == forward && nowMs - previous.atMs <= WINDOW_MS
        return if (continues) {
            Skip(forward, previous!!.totalMs + stepMs, nowMs, previous.count + 1)
        } else {
            Skip(forward, stepMs, nowMs, 1)
        }
    }

    /** "+30 s", "-1:10" for long totals. */
    fun label(skip: Skip): String {
        val sign = if (skip.forward) "+" else "-"
        val s = skip.totalMs / 1000
        return if (s >= 60) "$sign${s / 60}:${"%02d".format(s % 60)}" else "$sign$s s"
    }
}
