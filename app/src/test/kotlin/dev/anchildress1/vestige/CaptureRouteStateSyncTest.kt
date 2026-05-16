package dev.anchildress1.vestige

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.capture.CaptureViewModel
import dev.anchildress1.vestige.ui.capture.ForegroundInferenceCall
import dev.anchildress1.vestige.ui.capture.ForegroundTextInferenceCall
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.capture.SaveAndExtract
import dev.anchildress1.vestige.ui.capture.VoiceCapture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class CaptureRouteStateSyncTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `SyncCaptureViewModelState pushes persona and readiness updates into the existing vm`() {
        val viewModel = CaptureViewModel(
            initialPersona = Persona.WITNESS,
            recordVoice = VoiceCapture { _, _ -> null },
            foregroundInference = ForegroundInferenceCall { _, _ -> parseFailure() },
            saveAndExtract = SaveAndExtract { _, _, _, _, _ -> },
            foregroundTextInference = ForegroundTextInferenceCall { _, _ -> parseFailure() },
            initialReadiness = ModelReadiness.Loading,
        )
        val persona = mutableStateOf(Persona.WITNESS)
        val readiness = mutableStateOf<ModelReadiness>(ModelReadiness.Loading)

        composeRule.setContent {
            SyncCaptureViewModelState(
                viewModel = viewModel,
                persona = persona.value,
                modelReadiness = readiness.value,
            )
        }

        composeRule.runOnIdle {
            persona.value = Persona.HARDASS
            readiness.value = ModelReadiness.Ready
        }
        composeRule.waitForIdle()

        assertEquals(Persona.HARDASS, viewModel.state.value.persona)
        assertEquals(ModelReadiness.Ready, viewModel.state.value.modelReadiness)
    }

    private fun parseFailure(): ForegroundResult = ForegroundResult.ParseFailure(
        persona = Persona.WITNESS,
        rawResponse = "",
        elapsedMs = 0L,
        completedAt = Instant.EPOCH,
        reason = ForegroundResult.ParseReason.EMPTY_RESPONSE,
    )
}
