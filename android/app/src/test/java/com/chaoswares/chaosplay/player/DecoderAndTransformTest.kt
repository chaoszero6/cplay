package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DecoderRules and VideoTransform/AspectMode: the HW -> HW+ -> SW fallback
 * ladder and the aspect/zoom/rotation state.
 */
class DecoderAndTransformTest {

    // ---- decoder fallback ladder ----

    @Test
    fun `hardware failure falls back to hardware plus`() {
        val next = DecoderRules.nextModeOnError(DecoderMode.HW, true, 0)
        assertThat(next).isEqualTo(DecoderMode.HW_PLUS)
    }

    @Test
    fun `hardware plus failure falls back to software`() {
        val next = DecoderRules.nextModeOnError(DecoderMode.HW_PLUS, true, 1)
        assertThat(next).isEqualTo(DecoderMode.SW)
    }

    @Test
    fun `software failure exhausts the ladder and returns null`() {
        assertThat(DecoderRules.nextModeOnError(DecoderMode.SW, true, 2)).isNull()
    }

    @Test
    fun `without hardware support every mode drops to software`() {
        assertThat(DecoderRules.nextModeOnError(DecoderMode.HW, false, 0))
            .isEqualTo(DecoderMode.SW)
        assertThat(DecoderRules.nextModeOnError(DecoderMode.HW_PLUS, false, 0))
            .isEqualTo(DecoderMode.SW)
    }

    @Test
    fun `without hardware support software has nowhere left to go`() {
        assertThat(DecoderRules.nextModeOnError(DecoderMode.SW, false, 0)).isNull()
    }

    @Test
    fun `resolve downgrades unsupported hardware requests`() {
        assertThat(DecoderRules.resolve(DecoderMode.HW, false)).isEqualTo(DecoderMode.SW)
        assertThat(DecoderRules.resolve(DecoderMode.HW_PLUS, false)).isEqualTo(DecoderMode.SW)
        assertThat(DecoderRules.resolve(DecoderMode.SW, false)).isEqualTo(DecoderMode.SW)
    }

    @Test
    fun `resolve keeps hardware requests when supported`() {
        assertThat(DecoderRules.resolve(DecoderMode.HW, true)).isEqualTo(DecoderMode.HW)
        assertThat(DecoderRules.resolve(DecoderMode.HW_PLUS, true)).isEqualTo(DecoderMode.HW_PLUS)
    }

    @Test
    fun `decoder mode cycles HW to HW plus to SW and wraps`() {
        assertThat(DecoderMode.HW.next()).isEqualTo(DecoderMode.HW_PLUS)
        assertThat(DecoderMode.HW_PLUS.next()).isEqualTo(DecoderMode.SW)
        assertThat(DecoderMode.SW.next()).isEqualTo(DecoderMode.HW)
    }

    @Test
    fun `decoder short labels match the on-screen badge`() {
        assertThat(DecoderMode.HW.shortLabel).isEqualTo("HW")
        assertThat(DecoderMode.HW_PLUS.shortLabel).isEqualTo("HW+")
        assertThat(DecoderMode.SW.shortLabel).isEqualTo("SW")
    }

    // ---- aspect mode ----

    @Test
    fun `aspect modes cycle through every option and wrap`() {
        var mode = AspectMode.FIT
        val seen = mutableListOf(mode)
        repeat(AspectMode.entries.size - 1) {
            mode = mode.next()
            seen += mode
        }
        assertThat(seen.toSet()).hasSize(AspectMode.entries.size)
        assertThat(mode.next()).isEqualTo(AspectMode.FIT)
    }

    @Test
    fun `every aspect mode has a label`() {
        AspectMode.entries.forEach { assertThat(it.label).isNotEmpty() }
    }

    // ---- transform ----

    @Test
    fun `default transform is unzoomed and unmirrored`() {
        val transform = VideoTransform()
        assertThat(transform.zoom).isEqualTo(1.0f)
        assertThat(transform.mirrored).isFalse()
        assertThat(transform.flipped).isFalse()
        assertThat(transform.rotationDegrees).isEqualTo(0)
    }

