package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.ui.player.TransitionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * TransitionState: the timed exit used when leaving the player.
 * Spec section 2.5 caps UI motion at 150-250 ms, so the exit must complete
 * well inside that or back will feel slower than it already did.
 */
class TransitionStateTest {

    @Test
    fun `starts idle`() {
        val state = TransitionState()
        assertThat(state.phase).isEqualTo(TransitionState.Phase.NONE)
        assertThat(state.isExiting).isFalse()
        assertThat(state.isFinished).isFalse()
    }

    @Test
    fun `starting the exit resets progress to zero`() {
        val state = TransitionState(progress = 0.9f).startExit()
        assertThat(state.isExiting).isTrue()
        assertThat(state.progress).isEqualTo(0f)
    }

    @Test
    fun `progress advances proportionally to elapsed time`() {
        val state = TransitionState().startExit()
            .tick(90L, TransitionState.EXIT_DURATION_MS)
        assertThat(state.progress).isWithin(0.001f).of(0.5f)
        assertThat(state.isFinished).isFalse()
    }

    @Test
    fun `exit completes and never overshoots one`() {
        val state = TransitionState().startExit()
            .tick(TransitionState.EXIT_DURATION_MS * 4, TransitionState.EXIT_DURATION_MS)
        assertThat(state.progress).isEqualTo(1f)
        assertThat(state.isFinished).isTrue()
    }

    @Test
    fun `an idle state ignores ticks`() {
        val state = TransitionState().tick(500L, TransitionState.EXIT_DURATION_MS)
        assertThat(state.phase).isEqualTo(TransitionState.Phase.NONE)
        assertThat(state.progress).isEqualTo(0f)
    }

    @Test
    fun `a non positive duration completes immediately rather than dividing by zero`() {
        assertThat(TransitionState().startExit().tick(16L, 0L).isFinished).isTrue()
        assertThat(TransitionState().startExit().tick(16L, -5L).isFinished).isTrue()
    }

    @Test
    fun `negative elapsed time cannot rewind the exit`() {
        val state = TransitionState().startExit().tick(-100L, TransitionState.EXIT_DURATION_MS)
        assertThat(state.progress).isEqualTo(0f)
    }

    @Test
    fun `the exit duration sits inside the spec motion band`() {
        // Spec 2.5: motion is 150 to 250 ms
        assertThat(TransitionState.EXIT_DURATION_MS).isAtLeast(150L)
        assertThat(TransitionState.EXIT_DURATION_MS).isAtMost(250L)
    }
}
