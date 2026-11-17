package com.chaoswares.chaosplay.player

/**
 * Sleep timer, spec section 4.2 top bar overflow:
 * "info, A-B repeat, sleep timer, screenshot, share".
 *
 * Pure state machine: the UI ticks it with the elapsed wall-clock time and
 * pauses playback when [remainingMs] reaches zero.
 */
object SleepTimer {

    /** Preset choices offered in the sleep timer sheet, in minutes. */
    val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)

    private const val MS_PER_MINUTE = 60_000L

    /** Format a deadline for the button label, e.g. "Sleep 15m". */
    fun label(totalMs: Long?): String {
        if (totalMs == null || totalMs <= 0L) return "Sleep"
        val minutes = (totalMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE
        return "Sleep ${minutes}m"
    }

    fun minutesToMs(minutes: Int): Long = minutes.coerceAtLeast(0).toLong() * MS_PER_MINUTE

    /** Whole minutes left, rounded up so "1m" means "less than a minute". */
    fun remainingMinutes(remainingMs: Long): Int {
        if (remainingMs <= 0L) return 0
        return ((remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    }

    /**
     * Advances a running timer.
     *
     * @param remainingMs time left before this tick, or null when idle
     * @param elapsedMs how much wall-clock time passed
     * @return the new remaining time, or null when idle or just expired
     */
    fun tick(remainingMs: Long?, elapsedMs: Long): Long? {
        if (remainingMs == null) return null
        val left = remainingMs - elapsedMs.coerceAtLeast(0L)
        return if (left <= 0L) null else left
    }

    /** True when the timer has just reached zero and playback should pause. */
    fun shouldPause(remainingMs: Long?, elapsedMs: Long): Boolean {
        if (remainingMs == null) return false
        return remainingMs - elapsedMs.coerceAtLeast(0L) <= 0L
    }
}
