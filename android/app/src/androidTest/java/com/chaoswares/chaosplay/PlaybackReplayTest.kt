package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Regression test for the reported bug: "when I play a video, go back, and
 * then play the same or another video, it does not play, I see only a black
 * screen". Every round must start a NEW player session that renders a frame
 * and advances.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackReplayTest {

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
    }

    @After
    fun close() {
        scenario.close()
    }

    private fun backToLibrary() {
        scenario.pressBack()
        compose.waitFor("player to close") { !T.diag().active }
        compose.waitFor("library visible") { compose.exists("All videos") }
    }

    @Test
    fun sameVideoPlaysEveryTimeAfterGoingBack() {
        var session = -1
        repeat(3) { round ->
            compose.playVideo("Alpha Clip.mp4")
            val d = compose.awaitRealPlayback(previousSession = session, title = "Alpha Clip")
            assertThat(d.firstFrameRendered).isTrue()
            assertThat(d.sessionId).isGreaterThan(session)
            assertThat(d.error).isNull()
            session = d.sessionId
            backToLibrary()
            assertThat(T.diag().active).isFalse()
            println("round ${round + 1} ok, session $session")
        }
    }

    @Test
    fun differentVideosPlayAfterGoingBack() {
        var session = -1
        for (title in listOf("Bravo Bars.mp4", "Charlie Fractal.mp4", "Delta Tracks.mkv", "Bravo Bars.mp4")) {
            compose.playVideo(title)
            val d = compose.awaitRealPlayback(previousSession = session, title = title.substringBefore('.'))
            assertThat(d.videoWidth).isGreaterThan(0)
            session = d.sessionId
            backToLibrary()
        }
    }

    @Test
    fun reopeningImmediatelyWhileTheOldPlayerFadesOutStillPlays() {
        compose.playVideo("Bravo Bars.mp4")
        val first = compose.awaitRealPlayback(title = "Bravo")
        // Back and re-open without waiting for the exit animation to finish
        scenario.pressBack()
        compose.waitFor("library") { compose.exists("Charlie Fractal.mp4") }
        compose.playVideo("Bravo Bars.mp4")
        val second = compose.awaitRealPlayback(previousSession = first.sessionId, title = "Bravo")
        assertThat(second.sessionId).isGreaterThan(first.sessionId)
        backToLibrary()
    }

    @Test
    fun goingBackStopsSoundAndSavesResumePoint() {
        compose.playVideo("Charlie Fractal.mp4")
        compose.awaitRealPlayback(title = "Charlie", minPositionMs = 4_000)
        backToLibrary()
        assertThat(T.diag().isPlaying).isFalse()
        // The resume point shows up as a Continue watching card
        compose.waitFor("continue watching row") { compose.exists("Continue watching") }
    }
}
