package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class RecButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `default content description is Record`() {
        composeRule.setContent { VestigeTheme { RecButton(onClick = {}) } }
        composeRule.onNodeWithContentDescription("Record").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `overridden content description is announced`() {
        composeRule.setContent {
            VestigeTheme { RecButton(onClick = {}, contentDescription = "Stop") }
        }
        composeRule.onNodeWithContentDescription("Stop").assertIsDisplayed()
    }

    @Test
    fun `tap target is at least 48dp tall and wide`() {
        composeRule.setContent { VestigeTheme { RecButton(onClick = {}) } }
        composeRule.onNodeWithContentDescription("Record")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `click invokes onClick when enabled`() {
        var taps = 0
        composeRule.setContent { VestigeTheme { RecButton(onClick = { taps += 1 }) } }
        composeRule.onNodeWithContentDescription("Record").performClick()
        composeRule.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun `disabled state does not fire onClick`() {
        var taps = 0
        composeRule.setContent {
            VestigeTheme { RecButton(onClick = { taps += 1 }, enabled = false) }
        }
        composeRule.onNodeWithContentDescription("Record").performClick()
        composeRule.runOnIdle { assertEquals(0, taps) }
    }

    @Test
    fun `renders REC label and hint text`() {
        composeRule.setContent { VestigeTheme { RecButton(onClick = {}) } }
        composeRule.onNodeWithText("REC").assertIsDisplayed()
        composeRule.onNodeWithText("TAP · TALK · 30s").assertIsDisplayed()
    }

    @Test
    fun `composes inside an arbitrary container without crashing`() {
        // Smoke test: layout participation is the primary contract here.
        composeRule.setContent { VestigeTheme { Box { RecButton(onClick = {}) } } }
        composeRule.onNodeWithContentDescription("Record").assertIsDisplayed()
    }
}
