package com.chaoswares.chaosplay.player

import com.chaoswares.chaosplay.data.FileNameRules
import com.chaoswares.chaosplay.data.PinRules
import com.chaoswares.chaosplay.util.FieldCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Aspect, gesture, PIN, rename and codec rules added with the usability pass. */
class NewRulesTest {

    @Test fun `fill crops and stretch stretches (they were swapped)`() {
        assertThat(AspectRules.layoutFor(AspectMode.FILL).resize).isEqualTo(AspectRules.Resize.CROP)
        assertThat(AspectRules.layoutFor(AspectMode.STRETCH).resize).isEqualTo(AspectRules.Resize.STRETCH)
        assertThat(AspectRules.layoutFor(AspectMode.FIT).resize).isEqualTo(AspectRules.Resize.FIT)
        assertThat(AspectRules.layoutFor(AspectMode.RATIO_16_9).forcedAspect).isWithin(0.001f).of(16f / 9f)
        assertThat(AspectRules.layoutFor(AspectMode.RATIO_4_3).forcedAspect).isWithin(0.001f).of(4f / 3f)
        assertThat(AspectRules.layoutFor(AspectMode.PERCENT_100).nativeSize).isTrue()
        AspectMode.entries.forEach { assertThat(AspectRules.layoutFor(it)).isNotNull() }
    }

    @Test fun `fit box letterboxes and pillarboxes`() {
        assertThat(AspectRules.fitBox(1920f, 1080f, 4f / 3f)).isEqualTo(1440f to 1080f)
        assertThat(AspectRules.fitBox(1080f, 1920f, 16f / 9f)).isEqualTo(1080f to 607.5f)
        assertThat(AspectRules.fitBox(0f, 100f, 1f)).isEqualTo(0f to 100f)
    }

    @Test fun `rotation fit scale only for quarter turns`() {
        assertThat(AspectRules.rotationFitScale(0, 1080f, 1920f)).isEqualTo(1f)
        assertThat(AspectRules.rotationFitScale(180, 1080f, 1920f)).isEqualTo(1f)
        assertThat(AspectRules.rotationFitScale(90, 1080f, 1920f)).isWithin(0.001f).of(0.5625f)
        assertThat(AspectRules.rotationFitScale(-90, 1920f, 1080f)).isWithin(0.001f).of(0.5625f)
    }

    @Test fun `texture view only when a transform needs it`() {
        assertThat(AspectRules.needsTextureView(VideoTransform())).isFalse()
        assertThat(AspectRules.needsTextureView(VideoTransform(mirrored = true))).isTrue()
        assertThat(AspectRules.needsTextureView(VideoTransform(zoom = 1.5f))).isTrue()
        assertThat(AspectRules.needsTextureView(VideoTransform(rotationDegrees = 90))).isTrue()
    }

    @Test fun `pip ratio is clamped to android limits`() {
        assertThat(AspectRules.pipRatio(1920, 1080)).isEqualTo(1920 to 1080)
        assertThat(AspectRules.pipRatio(3000, 1000)).isEqualTo(239 to 100)
        assertThat(AspectRules.pipRatio(100, 1000)).isEqualTo(100 to 239)
        assertThat(AspectRules.pipRatio(0, 0)).isEqualTo(16 to 9)
    }

    private val on = GestureRules.GestureSettings()

    @Test fun `two finger pinch versus swipe`() {
        assertThat(GestureRules.classifyTwoFinger(200f, 260f, 5f, 1000f, on)).isEqualTo(GestureRules.Gesture.ZOOM)
        assertThat(GestureRules.classifyTwoFinger(200f, 205f, -80f, 1000f, on)).isEqualTo(GestureRules.Gesture.SPEED)
        assertThat(GestureRules.classifyTwoFinger(200f, 205f, 10f, 1000f, on)).isEqualTo(GestureRules.Gesture.NONE)
        assertThat(GestureRules.classifyTwoFinger(0f, 205f, 100f, 1000f, on)).isEqualTo(GestureRules.Gesture.NONE)
        val off = on.copy(pinchZoomEnabled = false, twoFingerSpeedEnabled = false)
        assertThat(GestureRules.classifyTwoFinger(200f, 260f, 5f, 1000f, off)).isEqualTo(GestureRules.Gesture.NONE)
        assertThat(GestureRules.classifyTwoFinger(200f, 205f, -80f, 1000f, off)).isEqualTo(GestureRules.Gesture.NONE)
    }

