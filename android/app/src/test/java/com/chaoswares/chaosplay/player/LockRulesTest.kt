package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * LockRules: the four-corner unlock sequence, including that random or
 * out-of-order touches must never unlock the screen.
 */
class LockRulesTest {

    @Test
    fun `corners run clockwise from top left`() {
        assertThat(LockRules.CORNER_SEQUENCE).hasSize(4)
        assertThat(LockRules.CORNER_SEQUENCE[0]).isEqualTo(0f to 0f)
        assertThat(LockRules.CORNER_SEQUENCE[1]).isEqualTo(1f to 0f)
        assertThat(LockRules.CORNER_SEQUENCE[2]).isEqualTo(1f to 1f)
        assertThat(LockRules.CORNER_SEQUENCE[3]).isEqualTo(0f to 1f)
    }

    @Test
    fun `progress starts at zero`() {
        val progress = LockRules.CornerProgress()
        assertThat(progress.nextIndex).isEqualTo(0)
        assertThat(progress.isComplete).isFalse()
    }

    @Test
    fun `touching the four corners in order unlocks`() {
        var progress = LockRules.CornerProgress()
        LockRules.CORNER_SEQUENCE.forEach { (x, y) ->
            progress = progress.touch(x, y)
        }
        assertThat(progress.isComplete).isTrue()
    }

    @Test
    fun `touching corners near but not exactly on them still counts`() {
        var progress = LockRules.CornerProgress()
        progress = progress.touch(0.1f, 0.05f)   // near top-left
        progress = progress.touch(0.95f, 0.02f)  // near top-right
        progress = progress.touch(0.9f, 0.95f)   // near bottom-right
        progress = progress.touch(0.02f, 0.9f)   // near bottom-left
        assertThat(progress.isComplete).isTrue()
    }

    @Test
    fun `touches just inside the hit radius count and just outside do not`() {
        // 0.15 is the radius; 0.1 from the corner is inside, 0.2 is outside
        assertThat(LockRules.CornerProgress().touch(0.1f, 0.1f).nextIndex).isEqualTo(1)
        assertThat(LockRules.CornerProgress().touch(0.2f, 0.2f).nextIndex).isEqualTo(0)
    }

    @Test
    fun `touching the screen centre resets progress`() {
        var progress = LockRules.CornerProgress().touch(0f, 0f)
        assertThat(progress.nextIndex).isEqualTo(1)
        progress = progress.touch(0.5f, 0.5f)
        assertThat(progress.nextIndex).isEqualTo(0)
    }

    @Test
    fun `touching corners out of order never unlocks`() {
        var progress = LockRules.CornerProgress()
        // Deliberately wrong order: TR, TL, BR, BL
        progress = progress.touch(1f, 0f)
        progress = progress.touch(0f, 0f)
        progress = progress.touch(1f, 1f)
        progress = progress.touch(0f, 1f)
        assertThat(progress.isComplete).isFalse()
    }

    @Test
    fun `repeated taps in the same corner do not unlock`() {
        var progress = LockRules.CornerProgress()
        repeat(10) { progress = progress.touch(0f, 0f) }
        assertThat(progress.isComplete).isFalse()
        // Re-touching a consumed corner is a miss, so progress resets
        // rather than walking forward on a single corner.
        assertThat(progress.nextIndex).isEqualTo(0)
    }

    @Test
    fun `touching a consumed corner restarts the sequence`() {
        // Complete the first corner, then touch it again: this is a miss,
        // so the pattern restarts instead of advancing to corner two.
        val progress = LockRules.CornerProgress()
            .touch(0f, 0f)   // corner 1 done
            .touch(0f, 0f)   // corner 1 again -> miss
        assertThat(progress.nextIndex).isEqualTo(0)
        // The sequence is still completable from the start afterwards
        var recovered = progress
        LockRules.CORNER_SEQUENCE.forEach { (x, y) -> recovered = recovered.touch(x, y) }
        assertThat(recovered.isComplete).isTrue()
    }

    @Test
    fun `skipping a corner resets progress`() {
        // Touch corner 2 first: never matches the expected corner 1
        val progress = LockRules.CornerProgress().touch(1f, 0f)
        assertThat(progress.nextIndex).isEqualTo(0)
    }

    @Test
    fun `the centre of the screen is far from every corner`() {
        val progress = LockRules.CornerProgress().touch(0.5f, 0.5f)
        assertThat(progress.nextIndex).isEqualTo(0)
    }

    @Test
    fun `random scattered touches do not unlock`() {
        val touches = listOf(
            0.3f to 0.7f, 0.8f to 0.2f, 0.5f to 0.5f, 0.2f to 0.9f,
            0.6f to 0.4f, 0.1f to 0.3f
        )
        var progress = LockRules.CornerProgress()
        touches.forEach { (x, y) -> progress = progress.touch(x, y) }
        assertThat(progress.isComplete).isFalse()
    }

    @Test
    fun `once complete further touches keep it complete`() {
        var progress = LockRules.CornerProgress()
        LockRules.CORNER_SEQUENCE.forEach { (x, y) -> progress = progress.touch(x, y) }
        progress = progress.touch(0.5f, 0.5f)
        assertThat(progress.isComplete).isTrue()
    }

    @Test
    fun `out of bounds coordinates do not match a corner`() {
        val progress = LockRules.CornerProgress().touch(2f, 2f)
        assertThat(progress.nextIndex).isEqualTo(0)
    }

    @Test
    fun `completed corners are exposed in order for the overlay`() {
        val progress = LockRules.CornerProgress().touch(0f, 0f).touch(1f, 0f)
        val completed = LockRules.completedCorners(progress)
        assertThat(completed).hasSize(2)
        assertThat(completed[0]).isEqualTo(0f to 0f)
        assertThat(completed[1]).isEqualTo(1f to 0f)
    }

    @Test
    fun `no completed corners when progress is zero`() {
        assertThat(LockRules.completedCorners(LockRules.CornerProgress())).isEmpty()
    }

    // ---- hold to unlock ----

    @Test
    fun `hold unlocks only at or past the hold duration`() {
        assertThat(LockRules.holdUnlocks(0L)).isFalse()
        assertThat(LockRules.holdUnlocks(2_999L)).isFalse()
        assertThat(LockRules.holdUnlocks(3_000L)).isTrue()
        assertThat(LockRules.holdUnlocks(10_000L)).isTrue()
    }
}