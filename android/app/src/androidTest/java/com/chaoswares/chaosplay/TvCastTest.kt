package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Play on TV" against a mock DLNA TV on the LAN (mocktv.py on the test
 * host, port 8768). The mock behaves like a TV: it is found by SSDP, takes
 * SetAVTransportURI / Play / Pause / Seek / Stop, and fetches the video
 * from the phone's built-in server with Range requests.
 */
@RunWith(AndroidJUnit4::class)
class TvCastTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val tv get() = "http://${T.HOST}:8768"

    private fun get(url: String): Pair<Int, String> = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 4_000
            readTimeout = 4_000
            val code = responseCode
            code to (if (code < 400) inputStream else errorStream)?.bufferedReader()?.readText().orEmpty()
        }
    }.getOrDefault(-1 to "")

    private fun tvState(): JSONObject = JSONObject(get("$tv/debug/state").second.ifBlank { "{}" })

    @Before
    fun launch() {
        Assume.assumeTrue("mock TV not reachable", get("$tv/debug/reset").first == 200)
        T.useTestPrefs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("library") { compose.exists("All videos") }
    }

    @After
    fun close() {
        if (::scenario.isInitialized) {
            scenario.closePlayer(compose)
            scenario.close()
        }
    }

    @Test
    fun localVideoPlaysOnDlnaTvWithRemoteControl() {
        compose.playVideo("Bravo Bars.mp4")
        compose.awaitRealPlayback(title = "Bravo", minPositionMs = 300)
        compose.showControls()
        compose.clickDesc("Play on TV")
        compose.waitFor("mock TV discovered", 30_000) { compose.exists("Mock TV") }
        compose.clickText("Mock TV")
        compose.waitFor("TV playing", 30_000) { T.diag().tvState == "PLAYING" }
        assertThat(T.diag().tvDevice).isEqualTo("Mock TV")
        assertThat(T.diag().isPlaying).isFalse() // the phone paused itself

        // The TV fetched real bytes from the phone, with seeking (Range) support
        compose.waitFor("TV fetched the video from the phone", 20_000) {
            val f = tvState().getJSONArray("fetches")
            (0 until f.length()).count { f.getJSONObject(it).optInt("status") == 206 && f.getJSONObject(it).optInt("bytes") > 0 } >= 2
        }
        val state = tvState()
        val uri = state.getString("uri")
        assertThat(uri).startsWith("http://")
        assertThat(state.getJSONArray("fetches").getJSONObject(0).optString("type")).isEqualTo("video/mp4")
        // Anything but the random token is refused
        val token = uri.split('/')[3]
        assertThat(get(uri.replace(token, "a".repeat(32))).first).isEqualTo(404)

        compose.clickDesc("Pause TV")
        compose.waitFor("TV paused") { tvState().getString("transport") == "PAUSED_PLAYBACK" && T.diag().tvState == "PAUSED_PLAYBACK" }
        compose.clickDesc("TV forward 10 s")
        compose.waitFor("TV seek sent") { tvState().getJSONArray("actions").toString().contains("Seek") }
        compose.clickText("Stop and continue on phone")
        compose.waitFor("back on the phone") { T.diag().tvDevice.isEmpty() && T.diag().active }
        compose.waitFor("TV told to stop") { tvState().getString("transport") == "STOPPED" }
        // The phone stops sharing once casting ends
        compose.waitFor("phone server closed", 10_000) { get(uri).first != 206 && get(uri).first != 200 }
    }
}
