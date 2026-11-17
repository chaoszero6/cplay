package com.chaoswares.chaosplay

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaoswares.chaosplay.data.SettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** First launch, spec Flow E: slides (skippable) -> permission with reason -> quick setup -> library. */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(T.permissions()).around(compose)

    @Test
    fun welcomeSkipPermissionSetupThenLibrary() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SettingsStore(ctx).setOnboardingDone(false) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitFor("welcome slide") { compose.exists("Plays almost anything") }
        compose.clickText("Next")
        compose.waitFor("second slide") { compose.exists("Your server, your network") }
        compose.clickText("Skip")
        compose.waitFor("permission reason") { compose.exists("Show your videos") }
        compose.clickText("Allow access") // already granted by the rule: returns straight away
        compose.waitFor("quick setup") { compose.exists("Quick setup") }
        compose.clickText("தமிழ்")
        compose.clickText("Start watching")
        compose.waitFor("library after welcome") { compose.exists("All videos") }
        val store = SettingsStore(ctx)
        runBlocking {
            assertThat(store.onboardingDone.first()).isTrue()
            assertThat(store.preferredSubtitleLanguage.first()).isEqualTo("ta")
            store.setPreferredSubtitleLanguage("en")
        }
        scenario.close()
    }
}
