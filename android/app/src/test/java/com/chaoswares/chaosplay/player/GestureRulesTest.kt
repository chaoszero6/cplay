package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * GestureRules: drag classification, double-tap zones, edge-zone rejection,
 * per-gesture disable, and the volume boost ceiling.
 */
class GestureRulesTest {

    private val allOn = GestureRules.GestureSettings()
    private val allOff = GestureRules.GestureSettings(
        brightnessEnabled = false,
        volumeEnabled = false,
        seekEnabled = false,
        doubleTapSkipEnabled = false,
        pinchZoomEnabled = false,
        twoFingerSpeedEnabled = false,
        swipeDownPipEnabled = false,
        longPressSpeedEnabled = false
    )
    private val screenWidth = 1080f

    // ---- halves ----

    @Test
    fun `left and right halves split at the centre`() {
        assertThat(GestureRules.halfOf(100f, screenWidth)).isEqualTo(GestureRules.ScreenHalf.LEFT)
        assertThat(GestureRules.halfOf(539f, screenWidth)).isEqualTo(GestureRules.ScreenHalf.LEFT)
        assertThat(GestureRules.halfOf(541f, screenWidth)).isEqualTo(GestureRules.ScreenHalf.RIGHT)
        assertThat(GestureRules.halfOf(1000f, screenWidth)).isEqualTo(GestureRules.ScreenHalf.RIGHT)
    }

    @Test
    fun `half of an invalid width defaults to left`() {
        assertThat(GestureRules.halfOf(100f, 0f)).isEqualTo(GestureRules.ScreenHalf.LEFT)
        assertThat(GestureRules.halfOf(100f, -5f)).isEqualTo(GestureRules.ScreenHalf.LEFT)
    }

    // ---- edge zones ----

    @Test
    fun `edge zones are detected on both sides`() {
        assertThat(GestureRules.inEdgeZone(10f, screenWidth)).isTrue()
        assertThat(GestureRules.inEdgeZone(1070f, screenWidth)).isTrue()
        assertThat(GestureRules.inEdgeZone(540f, screenWidth)).isFalse()
    }

    @Test
    fun `invalid width has no edge zones`() {
        assertThat(GestureRules.inEdgeZone(0f, 0f)).isFalse()
    }

    // ---- drag classification ----

    @Test
    fun `horizontal drag classifies as seek`() {
        val gesture = GestureRules.classifyDrag(
            dx = 100f, dy = 5f, x = 540f, screenWidth = screenWidth, settings = allOn
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.SEEK)
    }

    @Test
    fun `vertical drag on the left classifies as brightness`() {
        val gesture = GestureRules.classifyDrag(
            dx = 5f, dy = 100f, x = 200f, screenWidth = screenWidth, settings = allOn
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.BRIGHTNESS)
    }

    @Test
    fun `vertical drag on the right classifies as volume`() {
        val gesture = GestureRules.classifyDrag(
            dx = 5f, dy = 100f, x = 900f, screenWidth = screenWidth, settings = allOn
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.VOLUME)
    }

    @Test
    fun `tiny drags do not register`() {
        val gesture = GestureRules.classifyDrag(
            dx = 2f, dy = 2f, x = 200f, screenWidth = screenWidth, settings = allOn
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.NONE)
    }

    @Test
    fun `drag exactly at the threshold registers`() {
        val gesture = GestureRules.classifyDrag(
            dx = 24f, dy = 0f, x = 540f, screenWidth = screenWidth, settings = allOn
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.SEEK)
    }

    @Test
    fun `disabled seek gesture reports none`() {
        val settings = GestureRules.GestureSettings(seekEnabled = false)
        val gesture = GestureRules.classifyDrag(
            dx = 100f, dy = 0f, x = 540f, screenWidth = screenWidth, settings = settings
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.NONE)
    }

    @Test
    fun `disabled brightness gesture reports none on the left`() {
        val settings = GestureRules.GestureSettings(brightnessEnabled = false)
        val gesture = GestureRules.classifyDrag(
            dx = 0f, dy = 100f, x = 200f, screenWidth = screenWidth, settings = settings
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.NONE)
    }

    @Test
    fun `disabled volume gesture reports none on the right`() {
        val settings = GestureRules.GestureSettings(volumeEnabled = false)
        val gesture = GestureRules.classifyDrag(
            dx = 0f, dy = 100f, x = 900f, screenWidth = screenWidth, settings = settings
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.NONE)
    }

