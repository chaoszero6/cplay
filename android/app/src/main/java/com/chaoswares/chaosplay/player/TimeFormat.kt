package com.chaoswares.chaosplay.player

/**
 * Formats media times exactly as the player UI expects (spec section 2.2:
 * player time uses 14 pt tabular numerals, elapsed/remaining toggle).
 *
 * Pure Kotlin, no Android dependencies, so it is fully unit testable.
 */
object TimeFormat {

    /**
     * Formats a position/duration for the seek bar and labels.
     *
     * - Under one hour:  "12:34"
     * - One hour or more: "1:02:03"
     * - Negative or zero: "0:00"
     */
    fun format(positionMs: Long): String {
        if (positionMs <= 0L) return "0:00"
        val totalSeconds = positionMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats the remaining time as a negative value, e.g. "-01:05:20".
     * Returns "-0:00" for zero/negative input so the UI stays predictable.
     */
    fun formatRemaining(durationMs: Long, positionMs: Long): String {
        val remaining = durationMs - positionMs
        if (remaining <= 0L) return "-0:00"
        return "-" + format(remaining)
    }

    /**
     * Fixed-width clock for the player seek bar, spec section 4.2 wireframe:
     * overnight hours are zero padded and sub-hour media keeps the hour field
     * so the two labels do not jump in width as playback crosses an hour.
     * 754 s -> "00:12:34", 4 360 s -> "01:12:40".
     */
    fun formatClock(positionMs: Long): String {
        if (positionMs <= 0L) return "00:00:00"
        val totalSeconds = positionMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Remaining time for the right hand side of the seek bar, matching the
     * wireframe's "-01:05:20". Clamps to "-00:00:00" at or past the end.
     */
    fun formatClockRemaining(durationMs: Long, positionMs: Long): String {
        val remaining = durationMs - positionMs
        if (remaining <= 0L) return "-00:00:00"
        return "-" + formatClock(remaining)
    }

    /** Full seek-bar label pair used by the player: elapsed and remaining. */
    fun seekBarLabels(durationMs: Long, positionMs: Long): Pair<String, String> {
        return formatClock(positionMs) to formatClockRemaining(durationMs, positionMs)
    }

    /**
     * The seek bar label during a horizontal drag, spec section 4.2:
     * "+00:23 (01:12:40)" - signed delta plus the absolute target.
     */
    fun formatSeekDelta(deltaMs: Long, targetMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        val magnitude = kotlin.math.abs(deltaMs)
        return "$sign${format(magnitude)} (${format(targetMs)})"
    }

    /** Playback speed label, e.g. "1x", "1.25x", "0.5x". */
    fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}x"
        } else {
            // Trim trailing zeros: 1.25 -> "1.25", 1.50 -> "1.5"
            val trimmed = String.format("%.2f", speed).trimEnd('0').trimEnd('.')
            "${trimmed}x"
        }
    }

    /** Progress as a 0f..1f fraction, safe against zero/negative duration. */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** " 5 min left" style label used on continue-watching cards. */
    fun remainingShortLabel(durationMs: Long, positionMs: Long): String {
        val remainingMs = durationMs - positionMs
        if (remainingMs <= 0L) return "Finished"
        val totalMinutes = remainingMs / 60000L
        return when {
            totalMinutes < 1L -> "<1 min left"
            totalMinutes < 60L -> "$totalMinutes min left"
            else -> {
                val hours = totalMinutes / 60L
                val minutes = totalMinutes % 60L
                "${hours}h ${minutes}m left"
            }
        }
    }
}