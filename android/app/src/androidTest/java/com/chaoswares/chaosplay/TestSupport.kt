package com.chaoswares.chaosplay

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.chaoswares.chaosplay.playback.PlaybackDiagnostics

/** Shared helpers for the on-device tests. */
object T {

    /**
     * Test-friendly player settings: portrait (rotating costs the software
     * GPU several seconds) and controls that stay up for 8 s.
     */
    fun useTestPrefs() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        com.chaoswares.chaosplay.data.SettingsStore(ctx).setPlayerPrefsRaw(
            com.chaoswares.chaosplay.player.PlayerPrefs.encode(
                com.chaoswares.chaosplay.player.PlayerPrefs(autoHideMs = 8_000L, landscapeForWideVideo = false, autoPlayNext = false)
            )
        )
        // The first-launch welcome is covered by OnboardingTest; other tests skip it
        com.chaoswares.chaosplay.data.SettingsStore(ctx).setOnboardingDone(true)
        // No leftovers from an earlier failed run
        com.chaoswares.chaosplay.data.LibraryMetaStore(ctx).unhideAll()
    }

    /**
     * Machine running the test servers: the emulator's host alias by
     * default, or the LAN address passed with `-e host 192.168.x.y` when
     * the tests run on a real phone.
     */
    val HOST: String = InstrumentationRegistry.getArguments().getString("host") ?: "10.0.2.2"

    fun permissions(): GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun diag(): PlaybackDiagnostics.Snapshot = PlaybackDiagnostics.state.value

    fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }
}

fun ComposeTestRule.waitFor(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
    var last: Throwable? = null
    try {
        waitUntil(timeoutMs) {
            runCatching(condition).onFailure { last = it }.getOrDefault(false)
        }
    } catch (e: Throwable) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $what. Last error: $last. Player: ${T.diag()}", e)
    }
}

fun ComposeTestRule.exists(text: String): Boolean =
    onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.existsDesc(desc: String): Boolean =
    onAllNodesWithContentDescription(desc, substring = true).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.clickText(text: String, substring: Boolean = false) {
    waitFor("text '$text'") { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    onAllNodesWithText(text, substring = substring).onLast().performClick()
}

fun ComposeTestRule.clickDesc(desc: String, substring: Boolean = false) {
    waitFor("node '$desc'") { onAllNodesWithContentDescription(desc, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    onAllNodesWithContentDescription(desc, substring = substring).onLast().performClick()
}

fun ComposeTestRule.node(text: String): SemanticsNodeInteraction = onAllNodesWithText(text).onLast()

/**
 * Waits until the player has really started: a new session, the first
 * video frame rendered and the position moving. This is the black-screen
 * check: a black screen means no frame is ever rendered.
 */
fun ComposeTestRule.awaitRealPlayback(previousSession: Int = -1, title: String? = null, minPositionMs: Long = 1_200L): PlaybackDiagnostics.Snapshot {
    waitFor("playback session newer than $previousSession${title?.let { " for $it" } ?: ""}", 60_000) {
        val d = T.diag()
        d.active && d.sessionId > previousSession && (title == null || d.title.contains(title))
    }
    waitFor("first video frame", 60_000) { T.diag().firstFrameRendered }
    val start = T.diag().positionMs
    waitFor("position to advance past ${start + minPositionMs}", 60_000) {
        val d = T.diag()
        d.isPlaying && d.positionMs >= start + minPositionMs
    }
    return T.diag()
}

/**
 * Opens the ChaosTest folder so tests only see their own clips, also on a
 * real phone with its own videos.
 */
fun ComposeTestRule.openTestFolder() {
    if (existsDesc("Back to folders")) return
    waitFor("library chips") { exists("Folders") }
    onAllNodesWithText("Folders").onFirst().performClick()
    waitFor("folder list") { onAllNodes(hasTestTag("folder_list")).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasTestTag("folder_list")).performScrollToNode(hasText("ChaosTest"))
    onAllNodesWithText("ChaosTest").onFirst().performClick()
    waitFor("inside ChaosTest") { existsDesc("Back to folders") }
}

/** Scrolls the video grid/list until [title] is on screen. */
fun ComposeTestRule.reveal(title: String) {
    waitFor("video list") { onAllNodes(hasTestTag("video_list")).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasTestTag("video_list")).performScrollToNode(hasText(title))
}

fun ComposeTestRule.playVideo(title: String) {
    openTestFolder()
    reveal(title)
    clickText(title)
}

/** Shows the player controls if the auto-hide removed them. */
fun ComposeTestRule.showControls() {
    // The emulator's software GPU can take seconds per frame, so retry
    // instead of assuming the controls are drawn before they auto-hide.
    repeat(4) {
        if (!T.diag().controlsVisible) {
            onAllNodesWithContentDescription("Video player").onLast().performClick()
            waitFor("controls toggled on in the view model", 15_000) { T.diag().controlsVisible }
        }
        val shown = runCatching { waitFor("player controls on screen", 12_000) { existsDesc("Lock") } }.isSuccess
        if (shown) return
    }
    throw AssertionError("Player controls never appeared. Player: ${T.diag()}")
}

/** Back until the player is gone (a first Back may only close an open sheet). */
fun <A : android.app.Activity> ActivityScenario<A>.closePlayer(compose: ComposeTestRule) {
    repeat(4) {
        if (!T.diag().active) return
        pressBack()
        runCatching { compose.waitFor("player closed", 8_000) { !T.diag().active } }
    }
    compose.waitFor("player closed") { !T.diag().active }
}

fun <A : android.app.Activity> ActivityScenario<A>.pressBack() {
    onActivity { (it as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed() }
}

@Suppress("unused")
private val keepImports = listOf(hasText(""), hasContentDescription(""))
