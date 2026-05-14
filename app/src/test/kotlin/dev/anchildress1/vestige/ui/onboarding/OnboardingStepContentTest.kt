package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingStepContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val callbacks = OnboardingStepCallbacks(
        onPersonaChange = {},
        advance = {},
        onMicAllow = {},
        onNotificationAllow = {},
        onOpenWifiSettings = {},
        onComeBackLater = {},
        onOpenApp = {},
        onOpenModelDownload = {},
        onDownloadReturn = {},
        onChangePersona = {},
    )

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

        composeRule.onAllNodesWithText("NETWORK DOWN · RECONNECT TO DOWNLOAD").assertCountEquals(1)
        composeRule.onAllNodesWithText("DENIED · TAP AGAIN OR SETTINGS → PERMISSIONS").assertCountEquals(1)
        composeRule.onAllNodesWithText("SINGLE-STATUS ONLY · NOTHING ELSE, EVER").assertCountEquals(1)
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsNotEnabled()
    }

    @Test
    fun `wiring shows pending hints while model download is still running`() {
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

        composeRule.onAllNodesWithText("DOWNLOAD STILL RUNNING · BACK UP TO RESUME").assertCountEquals(1)
        composeRule.onAllNodesWithText("REQUIRED FOR VOICE · OPTIONAL OTHERWISE").assertCountEquals(1)
        composeRule.onAllNodesWithText("SINGLE-STATUS ONLY · NOTHING ELSE, EVER").assertCountEquals(1)
        composeRule.onAllNodesWithText("WAITING ON MODEL · TAP LOCAL TO START").assertCountEquals(1)
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsNotEnabled()
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

        composeRule.onAllNodesWithText("NETWORK DOWN · RECONNECT TO DOWNLOAD").assertCountEquals(1)
        composeRule.onAllNodesWithText("DOWNLOAD STILL RUNNING · BACK UP TO RESUME").assertCountEquals(0)
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

    private fun renderWiring(state: OnboardingStepState) {
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
}
