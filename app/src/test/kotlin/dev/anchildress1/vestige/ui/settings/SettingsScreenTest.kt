package dev.anchildress1.vestige.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
                        onExportToUri = { true },
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
    fun `renders the headline, sections and rows`() {
        screen()
        composeRule.onNodeWithText("SETTINGS.").assertIsDisplayed()
        composeRule.onNodeWithText("PERSONA").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("DATA").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("MODEL").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ABOUT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_export").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_delete").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_model").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_version").performScrollTo().assertIsDisplayed()
        // License is folded into the Version box (merged a11y node), under the version line.
        composeRule.onNodeWithContentDescription("Polyform Shield 1.0.0", substring = true).assertExists()
    }

    @Test
    fun `the selected persona carries the SELECTED tag`() {
        screen(persona = Persona.HARDASS)
        // The tag marks the currently-selected persona.
        composeRule.onNodeWithTag("persona_HARDASS").performScrollTo()
        composeRule.onNodeWithText("SELECTED").assertIsDisplayed()
    }

    @Test
    fun `tapping a persona reports the selection`() {
        var picked: Persona? = null
        screen(onSelectPersona = { picked = it })
        composeRule.onNodeWithTag("persona_EDITOR").performScrollTo().performClick()
        assertEquals(Persona.EDITOR, picked)
    }

    @Test
    fun `model status row navigates`() {
        var opened = false
        screen(onOpenModelStatus = { opened = true })
        composeRule.onNodeWithTag("settings_row_model").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `delete-all confirm is armed only after typing DELETE`() {
        var wiped = 0
        screen(onWipe = { wiped++ })
        composeRule.onNodeWithTag("settings_row_delete").performScrollTo().performClick()
        // The scoreboard confirm card uppercases the headline.
        composeRule.onNodeWithText("THIS DELETES EVERYTHING.").assertIsDisplayed()

        val confirm = composeRule.onNodeWithContentDescription("Wipe everything. No backup.")
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
        composeRule.onNodeWithTag("settings_row_delete").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(0, wiped)
        composeRule.onAllNodesWithText("THIS DELETES EVERYTHING.").assertCountEquals(0)
    }
}
