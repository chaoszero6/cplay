package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * TimeFormat: every branch of position/duration formatting, the
 * elapsed/remaining toggle, seek-drag labels and speed labels.
 */
class TimeFormatTest {

    // ---- format() ----

    @Test
    fun `zero and negative positions format as 0_00`() {
        assertThat(TimeFormat.format(0L)).isEqualTo("0:00")
        assertThat(TimeFormat.format(-1L)).isEqualTo("0:00")
        assertThat(TimeFormat.format(-90_000L)).isEqualTo("0:00")
    }

    @Test
    fun `sub-minute positions format as m_ss`() {
        assertThat(TimeFormat.format(1_000L)).isEqualTo("0:01")
        assertThat(TimeFormat.format(59_000L)).isEqualTo("0:59")
    }

    @Test
    fun `sub-hour positions format as m_ss with no hour field`() {
        assertThat(TimeFormat.format(60_000L)).isEqualTo("1:00")
        assertThat(TimeFormat.format(754_000L)).isEqualTo("12:34")
        assertThat(TimeFormat.format(3_599_000L)).isEqualTo("59:59")
    }

    @Test
    fun `one hour and above format as h_mm_ss`() {
        assertThat(TimeFormat.format(3_600_000L)).isEqualTo("1:00:00")
        assertThat(TimeFormat.format(3_723_000L)).isEqualTo("1:02:03")
        assertThat(TimeFormat.format(4_000_000L)).isEqualTo("1:06:40")
    }

    @Test
    fun `partial seconds are truncated not rounded`() {
        // 1999 ms is 1.999 s: must format as 0:01
        assertThat(TimeFormat.format(1_999L)).isEqualTo("0:01")
        assertThat(TimeFormat.format(999L)).isEqualTo("0:00")
    }

    @Test
    fun `very long durations keep counting hours`() {
        // 100 hours
        assertThat(TimeFormat.format(360_000_000L)).isEqualTo("100:00:00")
    }

    // ---- formatClock(): the wireframe's fixed-width 00:12:34 ----

    @Test
    fun `clock format pads hours and keeps the hour field under an hour`() {
        assertThat(TimeFormat.formatClock(754_000L)).isEqualTo("00:12:34")
        assertThat(TimeFormat.formatClock(4_360_000L)).isEqualTo("01:12:40")
        assertThat(TimeFormat.formatClock(0L)).isEqualTo("00:00:00")
        assertThat(TimeFormat.formatClock(-5_000L)).isEqualTo("00:00:00")
    }

    @Test
    fun `clock format keeps the same width across the hour boundary`() {
        val before = TimeFormat.formatClock(3_599_000L)
        val after = TimeFormat.formatClock(3_600_000L)
        assertThat(before).isEqualTo("00:59:59")
        assertThat(after).isEqualTo("01:00:00")
        assertThat(before.length).isEqualTo(after.length)
    }

    @Test
    fun `clock remaining matches the wireframe negative form`() {
        assertThat(TimeFormat.formatClockRemaining(4_360_000L, 0L)).isEqualTo("-01:12:40")
        assertThat(TimeFormat.formatClockRemaining(100_000L, 40_000L)).isEqualTo("-00:01:00")
    }

    @Test
    fun `clock remaining clamps to zero at and past the end`() {
        assertThat(TimeFormat.formatClockRemaining(100_000L, 100_000L)).isEqualTo("-00:00:00")
        assertThat(TimeFormat.formatClockRemaining(100_000L, 150_000L)).isEqualTo("-00:00:00")
        assertThat(TimeFormat.formatClockRemaining(0L, 0L)).isEqualTo("-00:00:00")
    }

    @Test
    fun `seek bar labels are elapsed then remaining`() {
        val (elapsed, remaining) = TimeFormat.seekBarLabels(4_360_000L, 754_000L)
        assertThat(elapsed).isEqualTo("00:12:34")
        assertThat(remaining).isEqualTo("-01:00:06")
    }

    // ---- formatRemaining() ----

    @Test
    fun `remaining time is negative and uses the same scale`() {
        assertThat(TimeFormat.formatRemaining(100_000L, 40_000L)).isEqualTo("-1:00")
        // Under an hour there is no hour field, so 3,500,000 ms (58 min 20 s)
        // formats as "-58:20" rather than "-0:58:20".
        assertThat(TimeFormat.formatRemaining(3_600_000L, 100_000L)).isEqualTo("-58:20")
        assertThat(TimeFormat.formatRemaining(4_000_000L, 500_000L))
            .isEqualTo("-58:20")
    }

