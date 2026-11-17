package com.chaoswares.chaosplay.player

/**
 * Playback speed, A-B repeat and seek-step logic. Pure Kotlin so every
 * branch is unit testable (spec section 3: speed 0.25x to 4x with pitch
 * correction, A-B repeat).
 */
object PlaybackRules {

    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f
    const val DEFAULT_SPEED = 1.0f
    const val DEFAULT_SEEK_STEP_MS = 10_000L

    /** Speed presets offered as chips in the speed sheet (spec section 4.5). */
    val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)

    /** Clamps any requested speed into the supported range. */
    fun clampSpeed(speed: Float): Float {
        if (speed.isNaN()) return DEFAULT_SPEED
        return speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** Steps speed up or down through the presets. */
    fun stepSpeed(current: Float, up: Boolean): Float {
        val clamped = clampSpeed(current)
        // Snap to the nearest preset first so stepping is predictable
        val nearest = SPEED_PRESETS.minByOrNull { kotlin.math.abs(it - clamped) } ?: DEFAULT_SPEED
        val index = SPEED_PRESETS.indexOf(nearest)
        val next = if (up) {
            (index + 1).coerceAtMost(SPEED_PRESETS.lastIndex)
        } else {
            (index - 1).coerceAtLeast(0)
        }
        return SPEED_PRESETS[next]
    }

    /**
     * Two-finger vertical swipe speed change, spec section 4.2.
     * [deltaFraction] is the drag distance normalised to the screen height.
     */
    fun speedFromSwipe(baseSpeed: Float, deltaFraction: Float): Float {
        // A full-screen-height swipe changes speed by 1.0x
        return clampSpeed(baseSpeed + deltaFraction)
    }

    /** Clamps a seek target inside the media bounds. */
    fun clampSeek(targetMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return targetMs.coerceIn(0L, durationMs)
    }

    /** Applies a +/- seek step to the current position. */
    fun seekBy(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        return clampSeek(positionMs + deltaMs, durationMs)
    }

    /**
     * Seek from a horizontal drag, spec section 4.2. A full screen width
     * drag covers the whole media duration, capped so long videos stay usable.
     */
    fun seekFromDrag(
        startPositionMs: Long,
        deltaFraction: Float,
        durationMs: Long
    ): Long {
        if (durationMs <= 0L) return 0L
        // Cap a full-width drag at 90 seconds of movement
        val maxDragMs = minOf(durationMs, 90_000L)
        val delta = (maxDragMs * deltaFraction).toLong()
        return clampSeek(startPositionMs + delta, durationMs)
    }
}

/**
 * A-B repeat state machine (spec section 3, "A-B repeat (loop a section)").
 *
 * Cycling: Idle -> A set -> A-B active -> Idle
 */
data class AbRepeatState(
    val aMs: Long? = null,
    val bMs: Long? = null
) {
    val isActive: Boolean get() = aMs != null && bMs != null
    val isArmed: Boolean get() = aMs != null && bMs == null

    /**
     * Press the A-B button at [positionMs].
     * First press sets A, second sets B (if after A), third clears.
     */
    fun press(positionMs: Long): AbRepeatState {
        return when {
            aMs == null -> AbRepeatState(aMs = positionMs, bMs = null)
            bMs == null -> {
                if (positionMs > aMs!!) {
                    AbRepeatState(aMs = aMs, bMs = positionMs)
                } else {
                    // B behind A: restart from A at the new position
                    AbRepeatState(aMs = positionMs, bMs = null)
                }
            }
            else -> AbRepeatState(aMs = null, bMs = null)
        }
    }

    /**
     * Given the current playback position, where should playback jump to?
     * Returns null when no jump is needed.
     */
    fun loopTargetMs(currentMs: Long): Long? {
        if (!isActive) return null
        return if (currentMs >= bMs!!) aMs!! else null
    }

    /** Label for the UI button, e.g. "A-B", "A-", "A-B ✓". */
    fun label(): String = when {
        isActive -> "A-B"
        isArmed -> "A-"
        else -> "A-B"
    }
}