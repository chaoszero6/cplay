package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PlaybackRules: speed clamping and stepping, seek clamping, drag seeking,
 * and the full A-B repeat state machine.
 */
class PlaybackRulesTest {

    // ---- speed ----

    @Test
    fun `speed is clamped into the supported range`() {
        assertThat(PlaybackRules.clampSpeed(0.1f)).isEqualTo(0.25f)
        assertThat(PlaybackRules.clampSpeed(0.25f)).isEqualTo(0.25f)
        assertThat(PlaybackRules.clampSpeed(1.0f)).isEqualTo(1.0f)
        assertThat(PlaybackRules.clampSpeed(4.0f)).isEqualTo(4.0f)
        assertThat(PlaybackRules.clampSpeed(10.0f)).isEqualTo(4.0f)
    }

    @Test
    fun `NaN speed falls back to default`() {
        assertThat(PlaybackRules.clampSpeed(Float.NaN)).isEqualTo(1.0f)
    }

    @Test
    fun `stepping up and down walks the presets`() {
        assertThat(PlaybackRules.stepSpeed(1.0f, up = true)).isEqualTo(1.25f)
        assertThat(PlaybackRules.stepSpeed(1.0f, up = false)).isEqualTo(0.75f)
    }

    @Test
    fun `stepping stops at the range ends`() {
        assertThat(PlaybackRules.stepSpeed(4.0f, up = true)).isEqualTo(4.0f)
        assertThat(PlaybackRules.stepSpeed(0.25f, up = false)).isEqualTo(0.25f)
    }

    @Test
    fun `stepping from an off-preset value snaps to the nearest preset first`() {
        // 1.1 is nearest to 1.0, so up should give 1.25
        assertThat(PlaybackRules.stepSpeed(1.1f, up = true)).isEqualTo(1.25f)
        // 3.9 is nearest to 4.0, so up should stay at 4.0
        assertThat(PlaybackRules.stepSpeed(3.9f, up = true)).isEqualTo(4.0f)
    }

    @Test
    fun `swipe speed adds the drag fraction and clamps`() {
        assertThat(PlaybackRules.speedFromSwipe(1.0f, 0.25f)).isEqualTo(1.25f)
        assertThat(PlaybackRules.speedFromSwipe(1.0f, -0.5f)).isEqualTo(0.5f)
        assertThat(PlaybackRules.speedFromSwipe(1.0f, 5.0f)).isEqualTo(4.0f)
        assertThat(PlaybackRules.speedFromSwipe(1.0f, -5.0f)).isEqualTo(0.25f)
    }

    // ---- seeking ----

    @Test
    fun `seek is clamped inside the media`() {
        assertThat(PlaybackRules.clampSeek(-5_000L, 100_000L)).isEqualTo(0L)
        assertThat(PlaybackRules.clampSeek(50_000L, 100_000L)).isEqualTo(50_000L)
        assertThat(PlaybackRules.clampSeek(150_000L, 100_000L)).isEqualTo(100_000L)
    }

    @Test
    fun `seek with unknown duration returns zero`() {
        assertThat(PlaybackRules.clampSeek(5_000L, 0L)).isEqualTo(0L)
        assertThat(PlaybackRules.clampSeek(5_000L, -1L)).isEqualTo(0L)
    }

    @Test
    fun `seek by step moves forward and backward`() {
        assertThat(PlaybackRules.seekBy(50_000L, 10_000L, 100_000L)).isEqualTo(60_000L)
        assertThat(PlaybackRules.seekBy(50_000L, -10_000L, 100_000L)).isEqualTo(40_000L)
    }

    @Test
    fun `seek by step clamps at both ends`() {
        assertThat(PlaybackRules.seekBy(2_000L, -10_000L, 100_000L)).isEqualTo(0L)
        assertThat(PlaybackRules.seekBy(98_000L, 10_000L, 100_000L)).isEqualTo(100_000L)
    }

