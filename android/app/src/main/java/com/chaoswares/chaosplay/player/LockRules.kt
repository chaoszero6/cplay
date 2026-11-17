package com.chaoswares.chaosplay.player

/**
 * Kids Lock / input lock, spec sections 7.3 and 4.2:
 * "Unlock by touching the four screen corners in order",
 * optional "hold-to-unlock", toddler touch effects.
 */
object LockRules {

    /**
     * The four corners in unlock order, per spec: clockwise starting
     * top-left. Each is a normalised (x, y) in 0..1 screen space.
     */
    val CORNER_SEQUENCE: List<Pair<Float, Float>> = listOf(
        0f to 0f, // top-left
        1f to 0f, // top-right
        1f to 1f, // bottom-right
        0f to 1f  // bottom-left
    )

    /**
     * How close to a corner a touch must land, as a fraction of the screen.
     * Tight enough that the centre of the screen and the interior cannot
     * satisfy a corner, loose enough to be comfortable on a phone.
     */
    const val CORNER_HIT_RADIUS = 0.15f

    /** Hold duration for the alternative hold-to-unlock gesture. */
    const val HOLD_TO_UNLOCK_MS = 3000L

    /**
     * Tracks corner-touch progress for the unlock pattern.
     * Immutable so state transitions are testable.
     */
    data class CornerProgress(val nextIndex: Int = 0) {
        val isComplete: Boolean get() = nextIndex >= CORNER_SEQUENCE.size

        /**
         * Register a touch at normalised ([x], [y]).
         *
         * Returns the new progress. Touches that miss the expected corner
         * reset progress to zero, which is what stops random taps from
         * unlocking (spec testing checklist: "lock mode cannot be triggered
         * by accident; the unlock method always works").
         *
         * Re-touching an already-consumed corner is treated as a miss, so
         * repeatedly mashing one corner can never walk the sequence
         * forward. Only the next corner in order advances progress.
         */
        fun touch(x: Float, y: Float): CornerProgress {
            if (isComplete) return this

            // Already-completed corners are dead: touching them is a miss.
            if (matcherIndex(x, y) in 0 until nextIndex) return CornerProgress(0)

            val (cx, cy) = CORNER_SEQUENCE[nextIndex]
            val hit = kotlin.math.abs(x - cx) <= CORNER_HIT_RADIUS &&
                kotlin.math.abs(y - cy) <= CORNER_HIT_RADIUS
            return if (hit) CornerProgress(nextIndex + 1) else CornerProgress(0)
        }

        /** Index of the corner this touch lands in, or -1 for none. */
        private fun matcherIndex(x: Float, y: Float): Int {
            return CORNER_SEQUENCE.indexOfFirst { (cx, cy) ->
                kotlin.math.abs(x - cx) <= CORNER_HIT_RADIUS &&
                    kotlin.math.abs(y - cy) <= CORNER_HIT_RADIUS
            }
        }
    }

    /** Which corners to show as completed in the unlock overlay. */
    fun completedCorners(progress: CornerProgress): List<Pair<Float, Float>> {
        return CORNER_SEQUENCE.take(progress.nextIndex)
    }

    /** True when a hold gesture of [heldMs] should unlock. */
    fun holdUnlocks(heldMs: Long): Boolean = heldMs >= HOLD_TO_UNLOCK_MS
}