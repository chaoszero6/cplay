package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Decoder modes must map to genuinely different renderer tuning; these tests
 * pin the differences so a refactor cannot quietly collapse HW, HW+ and SW
 * back into one behaviour (which is exactly the bug this class fixes).
 */
class DecoderTuningTest {

    @Test
    fun `every mode has a distinct tuning`() {
        val tunings = DecoderMode.entries.map { DecoderRules.tuningFor(it) }
        assertThat(tunings.toSet().size).isEqualTo(DecoderMode.entries.size)
    }

    @Test
    fun `hw uses platform defaults`() {
        val t = DecoderRules.tuningFor(DecoderMode.HW)
        assertThat(t.maxFrameRate).isEqualTo(0)
        assertThat(t.dropLateFrames).isFalse()
        assertThat(t.enableMediaCodecAsyncQueueing).isFalse()
        assertThat(t.recycleInputBuffers).isTrue()
    }

    @Test
    fun `hw plus keeps every frame and boosts queueing`() {
        val t = DecoderRules.tuningFor(DecoderMode.HW_PLUS)
        assertThat(t.dropLateFrames).isFalse()
        assertThat(t.enableMediaCodecAsyncQueueing).isTrue()
        assertThat(t.enableMediaCodecBufferDecoding).isTrue()
        assertThat(t.maxFrameRate).isEqualTo(0)
        assertThat(t.allowQueueingExcessDuringSlowMotion).isFalse()
    }

    @Test
    fun `sw caps the frame rate and drops late frames`() {
        val t = DecoderRules.tuningFor(DecoderMode.SW)
        assertThat(t.maxFrameRate).isGreaterThan(0)
        assertThat(t.dropLateFrames).isTrue()
        assertThat(t.dropInputBuffersWhileLate).isTrue()
        assertThat(t.recycleInputBuffers).isFalse()
    }

    @Test
    fun `hw plus and sw differ on the dimensions that matter`() {
        val hwPlus = DecoderRules.tuningFor(DecoderMode.HW_PLUS)
        val sw = DecoderRules.tuningFor(DecoderMode.SW)
        assertThat(hwPlus.dropLateFrames).isNotEqualTo(sw.dropLateFrames)
        assertThat(hwPlus.maxFrameRate).isNotEqualTo(sw.maxFrameRate)
        assertThat(hwPlus.enableMediaCodecAsyncQueueing).isNotEqualTo(sw.enableMediaCodecAsyncQueueing)
    }

    @Test
    fun `tuning summary names the budget`() {
        assertThat(DecoderRules.tuningFor(DecoderMode.SW).summary).contains("fps")
        assertThat(DecoderRules.tuningFor(DecoderMode.HW).summary).contains("unlimited")
    }

    @Test
    fun `sw is described as compatibility, not pure software`() {
        // The label must not promise a decoder the app cannot ship.
        assertThat(DecoderMode.SW.label.lowercase()).contains("compatibility")
    }
}
