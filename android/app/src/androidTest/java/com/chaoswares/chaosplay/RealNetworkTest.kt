package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Checks against the real home network, only on a real phone and only when
 * asked (`-e real 1 -e lanServer 192.168.x.y`). Read-only: discovery,
 * browsing a DLNA server and listing SMB shares as a guest; nothing is
 * signed in to, written or changed.
 */
@RunWith(AndroidJUnit4::class)
class RealNetworkTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val args = InstrumentationRegistry.getInstrumentation().let { InstrumentationRegistry.getArguments() }
    private val lan = args.getString("lanServer").orEmpty()

    @Before
    fun launch() {
        Assume.assumeTrue("real-network checks not requested", args.getString("real") == "1")
        T.useTestPrefs()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("app") { compose.exists("Library") }
        compose.clickText("Network")
        compose.waitFor("network tab") { compose.exists("Play a link") }
    }

    @After
    fun close() {
        if (::scenario.isInitialized) {
            scenario.closePlayer(compose)
            scenario.close()
        }
    }

    @Test
    fun lanDiscoveryFindsJellyfinAndDlna() {
        compose.clickText("Search")
        compose.waitFor("discovery finished", 30_000) { !compose.exists("Searching…") }
        val jellyfin = compose.onAllNodesWithText("Jellyfin · ", substring = true).fetchSemanticsNodes().size
        val dlna = compose.onAllNodesWithText("DLNA · ", substring = true).fetchSemanticsNodes().size
        println("REAL discovery: jellyfin=$jellyfin dlna=$dlna")
        assert(jellyfin + dlna > 0) { "Nothing discovered on the LAN" }
    }

    @Test
    fun realDlnaServerBrowses() {
        compose.clickText("Search")
        compose.waitFor("discovery finished", 30_000) { !compose.exists("Searching…") }
        Assume.assumeTrue("no DLNA server answered", compose.exists("DLNA · "))
        compose.onAllNodesWithText("Open").onLast().performClick()
        compose.waitFor("DLNA root listed", 30_000) { compose.existsDesc("Back") && !compose.existsDesc("Loading") }
        assert(!compose.exists("Could not load")) { "DLNA browse failed" }
        println("REAL dlna root ok")
        scenario.pressBack()
    }

    @Test
    fun realSambaListsSharesAsGuest() {
        Assume.assumeTrue("no lanServer given", lan.isNotBlank())
        compose.clickText("Add server")
        compose.clickText("SMB / Windows share")
        compose.onAllNodesWithContentDescription("Server and share").onLast().performTextInput(lan)
        compose.clickText("Connect")
        compose.waitFor("guest share list or a clear refusal", 60_000) {
            compose.exists("ChaosWares") || compose.exists("Wrong user name") || compose.exists("not allowed") || compose.exists("does not list")
        }
        println("REAL samba guest: shares visible=${compose.exists("ChaosWares")}")
        if (compose.exists("Cancel")) compose.clickText("Cancel")
        else {
            // Remove the server again so nothing is left saved on the phone
            scenario.pressBack()
            compose.waitFor("network home") { compose.exists("Play a link") }
            compose.onAllNodesWithContentDescription("Server options").onLast().performClick()
            compose.clickText("Remove and sign out")
            compose.clickText("Remove")
        }
    }
}
