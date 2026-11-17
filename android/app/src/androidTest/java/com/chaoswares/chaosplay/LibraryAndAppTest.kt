package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Library actions, playlists, settings persistence and background play. */
@RunWith(AndroidJUnit4::class)
class LibraryAndAppTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launch() {
        T.useTestPrefs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("library") { compose.exists("All videos") }
        compose.openTestFolder()
    }

    @After
    fun close() {
        scenario.closePlayer(compose)
        scenario.close()
    }

    private fun longPress(title: String) {
        compose.openTestFolder()
        compose.reveal(title)
        compose.waitFor(title) { compose.exists(title) }
        compose.onAllNodesWithText(title).onLast().performTouchInput { longClick() }
        compose.waitFor("action sheet") { compose.exists("Add to playlist") }
    }

    /** Taps the system consent button (Android 11+ MediaStore write/delete requests). */
    private fun allowSystemDialog() {
        val button = device.wait(Until.findObject(By.text(Pattern.compile("(?i)^(allow|delete|move to trash|ok)$"))), 15_000)
        button?.click()
    }

    @Test
    fun playlistCreateAddPlayAndRemove() {
        val name = "Road trip ${System.currentTimeMillis() % 10_000}"
        longPress("Bravo Bars.mp4")
        compose.clickText("Add to playlist")
        compose.clickText("New playlist…")
        compose.onAllNodesWithText("Name").onLast().performTextInput(name)
        compose.clickText("Create")
        longPress("Charlie Fractal.mp4")
        compose.clickText("Add to playlist")
        compose.clickText(name)
        compose.clickText("Playlists")
        compose.waitFor("playlist card") { compose.exists(name) && compose.exists("2 items") }
        compose.clickText(name)
        compose.clickText("Play all")
        val d = compose.awaitRealPlayback(title = "Bravo")
        assertThat(d.queueSize).isEqualTo(2)
        scenario.pressBack()
        compose.waitFor("player closed") { !T.diag().active }
        compose.waitFor("playlist detail") { compose.exists("Play all") }
        compose.clickDesc("Playlist options")
        compose.clickText("Delete playlist")
        compose.clickText("Delete")
        compose.waitFor("playlist gone") { !compose.exists(name) }
    }

    @Test
    fun playNextQueuesAfterTheTappedVideo() {
        longPress("Delta Tracks.mkv")
        compose.clickText("Play next")
        compose.waitFor("up next banner") { compose.exists("Up next: 1 video") }
        compose.playVideo("Alpha Clip.mp4")
        val d = compose.awaitRealPlayback(title = "Alpha", minPositionMs = 300)
        compose.showControls()
        compose.clickDesc("Next")
        compose.waitFor("Delta plays next") { T.diag().title.contains("Delta") && T.diag().queueIndex == d.queueIndex + 1 }
    }

    @Test
    fun hideAndUnhideWithPrivacyPin() {
        longPress("Charlie Fractal.mp4")
        compose.clickText("Hide")
        compose.waitFor("hidden from library") { !compose.exists("Charlie Fractal.mp4") }
        compose.clickText("Settings")
        compose.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Hidden videos (privacy)"))
        compose.clickText("Hidden videos (privacy)")
        if (compose.exists("Create a privacy PIN")) {
            compose.onAllNodesWithText("PIN").onLast().performTextInput("2468")
            compose.clickText("OK")
        } else {
            compose.onAllNodesWithText("PIN").onLast().performTextInput("1111")
            compose.clickText("OK")
            compose.waitFor("wrong PIN message") { compose.exists("Wrong PIN") }
            compose.onAllNodesWithText("PIN").onLast().performTextReplacement("2468")
            compose.clickText("OK")
        }
        compose.waitFor("hidden list") { compose.exists("Charlie Fractal.mp4") }
        compose.clickText("Unhide")
        device.pressBack()
        compose.clickText("Library")
        compose.openTestFolder()
        compose.reveal("Charlie Fractal.mp4")
        compose.waitFor("back in library") { compose.exists("Charlie Fractal.mp4") }
    }

    @Test
    fun renameAndDeleteThroughSystemConsent() {
        // Echo sits below the fold in a 9-video grid: bring it up with search
        compose.clickDesc("Search")
        compose.onAllNodesWithText("Search videos").onLast().performTextInput("Echo")
        compose.waitFor("Echo found", 20_000) { compose.exists("Echo Disposable.mp4") }
        longPress("Echo Disposable.mp4")
        compose.clickText("Rename")
        compose.onAllNodesWithText("Echo Disposable").onLast().performTextReplacement("Echo Renamed")
        compose.clickText("Rename")
        allowSystemDialog()
        compose.waitFor("renamed in library", 30_000) { compose.exists("Echo Renamed.mp4") }
        longPress("Echo Renamed.mp4")
        compose.clickText("Delete")
        compose.clickText("Delete")
        allowSystemDialog()
        compose.waitFor("deleted from library", 30_000) { !compose.exists("Echo Renamed.mp4") }
    }

    @Test
    fun settingsPersistAcrossRestart() {
        compose.clickText("Settings")
        compose.clickText("Seek step", substring = true)
        compose.clickText("30 s")
        compose.waitFor("row shows 30 s") { compose.exists("30 s") }
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.clickText("Settings")
        compose.waitFor("30 s survives restart") { compose.exists("30 s") }
        // Put it back so other tests keep the 10 s default
        compose.clickText("Seek step", substring = true)
        compose.clickText("10 s")
    }

    @Test
    fun homePausesWithoutBackgroundPlayAndKeepsPlayingWithIt() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        device.pressHome()
        compose.waitFor("paused after Home", 15_000) { !T.diag().isPlaying }
        reopen()
        compose.showControls()
        compose.clickDesc("More")
        compose.clickText("Background play: off")
        compose.showControls()
        compose.clickDesc("Play")
        compose.waitFor("playing again") { T.diag().isPlaying }
        device.pressHome()
        Thread.sleep(3_000)
        val at = T.diag().positionMs
        compose.waitFor("still playing in background", 15_000) { T.diag().isPlaying && T.diag().positionMs > at + 1_500 }
        val sessions = T.shell("dumpsys media_session")
        assertThat(sessions).contains("chaosplay")
        reopen()
        compose.showControls()
        compose.clickDesc("More")
        compose.clickText("Background play: on")
    }

    private fun reopen() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        T.shell("am start -n $pkg/com.chaoswares.chaosplay.MainActivity")
        compose.waitFor("app back in front") { compose.existsDesc("Video player") }
    }

    @Suppress("unused")
    private fun clickFirstDesc(desc: String) = compose.onAllNodesWithContentDescription(desc).onLast().performClick()
}
