package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network playback against local test servers on the host (see the
 * scratch testservers.py): a static HTTP/HLS server on :8765, a
 * Nextcloud-layout WebDAV server on :8766 (test / secret) and a mock
 * Jellyfin on :18096 (test / secret).
 */
@RunWith(AndroidJUnit4::class)
class NetworkPlaybackTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        T.useTestPrefs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("app") { compose.exists("Library") }
        compose.clickText("Network")
        compose.waitFor("network tab") { compose.exists("Play a link") }
    }

    @After
    fun close() {
        scenario.closePlayer(compose)
        scenario.close()
    }

    private fun playLink(url: String) {
        compose.onAllNodesWithContentDescription("Video link").onLast().performTextReplacement(url)
        compose.clickText("Play")
    }

    @Test
    fun httpVideoLinkPlays() {
        playLink("http://${T.HOST}:8765/Bravo%20Bars.mp4")
        val d = compose.awaitRealPlayback(title = "Bravo Bars")
        assertThat(d.uri).startsWith("http://")
        assertThat(d.durationMs).isGreaterThan(30_000L)
    }

    @Test
    fun hlsStreamPlays() {
        playLink("http://${T.HOST}:8765/hls/index.m3u8")
        compose.awaitRealPlayback()
    }

    @Test
    fun badLinkShowsFriendlyError() {
        playLink("http://${T.HOST}:8765/missing.mp4")
        compose.waitFor("error message") { T.diag().error != null }
        assertThat(T.diag().error).contains("404")
    }

    @Test
    fun nextcloudSignInBrowseAndPlayWithSidecarSubtitle() {
        removeServerIfPresent("Nextcloud")
        compose.clickText("Add server")
        compose.clickText("Nextcloud")
        compose.onAllNodesWithContentDescription("Nextcloud address").onLast().performTextInput("http://${T.HOST}:8766")
        compose.onAllNodesWithContentDescription("User name").onLast().performTextInput("test")
        // Wrong password first: must say so clearly
        compose.onAllNodesWithContentDescription("Password").onLast().performTextInput("nope")
        compose.clickText("Connect")
        compose.waitFor("wrong password message") { compose.exists("Wrong user name or password") }
        compose.onAllNodesWithContentDescription("Password").onLast().performTextReplacement("secret")
        compose.clickText("Connect")
        compose.waitFor("Nextcloud root listing") { compose.exists("Movies") }
        compose.clickText("Movies")
        compose.waitFor("Movies folder") { compose.exists("Charlie Fractal.mp4") }
        assertThat(compose.exists("notes.txt")).isFalse() // non-media hidden by default
        assertThat(compose.exists("1 subtitle file")).isTrue()
        compose.clickText("Charlie Fractal.mp4")
        compose.awaitRealPlayback(title = "Charlie")
        compose.waitFor("Nextcloud sidecar subtitle") { T.diag().subtitleText.contains("Nextcloud sidecar line") }
    }

    @Test
    fun jellyfinSignInResumeAndProgressReporting() {
        removeServerIfPresent("Mock Jellyfin")
        compose.clickText("Add server")
        compose.clickText("Jellyfin")
        compose.onAllNodesWithContentDescription("Server address").onLast().performTextInput("http://${T.HOST}:18096")
        compose.onAllNodesWithContentDescription("User name").onLast().performTextInput("test")
        compose.onAllNodesWithContentDescription("Password").onLast().performTextInput("secret")
        compose.clickText("Sign in")
        compose.waitFor("Jellyfin home") { compose.exists("Continue watching") && compose.exists("Libraries") }
        // First match = the Continue watching card at the top (the Recently
        // added copy is below the fold)
        compose.waitFor("movie card") { compose.exists("Bravo Movie (2024)") }
        compose.onAllNodesWithText("Bravo Movie (2024)").onFirst().performClick()
        compose.clickText("Resume from", substring = true)
        val d = compose.awaitRealPlayback(title = "Bravo Movie")
        // Resumed from the server's 12 s position, not from zero
        assertThat(d.positionMs).isAtLeast(12_000L)
        assertThat(d.uri).startsWith("jellyfin://")
        compose.waitFor("server subtitle offered and auto-selected") { T.diag().subtitleText.contains("Jellyfin server subtitle") }
        scenario.pressBack()
        compose.waitFor("player closed") { !T.diag().active }
        // The server received start and stop reports with a position
        compose.waitFor("playback reports on the server", 20_000) {
            val reports = get("http://${T.HOST}:18096/debug/reports")
            reports.contains("/Sessions/Playing\"") && reports.contains("/Sessions/Playing/Stopped")
        }
    }

    /** Starts each sign-in test from a clean list of saved servers. */
    @Suppress("UNUSED_PARAMETER")
    private fun removeServerIfPresent(name: String) {
        var guard = 0
        while (compose.existsDesc("Server options") && guard++ < 6) {
            val before = compose.onAllNodesWithContentDescription("Server options").fetchSemanticsNodes().size
            compose.onAllNodesWithContentDescription("Server options").onLast().performClick()
            compose.clickText("Remove and sign out")
            compose.clickText("Remove")
            compose.waitFor("server removed") {
                compose.onAllNodesWithContentDescription("Server options").fetchSemanticsNodes().size < before
            }
        }
    }

    private fun get(url: String): String = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 3_000
            readTimeout = 3_000
            inputStream.bufferedReader().readText()
        }
    }.getOrDefault("")
}
