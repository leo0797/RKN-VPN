package app.rkn.vpn

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.data.ProxyNode
import app.rkn.vpn.data.SubscriptionInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("rkn_vpn_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun s(id: Int): String = context.getString(id)

    private fun completeOnboardingIfShown() {
        val next = s(R.string.onboarding_next)
        val start = s(R.string.onboarding_start)
        val skip = s(R.string.onboarding_skip)
        // Onboarding shows 3 pages: Next, Next, Start (and Skip always available).
        if (composeRule.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) {
            // Prefer deterministic path through pages.
            repeat(3) {
                when {
                    composeRule.onAllNodesWithText(start).fetchSemanticsNodes().isNotEmpty() -> {
                        composeRule.onNodeWithText(start).performClick()
                        return
                    }
                    composeRule.onAllNodesWithText(next).fetchSemanticsNodes().isNotEmpty() -> {
                        composeRule.onNodeWithText(next).performClick()
                        composeRule.waitForIdle()
                    }
                    composeRule.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty() -> {
                        composeRule.onNodeWithText(skip).performClick()
                        return
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun firstLaunchOnboardingToHomeAndAddSubscriptionInput_noCrash() {
        composeRule.waitForIdle()
        completeOnboardingIfShown()

        // Navigate to Settings/More tab.
        composeRule.onNodeWithText(s(R.string.more)).performClick()
        composeRule.waitForIdle()

        // Add subscription from test input and save (refresh may fail in test env, but must not crash).
        val url = "https://example.invalid/subscription-test"
        composeRule.onNodeWithText(s(R.string.subscription_url_label)).performScrollTo().performTextClearance()
        composeRule.onNodeWithText(s(R.string.subscription_url_label)).performTextInput(url)
        composeRule.onNodeWithText(s(R.string.save_and_refresh)).performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.waitForIdle()

        // UI remains responsive.
        composeRule.onNodeWithText(s(R.string.settings_title)).assertExists()
    }

    @Test
    fun settingsSaveAndRefreshButton_isVisibleClickableAndSavesLink() {
        // Skip onboarding for this focused test.
        Prefs(context).onboardingShown = true
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(s(R.string.more)).performClick()
        composeRule.waitForIdle()

        val save = composeRule.onNodeWithText(s(R.string.save_and_refresh)).performScrollTo()
        save.assertIsDisplayed()
        save.assertHasClickAction()

        val url = "https://sub.example.com/test"
        composeRule.onNodeWithText(s(R.string.subscription_url_label)).performScrollTo().performTextClearance()
        composeRule.onNodeWithText(s(R.string.subscription_url_label)).performTextInput(url)
        save.performClick()
        composeRule.waitForIdle()

        assertEquals(url, Prefs(context).subscriptionUrl)
    }

    @Test
    fun serversTab_showsNodesStartingWithFlagEmoji() {
        val prefs = Prefs(context)
        prefs.onboardingShown = true
        prefs.nodes = listOf(
            ProxyNode(
                id = "n1",
                name = "🇩🇪 Germany",
                protocol = "vless",
                server = "de.example.com",
                port = 443,
                raw = """{"type":"vless","name":"🇩🇪 Germany","server":"de.example.com","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}"""
            ),
            ProxyNode(
                id = "n2",
                name = "🇺🇸 United States",
                protocol = "vmess",
                server = "us.example.com",
                port = 443,
                raw = """{"type":"vmess","name":"🇺🇸 United States","server":"us.example.com","server_port":443,"uuid":"22222222-2222-2222-2222-222222222222"}"""
            )
        )
        prefs.selectedId = "n1"
        prefs.subscription = SubscriptionInfo(title = "Test")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(s(R.string.servers)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("🇩🇪 Germany", substring = true).assertExists()
        composeRule.onNodeWithText("🇺🇸 United States", substring = true).assertExists()
    }
}
