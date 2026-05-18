package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class VestigeBottomNavTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders all three tabs`() {
        composeRule.setContent { VestigeTheme { VestigeBottomNav(active = BottomTab.CAPTURE, onSelect = {}) } }
        composeRule.onNodeWithText("CAPTURE").assertIsDisplayed()
        composeRule.onNodeWithText("PATTERNS").assertIsDisplayed()
        composeRule.onNodeWithText("HISTORY").assertIsDisplayed()
    }

    @Test
    fun `active tab is selected, others are not (a11y)`() {
        composeRule.setContent { VestigeTheme { VestigeBottomNav(active = BottomTab.PATTERNS, onSelect = {}) } }
        composeRule.onNodeWithText("PATTERNS").assertIsSelected()
        composeRule.onNodeWithText("CAPTURE").assertIsNotSelected()
        composeRule.onNodeWithText("HISTORY").assertIsNotSelected()
    }

    @Test
    fun `each tab reports the tab it selects`() {
        var picked: BottomTab? = null
        composeRule.setContent {
            VestigeTheme { VestigeBottomNav(active = BottomTab.CAPTURE, onSelect = { picked = it }) }
        }
        composeRule.onNodeWithText("HISTORY").performClick()
        assertEquals(BottomTab.HISTORY, picked)
        composeRule.onNodeWithText("PATTERNS").performClick()
        assertEquals(BottomTab.PATTERNS, picked)
    }

    @Test
    fun `tabs meet the 48dp tap-target floor (a11y)`() {
        composeRule.setContent { VestigeTheme { VestigeBottomNav(active = BottomTab.CAPTURE, onSelect = {}) } }
        composeRule.onNodeWithText("CAPTURE").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("PATTERNS").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("HISTORY").assertHeightIsAtLeast(48.dp)
    }
}
