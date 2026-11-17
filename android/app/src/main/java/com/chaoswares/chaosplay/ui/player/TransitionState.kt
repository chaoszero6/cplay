package com.chaoswares.chaosplay.ui.player

/**
 * Timed state for the player's exit transition. Spec section 2.5 asks for
 * short motion (150 to 250 ms); this keeps the timing rule out of Compose so
 * it can be unit tested.
 */
data class TransitionState(
    val phase: Phase = Phase.NONE,
    val progress: Float = 0f
) {
    enum class Phase { NONE, EXITING }

    val isExiting: Boolean get() = phase == Phase.EXITING

    /** Advance the clock by [deltaMs] over a total [durationMs]. */
    fun tick(deltaMs: Long, durationMs: Long): TransitionState {
        if (phase != Phase.EXITING) return this
        if (durationMs <= 0L) return copy(progress = 1f)
        val delta = if (deltaMs < 0L) 0L else deltaMs
        return copy(progress = (progress + delta.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))
    }

    val isFinished: Boolean get() = phase == Phase.EXITING && progress >= 1f

    fun startExit(): TransitionState = copy(phase = Phase.EXITING, progress = 0f)

    companion object {
        /** Duration of the player fade-out before the library is shown. */
        const val EXIT_DURATION_MS = 180L
    }
}