    @Test
    fun `drag seek moves proportionally to the drag fraction`() {
        // 10% of a 90 s cap = 9 s
        assertThat(PlaybackRules.seekFromDrag(50_000L, 0.1f, 600_000L))
            .isEqualTo(59_000L)
    }

    @Test
    fun `drag seek is capped at 90 seconds for very long media`() {
        // Full-width drag on a 10 hour file must not jump hours
        assertThat(PlaybackRules.seekFromDrag(0L, 1.0f, 36_000_000L))
            .isEqualTo(90_000L)
    }

    @Test
    fun `drag seek on short media is capped by the duration`() {
        // 30 s file, full-width drag: cap is the 30 s duration
        assertThat(PlaybackRules.seekFromDrag(0L, 1.0f, 30_000L))
            .isEqualTo(30_000L)
    }

    @Test
    fun `drag seek clamps at the media bounds`() {
        assertThat(PlaybackRules.seekFromDrag(0L, -1.0f, 600_000L)).isEqualTo(0L)
        assertThat(PlaybackRules.seekFromDrag(600_000L, 1.0f, 600_000L)).isEqualTo(600_000L)
    }

    // ---- A-B repeat ----

    @Test
    fun `AB repeat starts idle`() {
        val state = AbRepeatState()
        assertThat(state.isActive).isFalse()
        assertThat(state.isArmed).isFalse()
        assertThat(state.loopTargetMs(1_000L)).isNull()
    }

    @Test
    fun `first press arms point A`() {
        val state = AbRepeatState().press(10_000L)
        assertThat(state.aMs).isEqualTo(10_000L)
        assertThat(state.bMs).isNull()
        assertThat(state.isArmed).isTrue()
        assertThat(state.isActive).isFalse()
        assertThat(state.label()).isEqualTo("A-")
    }

    @Test
    fun `second press after A activates the loop`() {
        val state = AbRepeatState().press(10_000L).press(30_000L)
        assertThat(state.aMs).isEqualTo(10_000L)
        assertThat(state.bMs).isEqualTo(30_000L)
        assertThat(state.isActive).isTrue()
        assertThat(state.label()).isEqualTo("A-B")
    }

    @Test
    fun `third press clears the loop`() {
        val state = AbRepeatState().press(10_000L).press(30_000L).press(50_000L)
        assertThat(state.aMs).isNull()
        assertThat(state.bMs).isNull()
        assertThat(state.isActive).isFalse()
    }

    @Test
    fun `pressing B before A restarts from the earlier point`() {
        val state = AbRepeatState().press(30_000L).press(10_000L)
        assertThat(state.aMs).isEqualTo(10_000L)
        assertThat(state.bMs).isNull()
        assertThat(state.isArmed).isTrue()
    }

    @Test
    fun `pressing B equal to A restarts rather than creating an empty loop`() {
        val state = AbRepeatState().press(10_000L).press(10_000L)
        assertThat(state.bMs).isNull()
        assertThat(state.isArmed).isTrue()
    }

    @Test
    fun `loop target is null before B is reached`() {
        val state = AbRepeatState().press(10_000L).press(30_000L)
        assertThat(state.loopTargetMs(20_000L)).isNull()
        assertThat(state.loopTargetMs(29_999L)).isNull()
    }

    @Test
    fun `loop target returns A exactly at B and beyond`() {
        val state = AbRepeatState().press(10_000L).press(30_000L)
        assertThat(state.loopTargetMs(30_000L)).isEqualTo(10_000L)
        assertThat(state.loopTargetMs(31_000L)).isEqualTo(10_000L)
        assertThat(state.loopTargetMs(999_999L)).isEqualTo(10_000L)
    }

    @Test
    fun `loop target is null when only A is set`() {
        val state = AbRepeatState().press(10_000L)
        assertThat(state.loopTargetMs(999_999L)).isNull()
    }

    @Test
    fun `AB repeat loop works when A is zero`() {
        val state = AbRepeatState().press(0L).press(5_000L)
        assertThat(state.loopTargetMs(5_000L)).isEqualTo(0L)
    }
}