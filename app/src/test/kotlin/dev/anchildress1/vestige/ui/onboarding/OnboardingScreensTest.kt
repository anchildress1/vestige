package dev.anchildress1.vestige.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.ModelArtifactState
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
    fun `persona pick Continue fires when tapped`() {
        var continued = false
        composeRule.activity.setContent {
            VestigeTheme {
                PersonaPickScreen(
                    selected = Persona.WITNESS,
                    onSelect = {},
                    onContinue = { continued = true },
                )
            }
        }

        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        assertTrue(continued)
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
    fun `local explainer continue fires when Got it is tapped`() {
        var tapped = false
        composeRule.activity.setContent {
            VestigeTheme {
                LocalExplainerScreen(onContinue = { tapped = true })
            }
        }

        composeRule.onNodeWithText("Got it").performClick()
        assertTrue(tapped)
    }

    @Test
    fun `mic permission primary and secondary actions fire their callbacks`() {
        var allowed = false
        var skipped = false
        composeRule.activity.setContent {
            VestigeTheme {
                MicPermissionScreen(
                    showDeniedNotice = false,
                    onAllow = { allowed = true },
                    onSkip = { skipped = true },
                )
            }
        }

        composeRule.onNodeWithText("Allow microphone").performClick()
        composeRule.onNodeWithText("Skip — I'll type instead").performClick()
        assertTrue(allowed)
        assertTrue(skipped)
    }

    @Test
    fun `notification permission primary and secondary actions fire their callbacks`() {
        var allowed = false
        var skipped = false
        composeRule.activity.setContent {
            VestigeTheme {
                NotificationPermissionScreen(
                    onAllow = { allowed = true },
                    onSkip = { skipped = true },
                )
            }
        }

        composeRule.onNodeWithText("Allow notifications").performClick()
        composeRule.onNodeWithText("Skip — watch the app work").performClick()
        assertTrue(allowed)
        assertTrue(skipped)
    }

    @Test
    fun `typed fallback continue fires when Continue is tapped`() {
        var tapped = false
        composeRule.activity.setContent {
            VestigeTheme {
                TypedFallbackScreen(onContinue = { tapped = true })
            }
        }

        composeRule.onNodeWithText("Continue").performClick()
        assertTrue(tapped)
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

    @Test
    fun `model download placeholder disables Continue until the model is present`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = ModelArtifactState.Absent, onContinue = {})
            }
        }

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `model download placeholder shows downloading pill + loading note while waiting`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = ModelArtifactState.Absent, onContinue = {})
            }
        }

        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Model file is still landing. Keep Vestige open — this is the one network event in the app's life.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("MODEL READY").assertDoesNotExist()
    }

    @Test
    fun `model download placeholder keeps generic downloading pill for Corrupt state`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Corrupt(
                        expectedSha256 = "expected",
                        actualSha256 = "actual",
                    ),
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
    }

    @Test
    fun `model download placeholder shows placeholder dash when Partial total is unknown`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 10L, expectedBytes = 0L),
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
        composeRule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `model download placeholder swaps to ready pill once the artifact lands`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(modelState = ModelArtifactState.Complete, onContinue = {})
            }
        }

        composeRule.onNodeWithText("MODEL READY").assertIsDisplayed()
        composeRule.onNodeWithText("DOWNLOADING").assertDoesNotExist()
    }

    @Test
    fun `model download placeholder renders huge percent number and percent sign on Partial state`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 470L, expectedBytes = 1_000L),
                    onContinue = {},
                )
            }
        }

        // Hero rendering: number ("47") and "%" sign are separate text nodes; eyebrow says
        // "DOWNLOADING". The old single-pill "DOWNLOADING 47%" label is gone.
        composeRule.onNodeWithText("47").assertIsDisplayed()
        composeRule.onNodeWithText("%").assertIsDisplayed()
        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
    }

    @Test
    fun `model download placeholder hides progress semantics and loading note once ready`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Complete,
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Model file is still landing. Keep Vestige open — this is the one network event in the app's life.",
        ).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download progress").assertDoesNotExist()
    }

    @Test
    fun `model download placeholder exposes progress semantics while work is still in flight`() {
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Partial(currentBytes = 470L, expectedBytes = 1_000L),
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Download progress").assertIsDisplayed()
    }

    @Test
    fun `model download placeholder fires Continue only when enabled`() {
        var tapped = false
        composeRule.activity.setContent {
            VestigeTheme {
                ModelDownloadPlaceholderScreen(
                    modelState = ModelArtifactState.Complete,
                    onContinue = { tapped = true },
                )
            }
        }

        composeRule.onNodeWithText("Continue").performClick()
        assertTrue(tapped)
    }

    @Test
    fun `notification permission screen warns that skipping limits work to foreground`() {
        composeRule.activity.setContent {
            VestigeTheme {
                NotificationPermissionScreen(onAllow = {}, onSkip = {})
            }
        }

        val expected = "Skipping is fine. Without the notification, Vestige can only finish " +
            "reading an entry while the app is open — keep it foregrounded until the transcript lands."
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun `wifi check uses connected copy and callback when wifi is available`() {
        var continued = false
        composeRule.activity.setContent {
            VestigeTheme {
                WifiCheckScreen(
                    isWifiConnected = true,
                    onContinue = { continued = true },
                    onOpenWifiSettings = {},
                    onComeBackLater = {},
                )
            }
        }

        composeRule.onNodeWithText("Wi-Fi connected.").assertIsDisplayed()
        composeRule.onNodeWithText("Download model").performClick()
        assertTrue(continued)
    }

    @Test
    fun `wifi check uses missing copy and both callbacks when wifi is unavailable`() {
        var openedSettings = false
        var cameBackLater = false
        composeRule.activity.setContent {
            VestigeTheme {
                WifiCheckScreen(
                    isWifiConnected = false,
                    onContinue = {},
                    onOpenWifiSettings = { openedSettings = true },
                    onComeBackLater = { cameBackLater = true },
                )
            }
        }

        composeRule.onNodeWithText("Wi-Fi required.").assertIsDisplayed()
        composeRule.onNodeWithText("Open Wi-Fi settings").performClick()
        composeRule.onNodeWithText("I'll come back").performClick()
        assertTrue(openedSettings)
        assertTrue(cameBackLater)
    }

    @Test
    fun `onboarding scaffold renders optional subhead and secondary action`() {
        var primaryTapped = false
        var secondaryTapped = false
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingScaffold(
                    header = "Header",
                    subhead = "Subhead",
                    primary = OnboardingAction("Primary", onAction = { primaryTapped = true }),
                    secondary = OnboardingAction("Secondary", onAction = { secondaryTapped = true }),
                )
            }
        }

        composeRule.onNodeWithText("Subhead").assertIsDisplayed()
        composeRule.onNodeWithText("Primary").performClick()
        composeRule.onNodeWithText("Secondary").performClick()
        assertTrue(primaryTapped)
        assertTrue(secondaryTapped)
    }

    @Test
    fun `onboarding scaffold omits optional chrome when not provided`() {
        var primaryTapped = false
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingScaffold(
                    header = "Header",
                    primary = OnboardingAction("Primary", onAction = { primaryTapped = true }),
                )
            }
        }

        composeRule.onNodeWithText("Subhead").assertDoesNotExist()
        composeRule.onNodeWithText("Secondary").assertDoesNotExist()
        composeRule.onNodeWithText("Primary").performClick()
        assertTrue(primaryTapped)
    }

    @Test
    fun `onboarding scaffold keeps primary disabled and suppresses orphan secondary label`() {
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingScaffold(
                    header = "Header",
                    primary = OnboardingAction("Primary", onAction = {}, enabled = false),
                    secondary = null,
                    content = { BodyParagraph(text = "Body copy") },
                )
            }
        }

        composeRule.onNodeWithText("Body copy").assertIsDisplayed()
        composeRule.onNodeWithText("Primary").assertIsNotEnabled()
        composeRule.onNodeWithText("Secondary").assertDoesNotExist()
    }

    @Test
    fun `onboarding footer link renders dim helper copy`() {
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingFooterLink(text = "Footer helper")
            }
        }

        composeRule.onNodeWithText("Footer helper").assertIsDisplayed()
    }
}
