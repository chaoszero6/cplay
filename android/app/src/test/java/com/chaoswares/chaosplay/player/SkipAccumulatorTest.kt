package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkipAccumulatorTest {
    @Test fun `quick taps in one direction add up`() {
        var s = SkipAccumulator.next(null, true, 10_000, 1_000)
        s = SkipAccumulator.next(s, true, 10_000, 1_500)
        s = SkipAccumulator.next(s, true, 10_000, 2_200)
        assertThat(s.totalMs).isEqualTo(30_000)
        assertThat(s.count).isEqualTo(3)
        assertThat(SkipAccumulator.label(s)).isEqualTo("+30 s")
    }

    @Test fun `direction change or a pause starts over`() {
        val a = SkipAccumulator.next(null, true, 10_000, 0)
        assertThat(SkipAccumulator.next(a, false, 10_000, 100).totalMs).isEqualTo(10_000)
        assertThat(SkipAccumulator.next(a, true, 10_000, 900).count).isEqualTo(1)
        assertThat(SkipAccumulator.next(a, true, 10_000, 800).count).isEqualTo(2)
    }

    @Test fun `labels`() {
        assertThat(SkipAccumulator.label(SkipAccumulator.Skip(false, 5_000, 0, 1))).isEqualTo("-5 s")
        assertThat(SkipAccumulator.label(SkipAccumulator.Skip(true, 70_000, 0, 7))).isEqualTo("+1:10")
    }
}
