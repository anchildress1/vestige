package dev.anchildress1.vestige.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingScreensTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `persona pick announces all three personas with descriptions`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = {}, onContinue = {})
            }
        }

        composeRule.onNodeWithText("Observes. Names the pattern. Keeps quiet otherwise.").assertIsDisplayed()
        composeRule.onNodeWithText("Sharper. Less padding. More action.").assertIsDisplayed()
        composeRule.onNodeWithText("Cuts vague words until they confess.").assertIsDisplayed()
    }

    @Test
    fun `persona pick marks the selected card with a11y selected semantics`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.HARDASS, onSelect = {}, onContinue = {})
            }
        }
        composeRule.onNodeWithText("Hardass").assertIsSelected()
        composeRule.onNodeWithText("Witness").assertIsNotSelected()
        composeRule.onNodeWithText("Editor").assertIsNotSelected()
    }

    @Test
    fun `persona pick reports the tapped persona`() {
        var captured: Persona? = null
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = { captured = it }, onContinue = {})
            }
        }
        composeRule.onNodeWithText("Editor").performClick()
        assertEquals(Persona.EDITOR, captured)
    }

    @Test
    fun `mic permission notice hidden without denied flag`() {
        composeRule.activity.setContent {
            VestigeTheme {
                MicPermissionScreen(showDeniedNotice = false, onAllow = {}, onSkip = {})
            }
        }
        composeRule.onNodeWithText("Mic permission required to record. Settings → Permissions.")
            .assertDoesNotExist()
    }

    @Test
    fun `mic permission notice rendered when denied flag is set`() {
        composeRule.activity.setContent {
            VestigeTheme {
                MicPermissionScreen(showDeniedNotice = true, onAllow = {}, onSkip = {})
            }
        }
        composeRule.onNodeWithText("Mic permission required to record. Settings → Permissions.")
            .assertIsDisplayed()
    }

    @Test
    fun `Ready screen interpolates the selected persona name`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ReadyScreen(persona = Persona.HARDASS, onOpenApp = {})
            }
        }
        val expected = "Everything's local. The model's loaded. Talk into the mic when you've " +
            "got something to dump, or type. Hardass is selected."
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun `Ready screen onOpenApp fires when the primary action is tapped`() {
        var tapped = false
        composeRule.activity.setContent {
            VestigeTheme {
                ReadyScreen(persona = Persona.WITNESS, onOpenApp = { tapped = true })
            }
        }
        assertFalse(tapped)
        composeRule.onNodeWithText("Open Vestige").performClick()
        assertTrue(tapped)
    }
}
