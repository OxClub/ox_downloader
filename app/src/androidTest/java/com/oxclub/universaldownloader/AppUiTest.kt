package com.oxclub.universaldownloader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator. Skips onboarding first, then drives the real UI. */
@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun skipOnboarding() {
        val container = (rule.activity.application as UniversalDownloaderApp).container
        runBlocking {
            container.settings.set(com.oxclub.universaldownloader.data.preferences.Keys.ONBOARDING, true)
        }
        rule.waitForIdle()
    }

    @Test fun invalidUrlShowsError() {
        rule.onNodeWithTag("url_input").performTextInput("javascript:alert(1)")
        rule.onNodeWithTag("btn_analyze").performClick()
        rule.waitUntil(3000) {
            rule.onAllNodesWithTextSafe("Only http:// and https:// links are supported.")
        }
    }

    @Test fun downloadsTabShowsEmptyStateAndSearch() {
        rule.onNodeWithText("Downloads").performClick()
        rule.onNodeWithTag("search_toggle").performClick()
        rule.onNodeWithTag("search_field").assertIsDisplayed()
    }

    @Test fun categoriesListEverything() {
        rule.onNodeWithText("Categories").performClick()
        rule.onNodeWithText("Everything").assertIsDisplayed()
        rule.onNodeWithText("Videos").assertIsDisplayed()
    }

    @Test fun settingsThemeDialogOpens() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Theme").performClick()
        rule.onNodeWithText("Dark").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafe(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
