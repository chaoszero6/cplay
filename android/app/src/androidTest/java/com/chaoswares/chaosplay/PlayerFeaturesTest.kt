package com.chaoswares.chaosplay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Player features on a real device: queue, tracks, subtitles, bookmarks, lock, speed. */
@RunWith(AndroidJUnit4::class)
class PlayerFeaturesTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        T.useTestPrefs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("library") { compose.exists("All videos") }
        compose.openTestFolder()
        // Title order keeps the queue order predictable
        compose.clickDesc("Sort:", substring = true)
        compose.clickText("Title (A-Z)")
    }

    @After
    fun close() {
        scenario.closePlayer(compose)
        scenario.close()
    }

    @Test
    fun nextAndPreviousWalkTheLibraryList() {
        compose.playVideo("Bravo Bars.mp4")
        val first = compose.awaitRealPlayback(title = "Bravo")
        assertThat(first.queueSize).isAtLeast(4) // every video in the sorted list
        compose.showControls()
        compose.clickDesc("Next")
        compose.waitFor("next item (Charlie)") { T.diag().title.contains("Charlie") && T.diag().firstFrameRendered }
        compose.awaitRealPlayback(previousSession = first.sessionId - 1, title = "Charlie", minPositionMs = 500)
        assertThat(T.diag().queueIndex).isEqualTo(first.queueIndex + 1)
        compose.showControls()
        compose.clickDesc("Previous") // within 3 s: restart; else go back an item
        compose.showControls()
        compose.clickDesc("Previous")
        compose.waitFor("back to Bravo") { T.diag().title.contains("Bravo") }
    }

    @Test
    fun embeddedTracksAndTamilSubtitleSwitchDuringPlayback() {
        compose.playVideo("Delta Tracks.mkv")
        compose.awaitRealPlayback(title = "Delta", minPositionMs = 500)
        compose.waitFor("2 audio + 2 subtitle tracks") { T.diag().audioTracks == 2 && T.diag().subtitleTracks >= 2 }
        // The test clip's cues run 0.5-20 s; restart after each switch so a
        // slow emulator cannot drift past them before the text is checked.
        fun choose(track: String) {
            compose.showControls()
            compose.clickDesc("Subtitles")
            compose.clickText(track)
            compose.clickText("Done")
            compose.waitFor("sheet closed") { !compose.exists("Add subtitle file") }
            compose.showControls()
            compose.clickDesc("Previous") // past 3 s: restart this video
            compose.waitFor("restarted") { T.diag().positionMs < 15_000 && T.diag().title.contains("Delta") }
        }
        choose("Tamil (Embedded)")
        compose.waitFor("Tamil subtitle text on screen") { T.diag().subtitleText.contains("தமிழ்") }
        choose("English (Embedded)")
        compose.waitFor("English subtitle text on screen") { T.diag().subtitleText.contains("English embedded line") }
        compose.showControls()
        compose.clickDesc("Subtitles")
        compose.clickText("Off")
        compose.waitFor("subtitles off") { T.diag().subtitleText.isEmpty() }
        compose.clickText("Done")
    }

    @Test
    fun sidecarSubtitleLoadsAndDelayShiftsIt() {
        compose.playVideo("Alpha Clip.mp4")
        compose.awaitRealPlayback(title = "Alpha", minPositionMs = 500)
        compose.waitFor("sidecar Alpha Clip.srt shown") { T.diag().subtitleText.contains("Hello from the sidecar subtitle") }
    }

    @Test
    fun speedSheetChangesRealPlaybackSpeed() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        compose.showControls()
        compose.clickDesc("Speed")
        compose.clickText("1.5x")
        compose.waitFor("speed 1.5") { T.diag().speed == 1.5f }
        compose.clickText("Reset")
        compose.waitFor("speed 1.0") { T.diag().speed == 1.0f }
        compose.clickText("Done")
    }

    @Test
    fun bookmarkIsSavedAndListed() {
        compose.playVideo("Charlie Fractal.mp4")
        compose.awaitRealPlayback(title = "Charlie", minPositionMs = 1_500)
        compose.showControls()
        compose.clickDesc("More")
        compose.clickText("Bookmarks", substring = true)
        compose.clickText("Add at", substring = true)
        compose.waitFor("bookmark row") { compose.exists("Bookmark at") }
        compose.clickDesc("Delete bookmark")
        compose.clickText("Done")
    }

    @Test
    fun lockBlocksControlsAndFourCornersUnlock() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        compose.showControls()
        compose.clickDesc("Lock")
        compose.waitFor("locked overlay") { compose.exists("Touch the four corners") }
        assertThat(compose.existsDesc("Speed")).isFalse()
        val player = compose.onAllNodesWithContentDescription("Video player").onLast()
        listOf(0.04f to 0.04f, 0.96f to 0.04f, 0.96f to 0.96f, 0.04f to 0.96f).forEach { (fx, fy) ->
            player.performTouchInput { click(Offset(width * fx, height * fy)) }
            Thread.sleep(450) // longer than the double-tap window
        }
        compose.waitFor("unlocked") { !compose.exists("Touch the four corners") }
        assertThat(T.diag().isPlaying).isTrue()
    }

    /**
     * Regression: a brightness swipe used to show the volume (speaker)
     * indicator, because starting a drag set both levels.
     */
    @Test
    fun brightnessSwipeShowsBrightnessAndVolumeSwipeShowsVolume() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        val player = compose.onAllNodesWithContentDescription("Video player").onLast()
        player.performTouchInput {
            down(Offset(width * 0.2f, height * 0.6f))
            repeat(6) { moveBy(Offset(0f, -height * 0.03f)) }
        }
        compose.waitFor("brightness indicator only") { T.diag().indicator == "brightness" }
        player.performTouchInput { up() }
        compose.waitFor("indicator cleared") { T.diag().indicator.isEmpty() }
        player.performTouchInput {
            down(Offset(width * 0.8f, height * 0.6f))
            repeat(4) { moveBy(Offset(0f, -height * 0.02f)) }
            repeat(4) { moveBy(Offset(0f, height * 0.02f)) } // back down: leave the phone's volume as it was
        }
        compose.waitFor("volume indicator only") { T.diag().indicator == "volume" }
        player.performTouchInput { up() }
    }

    @Test
    fun kidsLockShowsTouchEffects() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        compose.showControls()
        compose.clickDesc("Lock")
        compose.waitFor("locked") { T.diag().locked }
        compose.onAllNodesWithContentDescription("Video player").onLast()
            .performTouchInput { click(Offset(width * 0.5f, height * 0.4f)) }
        compose.waitFor("colourful touch effect") { compose.existsDesc("Touch effect") }
        // A tap in the middle is not a corner: still locked, video still playing
        assertThat(T.diag().locked).isTrue()
        assertThat(T.diag().isPlaying).isTrue()
    }

    @Test
    fun pictureInPictureKeepsPlaying() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        compose.showControls()
        compose.clickDesc("Picture in picture")
        var inPip = false
        compose.waitFor("PiP mode") {
            scenario.onActivity { inPip = it.isInPictureInPictureMode }
            inPip
        }
        val at = T.diag().positionMs
        compose.waitFor("still playing in PiP") { T.diag().positionMs > at + 1_000 }
        // Bring it back full screen
        T.shell("am start -n ${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName}/com.chaoswares.chaosplay.MainActivity")
        compose.waitFor("left PiP") {
            scenario.onActivity { inPip = it.isInPictureInPictureMode }
            !inPip
        }
    }
}
