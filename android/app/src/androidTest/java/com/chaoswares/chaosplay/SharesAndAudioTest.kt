package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * SMB, FTP, SFTP and DLNA against throwaway servers on the host
 * (shareservers.py: SMB :4445, FTP :2121, SFTP :2222, DLNA :8767, all
 * test / secret), plus AC3 / E-AC3 / DTS / TrueHD through the bundled
 * FFmpeg decoder, audio delay and volume levelling.
 */
@RunWith(AndroidJUnit4::class)
class SharesAndAudioTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        T.useTestPrefs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("app") { compose.exists("Library") }
    }

    @After
    fun close() {
        scenario.closePlayer(compose)
        scenario.close()
    }

    private fun openNetwork() {
        compose.clickText("Network")
        compose.waitFor("network tab") { compose.exists("Play a link") }
        // Clean slate: remove saved servers from earlier runs
        var guard = 0
        while (compose.existsDesc("Server options") && guard++ < 8) {
            val before = compose.onAllNodesWithContentDescription("Server options").fetchSemanticsNodes().size
            compose.onAllNodesWithContentDescription("Server options").onLast().performClick()
            compose.clickText("Remove and sign out")
            compose.clickText("Remove")
            compose.waitFor("server removed") {
                compose.onAllNodesWithContentDescription("Server options").fetchSemanticsNodes().size < before
            }
        }
    }

    private fun addServer(type: String, address: String, addressLabel: String, user: String? = "test", password: String? = "secret") {
        compose.clickText("Add server")
        compose.clickText(type)
        compose.onAllNodesWithContentDescription(addressLabel).onLast().performTextInput(address)
        if (user != null) compose.onAllNodesWithContentDescription("User name").onLast().performTextInput(user)
        if (password != null) compose.onAllNodesWithContentDescription("Password").onLast().performTextInput(password)
        compose.clickText("Connect")
    }

    private fun playFromMoviesFolder(sidecarText: String) {
        compose.waitFor("Movies folder listed", 60_000) { compose.exists("Movies") }
        compose.clickText("Movies")
        compose.waitFor("files in Movies") { compose.exists("Bravo Bars.mp4") }
        assertThat(compose.exists("notes.txt")).isFalse()
        assertThat(compose.exists("1 subtitle file")).isTrue()
        compose.clickText("Bravo Bars.mp4")
        val d = compose.awaitRealPlayback(title = "Bravo", minPositionMs = 1_500)
        assertThat(d.uri).startsWith("chaos-")
        assertThat(d.uri).doesNotContain("secret")
        compose.waitFor("sidecar subtitle from the share") { T.diag().subtitleText.contains(sidecarText) }
    }

    @Test
    fun smbListsSharesBrowsesAndPlaysWithSeeking() {
        openNetwork()
        // Wrong password first: must say so in plain words
        addServer("SMB / Windows share", "${T.HOST}:4445", "Server and share", password = "nope")
        compose.waitFor("wrong password message") { compose.exists("Wrong user name or password") }
        compose.clickText("Cancel")
        // The test server cannot enumerate shares (real Samba/Windows can, verified
        // separately): the app must explain what to do instead of a raw error
        addServer("SMB / Windows share", "${T.HOST}:4445", "Server and share")
        compose.waitFor("clear no-share-list message", 60_000) { compose.exists("does not list its shares") }
        compose.clickText("Cancel")
        addServer("SMB / Windows share", "${T.HOST}:4445/VIDEOS", "Server and share")
        playFromMoviesFolder("Share sidecar line")
        // Seek far ahead: only that range is read from the share
        compose.showControls()
        val before = T.diag().positionMs
        compose.clickDesc("Forward 10 s")
        compose.waitFor("seeked forward over SMB") { T.diag().positionMs > before + 8_000 && T.diag().isPlaying }
    }

    @Test
    fun ftpBrowsesAndPlays() {
        openNetwork()
        addServer("FTP", "ftp://${T.HOST}:2121", "Server")
        playFromMoviesFolder("Share sidecar line")
    }

    @Test
    fun sftpBrowsesAndPlays() {
        openNetwork()
        addServer("SFTP (SSH)", "${T.HOST}:2222", "Server")
        playFromMoviesFolder("Share sidecar line")
    }

    @Test
    fun dlnaBrowsesContainersAndPlaysWithServerSubtitle() {
        openNetwork()
        addServer("DLNA / UPnP", "http://${T.HOST}:8767/desc.xml", "Server description URL", user = null, password = null)
        compose.waitFor("DLNA root") { compose.exists("Videos") }
        compose.clickText("Videos")
        compose.waitFor("DLNA items") { compose.exists("Bravo over DLNA") }
        assertThat(compose.exists("Photo")).isFalse() // pictures are not offered
        compose.clickText("Bravo over DLNA")
        compose.awaitRealPlayback(title = "Bravo over DLNA")
        compose.waitFor("DLNA subtitle") { T.diag().subtitleText.contains("DLNA subtitle line") }
    }

    private fun playSurround(title: String, codec: String) {
        // Reach the clip through library search (the grid is longer than the screen)
        compose.clickDesc("Search")
        compose.onAllNodesWithText("Search videos").onLast().performTextInput(title.substringBefore(' '))
        compose.waitFor("search finds $title") { compose.exists(title) }
        compose.clickText(title)
        compose.awaitRealPlayback(title = title.substringBefore('.'))
        compose.waitFor("$codec audio decoded") { T.diag().audioDecoder.isNotBlank() }
        val d = T.diag()
        println("$title -> decoder ${d.audioDecoder}, mime ${d.audioMime}")
        assertThat(d.audioMime).contains(codec)
        assertThat(d.error).isNull()
    }

    @Test
    fun ac3PlaysThroughFfmpeg() {
        playSurround("Foxtrot AC3.mkv", "ac3")
        assertThat(T.diag().audioDecoder.lowercase()).contains("ffmpeg")
    }

    @Test
    fun eac3Plays() = playSurround("Golf EAC3.mkv", "eac3")

    @Test
    fun dtsPlaysThroughFfmpeg() {
        playSurround("Hotel DTS.mkv", "dts")
        assertThat(T.diag().audioDecoder.lowercase()).contains("ffmpeg")
    }

    @Test
    fun trueHdPlaysThroughFfmpeg() {
        playSurround("India TrueHD.mkv", "true-hd")
        assertThat(T.diag().audioDecoder.lowercase()).contains("ffmpeg")
    }

    @Test
    fun audioDelayAndVolumeLevelling() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 500)
        compose.showControls()
        compose.clickDesc("Audio")
        repeat(4) { compose.clickText("+") }
        compose.waitFor("delay +200 ms") { T.diag().audioDelayMs == 200L }
        compose.waitFor("delay shown") { compose.exists("+0.20 s") }
        val at = T.diag().positionMs
        compose.waitFor("still playing with delay") { T.diag().positionMs > at + 1_000 }
        compose.clickDesc("Audio delay") // tap the value to reset
        compose.waitFor("delay reset") { T.diag().audioDelayMs == 0L }
        compose.clickDesc("Volume levelling")
        compose.waitFor("levelling on") { T.diag().levelling }
        compose.clickText("Done")
    }
}
