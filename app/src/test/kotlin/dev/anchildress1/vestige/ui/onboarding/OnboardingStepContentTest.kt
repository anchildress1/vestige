package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
class OnboardingStepContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `wiring shows corrupt-local and denied-mic blocked hints`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.EDITOR,
                micPermissionDenied = true,
                wifiConnected = true,
                modelState = ModelArtifactState.Corrupt(
                    expectedSha256 = "expected",
                    actualSha256 = "actual",
                ),
                micGranted = false,
                notifGranted = false,
            ),
        )

        // Corrupt artifact gets its own retry-framed hint, not the network-down message — the
        // user can re-tap to re-download, the network is fine.
        composeRule.onAllNodesWithText("ARTIFACT CORRUPT · TAP TO RETRY").assertCountEquals(1)
        composeRule.onAllNodesWithText("DENIED · TAP AGAIN OR SETTINGS → PERMISSIONS").assertCountEquals(1)
        composeRule.onAllNodesWithText("SINGLE-STATUS ONLY · NOTHING ELSE, EVER").assertCountEquals(1)
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsNotEnabled()
    }

    @Test
    fun `wiring shows partial-download hint while model is mid-flight`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.WITNESS,
                micPermissionDenied = false,
                wifiConnected = true,
                modelState = ModelArtifactState.Partial(currentBytes = 100, expectedBytes = 1_000),
                micGranted = false,
                notifGranted = false,
            ),
        )

        composeRule.onAllNodesWithText("DOWNLOAD STILL RUNNING · BACK UP TO RESUME").assertCountEquals(1)
        composeRule.onAllNodesWithText("REQUIRED FOR VOICE · OPTIONAL OTHERWISE").assertCountEquals(1)
        composeRule.onAllNodesWithText("SINGLE-STATUS ONLY · NOTHING ELSE, EVER").assertCountEquals(1)
        composeRule.onAllNodesWithText("WAITING ON MODEL · TAP LOCAL TO START").assertCountEquals(1)
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsNotEnabled()
    }

    @Test
    fun `wiring shows start-here hint when model is Absent and wifi is up`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.WITNESS,
                micPermissionDenied = false,
                wifiConnected = true,
                modelState = ModelArtifactState.Absent,
                micGranted = false,
                notifGranted = false,
            ),
        )

        composeRule.onAllNodesWithText("TAP LOCAL TO START DOWNLOAD").assertCountEquals(1)
        composeRule.onAllNodesWithText("DOWNLOAD STILL RUNNING · BACK UP TO RESUME").assertCountEquals(0)
    }

    @Test
    fun `wiring shows network blocked hint before download starts when wifi is down`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.WITNESS,
                micPermissionDenied = false,
                wifiConnected = false,
                modelState = ModelArtifactState.Absent,
                micGranted = false,
                notifGranted = false,
            ),
        )

        composeRule.onAllNodesWithText("NETWORK DOWN · TAP FOR WI-FI SETTINGS").assertCountEquals(1)
        composeRule.onAllNodesWithText("DOWNLOAD STILL RUNNING · BACK UP TO RESUME").assertCountEquals(0)
        composeRule.onAllNodesWithText("TAP LOCAL TO START DOWNLOAD").assertCountEquals(0)
    }

    @Test
    fun `wiring prefers wifi guidance over corrupt retry when local model is corrupt and offline`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.WITNESS,
                micPermissionDenied = false,
                wifiConnected = false,
                modelState = ModelArtifactState.Corrupt(
                    expectedSha256 = "expected",
                    actualSha256 = "actual",
                ),
                micGranted = false,
                notifGranted = false,
            ),
        )

        composeRule.onAllNodesWithText("NETWORK DOWN · TAP FOR WI-FI SETTINGS").assertCountEquals(1)
        composeRule.onAllNodesWithText("ARTIFACT CORRUPT · TAP TO RETRY").assertCountEquals(0)
    }

    @Test
    fun `wiring local row opens wifi settings when download is blocked by missing wifi`() {
        var wifiSettingsOpens = 0
        renderWiring(
            state = OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.WITNESS,
                micPermissionDenied = false,
                wifiConnected = false,
                modelState = ModelArtifactState.Absent,
                micGranted = false,
                notifGranted = false,
            ),
            callbacks = callbacks(
                onOpenWifiSettings = { wifiSettingsOpens += 1 },
            ),
        )

        composeRule.onNodeWithText("LOCAL · GEMMA 4", substring = true).performClick()
        assertEquals(1, wifiSettingsOpens)
    }

    @Test
    fun `wiring switch rows expose checked semantics`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.WITNESS,
                micPermissionDenied = false,
                wifiConnected = true,
                modelState = ModelArtifactState.Complete,
                micGranted = false,
                notifGranted = true,
            ),
        )

        composeRule.onNodeWithText("MIC · INPUT", substring = true).assertIsOff()
        composeRule.onNodeWithText("NOTIFY · STATUS", substring = true).assertIsOn()
        composeRule.onNodeWithText("TYPE · FALLBACK", substring = true).assertIsOn()
    }

    @Test
    fun `wiring enables open vestige once local model and optional grants are ready`() {
        renderWiring(
            OnboardingStepState(
                step = OnboardingStep.Wiring,
                persona = Persona.EDITOR,
                micPermissionDenied = false,
                wifiConnected = false,
                modelState = ModelArtifactState.Complete,
                micGranted = true,
                notifGranted = true,
            ),
        )

        composeRule.onAllNodesWithText("WAITING ON MODEL · TAP LOCAL TO START").assertCountEquals(0)
        composeRule.onAllNodesWithText("DENIED · TAP AGAIN OR SETTINGS → PERMISSIONS").assertCountEquals(0)
        composeRule.onAllNodesWithText("SINGLE-STATUS ONLY · NOTHING ELSE, EVER").assertCountEquals(0)
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsEnabled()
    }

    private fun renderWiring(state: OnboardingStepState, callbacks: OnboardingStepCallbacks = callbacks()) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingStepContent(
                    state = state,
                    callbacks = callbacks,
                    context = context,
                    modifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun callbacks(
        onOpenWifiSettings: () -> Unit = {},
        onOpenModelDownload: () -> Unit = {},
    ): OnboardingStepCallbacks = OnboardingStepCallbacks(
        onPersonaChange = {},
        advance = {},
        onMicAllow = {},
        onNotificationAllow = {},
        onOpenWifiSettings = onOpenWifiSettings,
        onComeBackLater = {},
        onOpenApp = {},
        onOpenModelDownload = onOpenModelDownload,
        onDownloadReturn = {},
        onChangePersona = {},
    )
}