    @Test fun `swipe down pip only in the middle third when enabled`() {
        assertThat(GestureRules.isPipSwipe(500f, 20f, 700f, 1080f, 2000f, on)).isTrue()
        assertThat(GestureRules.isPipSwipe(100f, 20f, 700f, 1080f, 2000f, on)).isFalse()
        assertThat(GestureRules.isPipSwipe(500f, 20f, 300f, 1080f, 2000f, on)).isFalse()
        assertThat(GestureRules.isPipSwipe(500f, 500f, 700f, 1080f, 2000f, on)).isFalse()
        assertThat(GestureRules.isPipSwipe(500f, 20f, -700f, 1080f, 2000f, on)).isFalse()
        val off = on.copy(swipeDownPipEnabled = false)
        assertThat(GestureRules.isPipSwipe(500f, 20f, 700f, 1080f, 2000f, off)).isFalse()
        assertThat(GestureRules.inPipZone(500f, 1080f, on)).isTrue()
        assertThat(GestureRules.inPipZone(500f, 1080f, off)).isFalse()
    }

    @Test fun `pin validation hashing and verification`() {
        assertThat(PinRules.isValid("1234")).isTrue()
        assertThat(PinRules.isValid("123")).isFalse()
        assertThat(PinRules.isValid("123456789")).isFalse()
        assertThat(PinRules.isValid("12a4")).isFalse()
        val stored = PinRules.encode("4821", "salty")
        assertThat(stored).doesNotContain("4821")
        assertThat(PinRules.verify("4821", stored)).isTrue()
        assertThat(PinRules.verify("4822", stored)).isFalse()
        assertThat(PinRules.verify("4821", "garbage")).isFalse()
        assertThat(PinRules.hash("1", "a")).isNotEqualTo(PinRules.hash("1", "b"))
    }

    @Test fun `pin lockout after too many failures`() {
        assertThat(PinRules.lockoutRemainingMs(4, 1_000, 1_000)).isEqualTo(0)
        assertThat(PinRules.lockoutRemainingMs(5, 1_000, 11_000)).isEqualTo(20_000)
        assertThat(PinRules.lockoutRemainingMs(6, 1_000, 99_000)).isEqualTo(0)
    }

    @Test fun `rename keeps the extension and rejects bad names`() {
        assertThat(FileNameRules.rename("clip.mp4", "Holiday")).isEqualTo(FileNameRules.Result.Ok("Holiday.mp4"))
        assertThat(FileNameRules.rename("clip.mp4", " Holiday.MP4 ")).isEqualTo(FileNameRules.Result.Ok("Holiday.MP4"))
        assertThat(FileNameRules.rename("clip.mp4", "a.b")).isEqualTo(FileNameRules.Result.Ok("a.b.mp4"))
        assertThat(FileNameRules.rename("noext", "new")).isEqualTo(FileNameRules.Result.Ok("new"))
        listOf("", "  ", "a/b", "..", ".hidden", "what?", "x".repeat(300)).forEach {
            assertThat(FileNameRules.rename("clip.mp4", it)).isInstanceOf(FileNameRules.Result.Error::class.java)
        }
        assertThat(FileNameRules.rename("clip.mp4", "clip")).isInstanceOf(FileNameRules.Result.Error::class.java)
        assertThat(FileNameRules.stem("a.b.mkv")).isEqualTo("a.b")
        assertThat(FileNameRules.stem("plain")).isEqualTo("plain")
    }

    @Test fun `field codec escapes and round trips`() {
        val tricky = listOf("a\tb", "c\nd", "back\\slash", "cr\rhere", "", "plain")
        val raw = FieldCodec.encode(listOf(tricky, listOf("x")))
        assertThat(raw.lines()).hasSize(2)
        assertThat(FieldCodec.decode(raw)).containsExactly(tricky, listOf("x")).inOrder()
        assertThat(FieldCodec.unescape("odd\\q")).isEqualTo("odd\\q")
        assertThat(FieldCodec.decode("   ")).isEmpty()
    }
}