    @Test
    fun `zoom is clamped to the supported range`() {
        assertThat(VideoTransform.clampZoom(0.5f)).isEqualTo(1.0f)
        assertThat(VideoTransform.clampZoom(1.5f)).isEqualTo(1.5f)
        assertThat(VideoTransform.clampZoom(10f)).isEqualTo(5.0f)
    }

    @Test
    fun `NaN zoom resets to normal`() {
        assertThat(VideoTransform.clampZoom(Float.NaN)).isEqualTo(1.0f)
    }

    @Test
    fun `pinch zoom multiplies the current zoom`() {
        val zoomed = VideoTransform().zoomBy(2.0f)
        assertThat(zoomed.zoom).isEqualTo(2.0f)
        assertThat(zoomed.zoomBy(2.0f).zoom).isEqualTo(4.0f)
    }

    @Test
    fun `pinch out cannot go below normal zoom`() {
        val zoomed = VideoTransform(zoom = 2.0f).zoomBy(0.1f)
        assertThat(zoomed.zoom).isEqualTo(1.0f)
    }

    @Test
    fun `pinch zoom cannot exceed maximum`() {
        val zoomed = VideoTransform(zoom = 4.0f).zoomBy(4.0f)
        assertThat(zoomed.zoom).isEqualTo(5.0f)
    }

    @Test
    fun `invalid pinch factors are ignored`() {
        val transform = VideoTransform(zoom = 2.0f)
        assertThat(transform.zoomBy(Float.NaN).zoom).isEqualTo(2.0f)
        assertThat(transform.zoomBy(0f).zoom).isEqualTo(2.0f)
        assertThat(transform.zoomBy(-1f).zoom).isEqualTo(2.0f)
    }

    @Test
    fun `pan is ignored while fully zoomed out`() {
        val panned = VideoTransform().panBy(100f, 100f)
        assertThat(panned.panX).isEqualTo(0f)
        assertThat(panned.panY).isEqualTo(0f)
    }

    @Test
    fun `pan applies once zoomed in`() {
        val panned = VideoTransform(zoom = 2.0f).panBy(50f, -30f)
        assertThat(panned.panX).isEqualTo(50f)
        assertThat(panned.panY).isEqualTo(-30f)
    }

    @Test
    fun `zooming back out resets the pan`() {
        val transform = VideoTransform(zoom = 2.0f).panBy(50f, 50f)
        val zoomedOut = transform.zoomBy(0.1f)
        assertThat(zoomedOut.zoom).isEqualTo(1.0f)
        assertThat(zoomedOut.panBy(10f, 10f).panX).isEqualTo(0f)
    }

    @Test
    fun `rotation steps by 90 degrees and wraps`() {
        var transform = VideoTransform()
        transform = transform.rotate()
        assertThat(transform.rotationDegrees).isEqualTo(90)
        transform = transform.rotate().rotate().rotate()
        assertThat(transform.rotationDegrees).isEqualTo(0)
    }

    @Test
    fun `reset clears zoom pan mirror and rotation`() {
        val messy = VideoTransform(
            zoom = 3.0f, panX = 20f, panY = 20f,
            mirrored = true, flipped = true, rotationDegrees = 270
        ).reset()
        assertThat(messy).isEqualTo(VideoTransform())
    }

    @Test
    fun `zoom label reports whole percent`() {
        assertThat(VideoTransform().zoomLabel()).isEqualTo("100%")
        assertThat(VideoTransform(zoom = 2.5f).zoomLabel()).isEqualTo("250%")
    }

    @Test
    fun `mirror and flip toggles are independent`() {
        val mirrored = VideoTransform().copy(mirrored = true)
        assertThat(mirrored.mirrored).isTrue()
        assertThat(mirrored.flipped).isFalse()
        val flipped = mirrored.copy(mirrored = false, flipped = true)
        assertThat(flipped.mirrored).isFalse()
        assertThat(flipped.flipped).isTrue()
    }
}