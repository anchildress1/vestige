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

    private val partial = ModelArtifactState.Partial(
        currentBytes = 1_500_000_000L,
        expectedBytes = 3_928_000_000L,
    )

    // region Persona Pick

    @Test
    fun `persona pick renders headline + three persona cards with short descriptions`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = {}, onContinue = {})
            }
        }
        composeRule.onNodeWithText("PICK A PERSONA", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Observes. Names the pattern.").assertIsDisplayed()
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
        composeRule.onNodeWithText("Sharper. Less padding. More action.").assertIsSelected()
        composeRule.onNodeWithText("Observes. Names the pattern.").assertIsNotSelected()
        composeRule.onNodeWithText("Cuts vague words until they confess.").assertIsNotSelected()
    }

    @Test
    fun `persona pick shows the SELECTED tag only on the chosen card`() {
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = {}, onContinue = {})
            }
        }
        composeRule.onAllNodesWithText("SELECTED").assertCountEquals(1)
    }

    @Test
    fun `persona pick reports the tapped persona`() {
        var captured: Persona? = null
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.WITNESS, onSelect = { captured = it }, onContinue = {})
            }
        }
        composeRule.onNodeWithText("Cuts vague words until they confess.").performScrollTo().performClick()
        assertEquals(Persona.EDITOR, captured)
    }

    @Test
    fun `persona pick primary reads SELECT and fires onContinue`() {
        var advanced = false
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(selected = Persona.HARDASS, onSelect = {}, onContinue = { advanced = true })
            }
        }
        composeRule.onNodeWithText("SELECT").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(true, advanced)
    }

    // endregion

    // region Download

    @Test
    fun `download screen has no primary — completion auto-unwinds`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = partial)
            }
        }
        composeRule.onNodeWithText("DOWNLOAD MODEL", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("CONTINUE").assertCountEquals(0)
    }

    @Test
    fun `download card renders percent, total, and a Pause affordance on active Partial`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = partial,
                    downloadMbps = 6.4f,
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Active, etaSeconds = 258L),
                )
            }
        }
        composeRule.onNodeWithText("38").assertIsDisplayed()
        composeRule.onNodeWithText("%").assertIsDisplayed()
        composeRule.onNodeWithText("OF 3.66 GB").assertIsDisplayed()
        composeRule.onNodeWithText("04:18").assertIsDisplayed()
        // Speed line is the card's last row — below the Robolectric fold; existence is the
        // contract (it's plain status text, not interactive).
        composeRule.onNodeWithText("~6.4 MB/S · WI-FI").assertExists()
        composeRule.onNodeWithText("PAUSE").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `download card shows em dash percent when total is unknown`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 10L, expectedBytes = 0L),
                )
            }
        }
        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("--:--").assertIsDisplayed()
    }

    @Test
    fun `download screen swaps to ready pill once the artifact lands`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = ModelArtifactState.Complete)
            }
        }
        composeRule.onNodeWithText("GEMMA READY").assertIsDisplayed()
    }

    @Test
    fun `stalled download surfaces a status band with no click action and a Retry button`() {
        var retried = false
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = partial,
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Stalled),
                    onRetry = { retried = true },
                )
            }
        }
        // Band sits below the download card in the scroll region — existence + semantics is
        // the contract (the polite live region announces it regardless of scroll position).
        val band = composeRule.onNodeWithContentDescription("Download stalled.")
        band.assertExists()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        composeRule.onNodeWithText("RETRY").assertHasClickAction().performClick()
        assertEquals(true, retried)
    }

    @Test
    fun `failed download surfaces the network-choked band and a Try again button`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = partial,
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Failed),
                    onRetry = {},
                )
            }
        }
        val band = composeRule.onNodeWithContentDescription("Network choked.")
        band.assertExists()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithText("TRY AGAIN").assertHasClickAction()
    }

    @Test
    fun `reacquiring shows the auto-redownload band and offers no manual affordance`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 0L, expectedBytes = 3_928_000_000L),
                    downloadStatus = DownloadStatus(phase = DownloadPhase.Reacquiring),
                )
            }
        }
        val band = composeRule.onNodeWithContentDescription("Model file unreadable. Re-downloading.")
        band.assertExists()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onAllNodesWithText("RETRY").assertCountEquals(0)
        composeRule.onAllNodesWithText("TRY AGAIN").assertCountEquals(0)
        composeRule.onAllNodesWithText("PAUSE").assertCountEquals(0)
    }

    // endregion
}
