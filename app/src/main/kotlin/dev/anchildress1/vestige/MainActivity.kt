package dev.anchildress1.vestige

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.anchildress1.vestige.ui.capture.deriveMeta
import dev.anchildress1.vestige.ui.capture.deriveStats
import dev.anchildress1.vestige.ui.onboarding.ModelAvailability
import dev.anchildress1.vestige.ui.onboarding.OnboardingHost
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import dev.anchildress1.vestige.ui.patterns.PatternsHost
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
                var screen by rememberSaveable { mutableStateOf(PostOnboardingScreen.Capture) }
                when (screen) {
                    PostOnboardingScreen.Capture -> CaptureRoute(
                        container = container,
                        persona = selectedPersona,
                        clock = clock,
                        zoneId = zoneId,
                        onOpenPatterns = { screen = PostOnboardingScreen.Patterns },
                    )

                    PostOnboardingScreen.Patterns -> PatternsHost(
                        patternStore = container.patternStore,
                        patternRepo = container.patternRepo,
                        entryStore = container.entryStore,
                        onExit = { screen = PostOnboardingScreen.Capture },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private enum class PostOnboardingScreen { Capture, Patterns }

@androidx.compose.runtime.Composable
private fun CaptureRoute(
    container: AppContainer,
    persona: Persona,
    clock: Clock,
    zoneId: ZoneId,
    onOpenPatterns: () -> Unit,
) {
    val limitWarningCue = remember { ToneGeneratorLimitWarningCue() }
    DisposableEffect(limitWarningCue) {
        onDispose { limitWarningCue.release() }
    }
    val modelReadiness by container.modelReadinessFlow.collectAsStateWithLifecycle()
    val dataRevision by container.dataRevision.collectAsStateWithLifecycle()
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
            initialReadiness = container.modelReadinessFlow.value,
            limitWarningCue = limitWarningCue,
        )
    }
    val viewModel: CaptureViewModel = viewModel(factory = factory)
    // Pipe live model-readiness changes into the VM so REC enablement, the error band, and
    // the inferring-vs-loading chrome stay in sync if the artifact transitions during the
    // session (download completes, pauses, or is removed via Settings).
    LaunchedEffect(viewModel, modelReadiness) { viewModel.setModelReadiness(modelReadiness) }
    // Re-probe on ON_RESUME so a download that completed in another activity / process is
    // reflected when the user returns. AppContainer no-ops if nothing changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { container.refreshModelReadiness() }
    // `dataRevision` as a remember key forces re-derivation whenever AppContainer increments
    // it (entry write / pattern write / recovery sweep). Cheap — entryStore.countCompleted +
    // patternStore.findVisibleSortedByLastSeen are indexed reads.
    val stats = remember(container, dataRevision) { deriveStats(container) }
    val meta = remember(clock, zoneId) { deriveMeta(clock, zoneId) }
    CaptureScreen(
        viewModel = viewModel,
        stats = stats,
        meta = meta,
        modifier = Modifier.fillMaxSize(),
        onOpenPatterns = onOpenPatterns,
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
