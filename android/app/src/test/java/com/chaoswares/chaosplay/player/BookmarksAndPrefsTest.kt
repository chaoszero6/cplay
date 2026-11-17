package com.chaoswares.chaosplay.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookmarksAndPrefsTest {

    private val uri = "content://v/1"

    @Test fun `add bookmark uses a timestamp label when blank`() {
        val list = BookmarkRules.add(emptyList(), Bookmark(uri, 65_000, "  ", 1))
        assertThat(list.single().label).isEqualTo("Bookmark at 1:05")
    }

    @Test fun `add bookmark dedupes points within the window and clamps negatives`() {
        var list = BookmarkRules.add(emptyList(), Bookmark(uri, 10_000, "a", 1))
        list = BookmarkRules.add(list, Bookmark(uri, 11_000, "b", 2))
        assertThat(list).hasSize(1)
        list = BookmarkRules.add(list, Bookmark(uri, 12_000, "c", 3))
        assertThat(list).hasSize(2)
        list = BookmarkRules.add(list, Bookmark("other", 10_000, "d", 4))
        assertThat(list).hasSize(3)
        assertThat(BookmarkRules.add(emptyList(), Bookmark(uri, -5, "x")).single().positionMs).isEqualTo(0L)
    }

    @Test fun `add bookmark drops the oldest past the cap`() {
        var list = emptyList<Bookmark>()
        for (i in 0..BookmarkRules.MAX_PER_VIDEO) {
            list = BookmarkRules.add(list, Bookmark(uri, i * 10_000L, "b$i", i.toLong()))
        }
        assertThat(list).hasSize(BookmarkRules.MAX_PER_VIDEO)
        assertThat(list.none { it.label == "b0" }).isTrue()
    }

    @Test fun `forUri sorts and remove and rename target one bookmark`() {
        var list = listOf(Bookmark(uri, 50_000, "late"), Bookmark(uri, 5_000, "early"), Bookmark("x", 1, "o"))
        assertThat(BookmarkRules.forUri(list, uri).map { it.label }).containsExactly("early", "late").inOrder()
        list = BookmarkRules.rename(list, uri, 5_000, "intro")
        assertThat(BookmarkRules.forUri(list, uri).first().label).isEqualTo("intro")
        list = BookmarkRules.rename(list, uri, 5_000, " ")
        assertThat(BookmarkRules.forUri(list, uri).first().label).isEqualTo("Bookmark at 0:05")
        list = BookmarkRules.remove(list, uri, 50_000)
        assertThat(BookmarkRules.forUri(list, uri)).hasSize(1)
    }

    @Test fun `next and previous bookmark navigation`() {
        val marks = listOf(Bookmark(uri, 10_000, "a"), Bookmark(uri, 30_000, "b"), Bookmark(uri, 60_000, "c"))
        assertThat(BookmarkRules.nextAfter(marks, 10_000)!!.positionMs).isEqualTo(30_000)
        assertThat(BookmarkRules.nextAfter(marks, 60_000)).isNull()
        assertThat(BookmarkRules.previousBefore(marks, 31_000)!!.positionMs).isEqualTo(10_000)
        assertThat(BookmarkRules.previousBefore(marks, 33_000)!!.positionMs).isEqualTo(30_000)
        assertThat(BookmarkRules.previousBefore(marks, 5_000)).isNull()
    }

    @Test fun `bookmark codec round trips and skips junk`() {
        val list = listOf(Bookmark(uri, 1, "tab\tlabel", 7), Bookmark("u2", 2, "", 0))
        assertThat(BookmarkRules.decode(BookmarkRules.encode(list))).isEqualTo(list)
        assertThat(BookmarkRules.decode("\tx\n" + "u\tnotanumber\n")).isEmpty()
    }

    @Test fun `player prefs round trip every field`() {
        val prefs = PlayerPrefs(
            defaultSpeed = 1.5f, seekStepMs = 30_000, autoHideMs = 5_000, resumeEnabled = false,
            autoPlayNext = false, backgroundPlay = true, autoPip = true, keepScreenOn = false,
            landscapeForWideVideo = false, showClockAndBattery = true,
            gestures = GestureRules.GestureSettings(
                brightnessEnabled = false, volumeEnabled = true, seekEnabled = false, doubleTapSkipEnabled = false,
                pinchZoomEnabled = false, twoFingerSpeedEnabled = false, swipeDownPipEnabled = true,
                longPressSpeedEnabled = false
            )
        )
        assertThat(PlayerPrefs.decode(PlayerPrefs.encode(prefs))).isEqualTo(prefs)
    }

    @Test fun `player prefs default, ignore junk and clamp`() {
        assertThat(PlayerPrefs.decode("")).isEqualTo(PlayerPrefs())
        assertThat(PlayerPrefs.decode("garbage;;=;bg=maybe")).isEqualTo(PlayerPrefs())
        val clamped = PlayerPrefs.decode("speed=99;seek=1;hide=60000")
        assertThat(clamped.defaultSpeed).isEqualTo(PlaybackRules.MAX_SPEED)
        assertThat(clamped.seekStepMs).isEqualTo(1_000L)
        assertThat(clamped.autoHideMs).isEqualTo(8_000L)
    }

    @Test fun `swipe down pip is opt in by default`() {
        assertThat(PlayerPrefs().gestures.swipeDownPipEnabled).isFalse()
    }

    @Test fun `seek step labels`() {
        assertThat(PlayerPrefs.seekStepLabel(10_000)).isEqualTo("10 s")
        assertThat(PlayerPrefs.seekStepLabel(60_000)).isEqualTo("1 min")
        assertThat(PlayerPrefs.seekStepLabel(90_000)).isEqualTo("90 s")
    }

    @Test fun `boost gain is zero up to 100 percent and 6 dB at 200`() {
        assertThat(AudioRules.boostGainMb(0.5f)).isEqualTo(0)
        assertThat(AudioRules.boostGainMb(1.0f)).isEqualTo(0)
        assertThat(AudioRules.boostGainMb(2.0f)).isEqualTo(602)
        assertThat(AudioRules.boostGainMb(5.0f)).isEqualTo(602)
        assertThat(AudioRules.boostGainMb(Float.NaN)).isEqualTo(0)
        assertThat(AudioRules.boostGainMb(1.5f)).isEqualTo(352)
    }

    @Test fun `split level into system volume and boost`() {
        assertThat(AudioRules.split(0.4f)).isEqualTo(0.4f to 1f)
        assertThat(AudioRules.split(1.6f)).isEqualTo(1f to 1.6f)
        assertThat(AudioRules.split(-1f)).isEqualTo(0f to 1f)
        assertThat(AudioRules.split(9f)).isEqualTo(1f to 2f)
        assertThat(AudioRules.split(Float.NaN)).isEqualTo(1f to 1f)
    }

    @Test fun `stream index rounds and clamps`() {
        assertThat(AudioRules.streamIndex(0.5f, 15)).isEqualTo(8)
        assertThat(AudioRules.streamIndex(1.5f, 15)).isEqualTo(15)
        assertThat(AudioRules.streamIndex(0.3f, 0)).isEqualTo(0)
    }

    @Test fun `eq band levels interpolate and stay in range`() {
        val flat = AudioRules.bandLevels(AudioRules.EqPreset.FLAT, 5, -1500, 1500)
        assertThat(flat).containsExactly(0, 0, 0, 0, 0)
        val bass = AudioRules.bandLevels(AudioRules.EqPreset.BASS, 5, -1500, 1500)
        assertThat(bass.first()).isEqualTo(1200)
        assertThat(bass.last()).isEqualTo(0)
        val ten = AudioRules.bandLevels(AudioRules.EqPreset.NIGHT, 10, -1000, 500)
        assertThat(ten).hasSize(10)
        assertThat(ten.all { it in -1000..500 }).isTrue()
        assertThat(ten.first()).isEqualTo(-600)
        assertThat(AudioRules.bandLevels(AudioRules.EqPreset.ROCK, 1, -100, 100)).containsExactly(50)
        assertThat(AudioRules.bandLevels(AudioRules.EqPreset.ROCK, 0, -100, 100)).isEmpty()
    }

    @Test fun `orientation cycles and picks landscape for wide video`() {
        assertThat(OrientationMode.AUTO.next()).isEqualTo(OrientationMode.LANDSCAPE)
        assertThat(OrientationMode.PORTRAIT.next()).isEqualTo(OrientationMode.AUTO)
        assertThat(OrientationMode.forVideo(1920, 1080, true)).isEqualTo(OrientationMode.LANDSCAPE)
        assertThat(OrientationMode.forVideo(1080, 1920, true)).isEqualTo(OrientationMode.AUTO)
        assertThat(OrientationMode.forVideo(1920, 1080, false)).isEqualTo(OrientationMode.AUTO)
        assertThat(OrientationMode.forVideo(0, 0, true)).isEqualTo(OrientationMode.AUTO)
    }

    @Test fun `seek preview buckets scale with duration`() {
        assertThat(SeekPreviewRules.bucketSizeMs(0)).isEqualTo(1_000L)
        assertThat(SeekPreviewRules.bucketSizeMs(60_000)).isEqualTo(1_000L)
        assertThat(SeekPreviewRules.bucketSizeMs(600_000)).isEqualTo(5_000L)
        assertThat(SeekPreviewRules.bucketSizeMs(7_200_000)).isEqualTo(10_000L)
        assertThat(SeekPreviewRules.bucket(12_345, 600_000)).isEqualTo(10_000L)
        assertThat(SeekPreviewRules.bucket(999_999, 600_000)).isEqualTo(600_000L)
        assertThat(SeekPreviewRules.bucket(-50, 600_000)).isEqualTo(0L)
    }
}
