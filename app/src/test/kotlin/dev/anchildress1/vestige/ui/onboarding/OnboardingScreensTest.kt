package dev.anchildress1.vestige.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingScreensTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // region Persona Pick

    @Test
    fun `persona pick renders headline + three persona cards with descriptions`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = {}, onContinue = {})
            }
        }
        composeRule.onNodeWithText("PERSONA", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Observes. Names the pattern. Keeps quiet otherwise.").assertIsDisplayed()
        composeRule.onNodeWithText("Sharper. Less padding. More action.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cuts vague words until they confess.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `persona pick marks the selected card with a11y selected semantics`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.HARDASS, onSelect = {}, onContinue = {})
            }
        }
        composeRule.onNodeWithText("HARDASS").assertIsSelected()
        composeRule.onNodeWithText("WITNESS").assertIsNotSelected()
        composeRule.onNodeWithText("EDITOR").assertIsNotSelected()
    }

    @Test
    fun `persona pick reports the tapped persona`() {
        var captured: Persona? = null
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = { captured = it }, onContinue = {})
            }
        }
        composeRule.onNodeWithText("EDITOR").performScrollTo().performClick()
        assertEquals(Persona.EDITOR, captured)
    }

    @Test
    fun `persona pick Continue carries the selected persona into the bar label`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.HARDASS, onSelect = {}, onContinue = {})
            }
        }
        composeRule.onNodeWithText("CONTINUE").assertIsDisplayed()
    }

    // endregion

    // region Download

    @Test
    fun `model download placeholder disables Continue until the model is present`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = ModelArtifactState.Absent, onContinue = {})
            }
        }
        composeRule.onNodeWithText("CONTINUE").assertIsNotEnabled()
    }

    @Test
    fun `model download placeholder renders huge percent number + percent sign on Partial state`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 470L, expectedBytes = 1_000L),
                    onContinue = {},
                )
            }
        }
        composeRule.onNodeWithText("47").assertIsDisplayed()
        composeRule.onNodeWithText("%").assertIsDisplayed()
        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
    }

    @Test
    fun `model download placeholder shows em dash placeholders when Partial total is unknown`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 10L, expectedBytes = 0L),
                    onContinue = {},
                )
            }
        }
        // The hero number and the MB/s stat both fall back to "—" with no fraction; both must
        // render so the UI doesn't blank out.
        composeRule.onAllNodesWithText("—").assertCountEquals(2)
    }

    @Test
    fun `model download placeholder swaps to ready pill once the artifact lands`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = ModelArtifactState.Complete, onContinue = {})
            }
        }
        composeRule.onNodeWithText("GEMMA READY").assertIsDisplayed()
    }

    // endregion
}
