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
                    info = SettingsInfo(
                        versionLabel = "1.0.0",
                        sourceUrl = "https://example.test",
                        defaultPersona = Persona.WITNESS,
                    ),
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
        composeRule.onNodeWithText("Polyform Shield 1.0.0").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the default persona carries the DEFAULT tag`() {
        screen(persona = Persona.HARDASS)
        // Selection is HARDASS but the default is WITNESS — DEFAULT marks the default, not the
        // current selection.
        composeRule.onNodeWithText("DEFAULT").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag("settings_row_delete").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, wiped)
        composeRule.onAllNodesWithText("This deletes everything.").assertCountEquals(0)
    }
}
