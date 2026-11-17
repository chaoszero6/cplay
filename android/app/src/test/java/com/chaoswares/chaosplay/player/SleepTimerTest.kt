package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SleepTimer: the presets and countdown behind the player overflow's
 * "sleep timer" entry (spec section 4.2 top bar).
 */
class SleepTimerTest {

    @Test
    fun `presets are ascending minutes`() {
        assertThat(SleepTimer.PRESET_MINUTES).isNotEmpty()
        assertThat(SleepTimer.PRESET_MINUTES).isInOrder()
        assertThat(SleepTimer.PRESET_MINUTES.first()).isEqualTo(5)
    }

    @Test
    fun `minutes convert to milliseconds`() {
        assertThat(SleepTimer.minutesToMs(15)).isEqualTo(900_000L)
        assertThat(SleepTimer.minutesToMs(0)).isEqualTo(0L)
        assertThat(SleepTimer.minutesToMs(-5)).isEqualTo(0L)
    }

    @Test
    fun `idle timer has a plain label`() {
        assertThat(SleepTimer.label(null)).isEqualTo("Sleep")
        assertThat(SleepTimer.label(0L)).isEqualTo("Sleep")
    }

    @Test
    fun `running timer labels the whole minutes left`() {
        assertThat(SleepTimer.label(900_000L)).isEqualTo("Sleep 15m")
        // 61 seconds left rounds up to two minutes so it never shows "0m"
        assertThat(SleepTimer.label(61_000L)).isEqualTo("Sleep 2m")
    }

    @Test
    fun `remaining minutes round up so a partial minute is never zero`() {
        assertThat(SleepTimer.remainingMinutes(1_000L)).isEqualTo(1)
        assertThat(SleepTimer.remainingMinutes(60_000L)).isEqualTo(1)
        assertThat(SleepTimer.remainingMinutes(60_001L)).isEqualTo(2)
        assertThat(SleepTimer.remainingMinutes(0L)).isEqualTo(0)
        assertThat(SleepTimer.remainingMinutes(-1L)).isEqualTo(0)
    }

    @Test
    fun `tick subtracts elapsed time`() {
        assertThat(SleepTimer.tick(60_000L, 1_000L)).isEqualTo(59_000L)
    }

    @Test
    fun `tick expires the timer to null`() {
        assertThat(SleepTimer.tick(1_000L, 1_000L)).isNull()
        assertThat(SleepTimer.tick(1_000L, 5_000L)).isNull()
    }

    @Test
    fun `tick on an idle timer stays idle`() {
        assertThat(SleepTimer.tick(null, 5_000L)).isNull()
    }

    @Test
    fun `negative elapsed time cannot add time`() {
        assertThat(SleepTimer.tick(60_000L, -10_000L)).isEqualTo(60_000L)
    }

    @Test
    fun `should pause fires exactly when the countdown runs out`() {
        assertThat(SleepTimer.shouldPause(null, 999_999L)).isFalse()
        assertThat(SleepTimer.shouldPause(10_000L, 9_999L)).isFalse()
        assertThat(SleepTimer.shouldPause(10_000L, 10_000L)).isTrue()
        assertThat(SleepTimer.shouldPause(10_000L, 60_000L)).isTrue()
    }
}