    @Test
    fun `remaining time gains an hour field past one hour`() {
        // 2h total, 1h10m elapsed leaves 50 min -> still under an hour
        assertThat(TimeFormat.formatRemaining(7_200_000L, 4_200_000L)).isEqualTo("-50:00")
        // 3h total, 30 min elapsed leaves 2h30m -> hour field appears
        assertThat(TimeFormat.formatRemaining(10_800_000L, 1_800_000L)).isEqualTo("-2:30:00")
    }

    @Test
    fun `remaining hits zero at the end of the media`() {
        assertThat(TimeFormat.formatRemaining(100_000L, 100_000L)).isEqualTo("-0:00")
    }

    @Test
    fun `remaining past the end clamps to zero`() {
        assertThat(TimeFormat.formatRemaining(100_000L, 150_000L)).isEqualTo("-0:00")
    }

    @Test
    fun `remaining with unknown duration shows zero`() {
        assertThat(TimeFormat.formatRemaining(0L, 0L)).isEqualTo("-0:00")
        assertThat(TimeFormat.formatRemaining(-5L, 10L)).isEqualTo("-0:00")
    }

    // ---- formatSeekDelta() ----

    @Test
    fun `forward seek delta is signed positive`() {
        assertThat(TimeFormat.formatSeekDelta(23_000L, 4_360_000L))
            .isEqualTo("+0:23 (1:12:40)")
    }

    @Test
    fun `backward seek delta is signed negative`() {
        assertThat(TimeFormat.formatSeekDelta(-23_000L, 60_000L))
            .isEqualTo("-0:23 (1:00)")
    }

    @Test
    fun `zero seek delta is signed positive`() {
        assertThat(TimeFormat.formatSeekDelta(0L, 60_000L)).isEqualTo("+0:00 (1:00)")
    }

    // ---- formatSpeed() ----

    @Test
    fun `whole speeds drop the decimal point`() {
        assertThat(TimeFormat.formatSpeed(1.0f)).isEqualTo("1x")
        assertThat(TimeFormat.formatSpeed(2.0f)).isEqualTo("2x")
        assertThat(TimeFormat.formatSpeed(4.0f)).isEqualTo("4x")
    }

    @Test
    fun `fractional speeds are trimmed of trailing zeros`() {
        assertThat(TimeFormat.formatSpeed(1.25f)).isEqualTo("1.25x")
        assertThat(TimeFormat.formatSpeed(1.5f)).isEqualTo("1.5x")
        assertThat(TimeFormat.formatSpeed(0.25f)).isEqualTo("0.25x")
        assertThat(TimeFormat.formatSpeed(0.75f)).isEqualTo("0.75x")
    }

    // ---- progressFraction() ----

    @Test
    fun `progress fraction is clamped between zero and one`() {
        assertThat(TimeFormat.progressFraction(50L, 100L)).isEqualTo(0.5f)
        assertThat(TimeFormat.progressFraction(0L, 100L)).isEqualTo(0f)
        assertThat(TimeFormat.progressFraction(100L, 100L)).isEqualTo(1f)
        assertThat(TimeFormat.progressFraction(150L, 100L)).isEqualTo(1f)
        assertThat(TimeFormat.progressFraction(-10L, 100L)).isEqualTo(0f)
    }

    @Test
    fun `progress fraction with unknown duration is zero`() {
        assertThat(TimeFormat.progressFraction(50L, 0L)).isEqualTo(0f)
        assertThat(TimeFormat.progressFraction(50L, -1L)).isEqualTo(0f)
    }

    // ---- remainingShortLabel() ----

    @Test
    fun `short remaining label covers minutes hours and finished`() {
        assertThat(TimeFormat.remainingShortLabel(300_000L, 0L)).isEqualTo("5 min left")
        assertThat(TimeFormat.remainingShortLabel(60_000L, 0L)).isEqualTo("1 min left")
        assertThat(TimeFormat.remainingShortLabel(4_000_000L, 0L)).isEqualTo("1h 6m left")
        assertThat(TimeFormat.remainingShortLabel(10_000L, 0L)).isEqualTo("<1 min left")
        assertThat(TimeFormat.remainingShortLabel(100_000L, 100_000L)).isEqualTo("Finished")
        assertThat(TimeFormat.remainingShortLabel(100_000L, 200_000L)).isEqualTo("Finished")
    }
}