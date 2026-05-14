package dev.anchildress1.vestige

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.capture.CaptureScreen
import dev.anchildress1.vestige.ui.capture.CaptureViewModel
import dev.anchildress1.vestige.ui.capture.ForegroundInferenceCall
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.capture.RealVoiceCapture
import dev.anchildress1.vestige.ui.capture.SaveAndExtract
import dev.anchildress1.vestige.ui.capture.SaveTypedEntry
import dev.anchildress1.vestige.ui.capture.ToneGeneratorLimitWarningCue
import dev.anchildress1.vestige.ui.capture.deriveInitialReadiness
import dev.anchildress1.vestige.ui.capture.deriveMeta
import dev.anchildress1.vestige.ui.capture.deriveStats
import dev.anchildress1.vestige.ui.onboarding.ModelAvailability
import dev.anchildress1.vestige.ui.onboarding.OnboardingHost
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.time.Clock
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VestigeApplication).appContainer
        val onboardingPrefs = OnboardingPrefs.from(this)
        val clock = Clock.systemDefaultZone()
        val zoneId: ZoneId = ZoneId.systemDefault()
        setContent {
            VestigeTheme {
                var onboardingComplete by remember { mutableStateOf(onboardingPrefs.isComplete) }
                var selectedPersona by remember { mutableStateOf(onboardingPrefs.defaultPersona) }
                if (!onboardingComplete) {
                    OnboardingHost(
                        prefs = onboardingPrefs,
                        onComplete = { persona ->
                            selectedPersona = persona
                            onboardingComplete = true
                        },
                        modelAvailability = ModelAvailability.Default(
                            artifactStore = container.mainModelArtifactStore,
                            networkGate = container.networkGate,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    return@VestigeTheme
                }
                CaptureRoute(
                    container = container,
                    persona = selectedPersona,
                    clock = clock,
                    zoneId = zoneId,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CaptureRoute(container: AppContainer, persona: Persona, clock: Clock, zoneId: ZoneId) {
    val limitWarningCue = remember { ToneGeneratorLimitWarningCue() }
    DisposableEffect(limitWarningCue) {
        onDispose { limitWarningCue.release() }
    }
    val factory = remember(container, persona, limitWarningCue) {
        CaptureViewModelFactory(
            initialPersona = persona,
            recordVoice = RealVoiceCapture(),
            foregroundInference = ForegroundInferenceCall { audio, sel ->
                container.runForegroundCall(audio = audio, persona = sel)
            },
            saveAndExtract = SaveAndExtract { text, capturedAt, personaSel ->
                container.saveAndExtract(entryText = text, capturedAt = capturedAt, persona = personaSel)
            },
            saveTypedEntry = SaveTypedEntry { text, capturedAt, personaSel ->
                container.saveTypedEntry(entryText = text, capturedAt = capturedAt, persona = personaSel)
            },
            clock = clock,
            zoneId = zoneId,
            // Fast presence + size check at composition; the engine's own load verifies
            // integrity at first use, so the UI gate doesn't need the full SHA-256 hash of a
            // 3.66 GB file to enable REC. Real-time observation of Downloading / Paused states
            // lands with the error chrome in commit 6.
            initialReadiness = deriveInitialReadiness(container),
            limitWarningCue = limitWarningCue,
        )
    }
    val viewModel: CaptureViewModel = viewModel(factory = factory)
    val stats = remember(container) { deriveStats(container) }
    val meta = remember(clock, zoneId) { deriveMeta(clock, zoneId) }
    CaptureScreen(
        viewModel = viewModel,
        stats = stats,
        meta = meta,
        modifier = Modifier.fillMaxSize(),
    )
}

@Suppress("LongParameterList")
private class CaptureViewModelFactory(
    private val initialPersona: Persona,
    private val recordVoice: RealVoiceCapture,
    private val foregroundInference: ForegroundInferenceCall,
    private val saveAndExtract: SaveAndExtract,
    private val saveTypedEntry: SaveTypedEntry,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val initialReadiness: ModelReadiness,
    private val limitWarningCue: ToneGeneratorLimitWarningCue,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CaptureViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return CaptureViewModel(
            initialPersona = initialPersona,
            recordVoice = recordVoice,
            foregroundInference = foregroundInference,
            saveAndExtract = saveAndExtract,
            saveTypedEntry = saveTypedEntry,
            clock = clock,
            zoneId = zoneId,
            initialReadiness = initialReadiness,
            limitWarningCue = limitWarningCue,
        ) as T
    }
}
