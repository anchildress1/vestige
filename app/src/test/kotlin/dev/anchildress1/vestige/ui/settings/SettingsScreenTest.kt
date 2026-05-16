package dev.anchildress1.vestige.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun screen(
        persona: Persona = Persona.WITNESS,
        onSelectPersona: (Persona) -> Unit = {},
        onWipe: () -> Unit = {},
        onOpenModelStatus: () -> Unit = {},
    ) {
        composeRule.activity.setContent {
            VestigeTheme {
                SettingsScreen(
                    persona = persona,
                    info = SettingsInfo(versionLabel = "1.0.0", sourceUrl = "https://example.test"),
                    actions = SettingsActions(
                        onSelectPersona = onSelectPersona,
                        onExportToUri = {},
                        onWipe = onWipe,
                        onOpenModelStatus = onOpenModelStatus,
                        onOpenSource = {},
                        onExit = {},
                    ),
                )
            }
        }
    }

    @Test
    fun `renders the four sections and about details`() {
        screen()
        // The screen is a single verticalScroll column; Robolectric's short window means
        // lower rows must be scrolled into view before they count as displayed.
        composeRule.onNodeWithText("Settings.").assertIsDisplayed()
        composeRule.onNodeWithText("PERSONA").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export all entries").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Delete all data").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Model status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("v1.0.0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Polyform Shield 1.0.0").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping a persona reports the selection`() {
        var picked: Persona? = null
        screen(onSelectPersona = { picked = it })
        composeRule.onNodeWithText("Editor").performClick()
        assertEquals(Persona.EDITOR, picked)
    }

    @Test
    fun `model status row navigates`() {
        var opened = false
        screen(onOpenModelStatus = { opened = true })
        composeRule.onNodeWithText("Model status").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `delete-all confirm is armed only after typing DELETE`() {
        var wiped = 0
        screen(onWipe = { wiped++ })
        composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()
        composeRule.onNodeWithText("This deletes everything.").assertIsDisplayed()

        val confirm = composeRule.onNodeWithText("Wipe everything. No backup.")
        confirm.assertIsNotEnabled()
        composeRule.onNodeWithTag(WIPE_FIELD_TAG).performTextInput("DELETE")
        confirm.assertIsEnabled()
        confirm.performClick()
        assertEquals(1, wiped)
    }

    @Test
    fun `delete-all cancel dismisses without wiping`() {
        var wiped = 0
        screen(onWipe = { wiped++ })
        composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, wiped)
        composeRule.onAllNodesWithText("This deletes everything.").assertCountEquals(0)
    }
}