    @Test
    fun `every gesture off reports none for any drag`() {
        val gesture = GestureRules.classifyDrag(
            dx = 300f, dy = 300f, x = 200f, screenWidth = screenWidth, settings = allOff
        )
        assertThat(gesture).isEqualTo(GestureRules.Gesture.NONE)
    }

    // ---- double tap ----

    @Test
    fun `double tap in the left third skips back`() {
        val gesture = GestureRules.classifyDoubleTap(100f, screenWidth, allOn)
        assertThat(gesture).isEqualTo(GestureRules.Gesture.SKIP_BACK)
    }

    @Test
    fun `double tap in the right third skips forward`() {
        val gesture = GestureRules.classifyDoubleTap(900f, screenWidth, allOn)
        assertThat(gesture).isEqualTo(GestureRules.Gesture.SKIP_FORWARD)
    }

    @Test
    fun `double tap in the centre is play pause`() {
        val gesture = GestureRules.classifyDoubleTap(540f, screenWidth, allOn)
        assertThat(gesture).isEqualTo(GestureRules.Gesture.PLAY_PAUSE)
    }

    @Test
    fun `double tap boundaries fall in the expected zones`() {
        // Exactly one third is the centre zone start
        assertThat(GestureRules.classifyDoubleTap(360f, screenWidth, allOn))
            .isEqualTo(GestureRules.Gesture.PLAY_PAUSE)
        // Exactly two thirds is the forward zone start
        assertThat(GestureRules.classifyDoubleTap(720f, screenWidth, allOn))
            .isEqualTo(GestureRules.Gesture.SKIP_FORWARD)
        // Just below one third is back
        assertThat(GestureRules.classifyDoubleTap(359f, screenWidth, allOn))
            .isEqualTo(GestureRules.Gesture.SKIP_BACK)
    }

    @Test
    fun `with skipping disabled every double tap is play pause`() {
        val settings = GestureRules.GestureSettings(doubleTapSkipEnabled = false)
        assertThat(GestureRules.classifyDoubleTap(100f, screenWidth, settings))
            .isEqualTo(GestureRules.Gesture.PLAY_PAUSE)
        assertThat(GestureRules.classifyDoubleTap(900f, screenWidth, settings))
            .isEqualTo(GestureRules.Gesture.PLAY_PAUSE)
    }

    @Test
    fun `double tap with invalid width is play pause`() {
        assertThat(GestureRules.classifyDoubleTap(100f, 0f, allOn))
            .isEqualTo(GestureRules.Gesture.PLAY_PAUSE)
    }

    // ---- level from drag ----

    @Test
    fun `brightness drag up increases and down decreases`() {
        // Drag up is a negative dy, which should raise the level
        assertThat(GestureRules.levelFromDrag(0.5f, -0.25f)).isEqualTo(0.75f)
        assertThat(GestureRules.levelFromDrag(0.5f, 0.25f)).isEqualTo(0.25f)
    }

    @Test
    fun `brightness is clamped to zero and one`() {
        assertThat(GestureRules.levelFromDrag(0.1f, 1.0f)).isEqualTo(0f)
        assertThat(GestureRules.levelFromDrag(0.9f, -1.0f)).isEqualTo(1f)
    }

    @Test
    fun `NaN drag leaves the brightness level unchanged`() {
        assertThat(GestureRules.levelFromDrag(0.4f, Float.NaN)).isEqualTo(0.4f)
    }

    // ---- volume boost ----

    @Test
    fun `volume can be boosted past 100 percent up to 200`() {
        assertThat(GestureRules.volumeFromDrag(1.0f, -1.0f)).isEqualTo(2.0f)
        assertThat(GestureRules.volumeFromDrag(1.0f, -5.0f)).isEqualTo(2.0f)
    }

    @Test
    fun `volume is clamped at zero`() {
        assertThat(GestureRules.volumeFromDrag(0.2f, 1.0f)).isEqualTo(0f)
    }

    @Test
    fun `boosted flag only trips above 100 percent`() {
        assertThat(GestureRules.isBoosted(1.0f)).isFalse()
        assertThat(GestureRules.isBoosted(1.01f)).isTrue()
        assertThat(GestureRules.isBoosted(2.0f)).isTrue()
    }
}