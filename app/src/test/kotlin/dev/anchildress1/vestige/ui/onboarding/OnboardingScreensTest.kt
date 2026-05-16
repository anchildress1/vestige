package dev.anchildress1.vestige.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
        // Hero number, the MB/s stat, and the ETA slot all fall back to "—" with no known
        // total; all three must render so the UI doesn't blank out.
        composeRule.onAllNodesWithText("—").assertCountEquals(3)
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

    @Test
    fun `active download shows the ETA label and a Pause affordance`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 100L, expectedBytes = 1_000L),
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Active, etaSeconds = 125L),
                    onContinue = {},
                )
            }
        }
        composeRule.onNodeWithText("~2 min").performScrollTo().assertIsDisplayed()
        // Pause lives in the fixed bottom bar (no scrollable parent) — assert in place.
        composeRule.onNodeWithText("Pause").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `stalled download surfaces a status band with no click action and a Retry button`() {
        var retried = false
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 400L, expectedBytes = 1_000L),
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Stalled),
                    onContinue = {},
                    onRetry = { retried = true },
                )
            }
        }
        // Band a11y: polite live region, no click action — recovery is the Retry button's job.
        val band = composeRule.onNodeWithContentDescription("Download stalled.")
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        // Retry lives in the fixed bottom bar (no scrollable parent) — act in place.
        composeRule.onNodeWithText("Retry").assertHasClickAction().performClick()
        assertEquals(true, retried)
    }

    @Test
    fun `failed download surfaces the network-choked band and a Try again button`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 400L, expectedBytes = 1_000L),
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Failed),
                    onContinue = {},
                    onRetry = {},
                )
            }
        }
        val band = composeRule.onNodeWithContentDescription("Network choked.")
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithText("Try again").assertHasClickAction()
    }

    @Test
    fun `reacquiring shows the auto-redownload band and offers no manual affordance`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 0L, expectedBytes = 1_000L),
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Reacquiring),
                    onContinue = {},
                )
            }
        }
        val band = composeRule.onNodeWithContentDescription("Model file unreadable. Re-downloading.")
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // Automatic clean re-pull in flight — no Retry / Try again / Pause while it runs.
        composeRule.onAllNodesWithText("Retry").assertCountEquals(0)
        composeRule.onAllNodesWithText("Try again").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pause").assertCountEquals(0)
    }

    // endregion
}
