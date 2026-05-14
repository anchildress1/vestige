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
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.capture.CaptureMeta
import dev.anchildress1.vestige.ui.capture.CaptureScreen
import dev.anchildress1.vestige.ui.capture.CaptureStats
import dev.anchildress1.vestige.ui.capture.CaptureViewModel
import dev.anchildress1.vestige.ui.capture.ForegroundInferenceCall
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.capture.RealVoiceCapture
import dev.anchildress1.vestige.ui.capture.SaveAndExtract
import dev.anchildress1.vestige.ui.capture.ToneGeneratorLimitWarningCue
import dev.anchildress1.vestige.ui.onboarding.ModelAvailability
import dev.anchildress1.vestige.ui.onboarding.OnboardingHost
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val viewModel = remember(container, persona, limitWarningCue) {
        CaptureViewModel(
            initialPersona = persona,
            recordVoice = RealVoiceCapture(),
            foregroundInference = ForegroundInferenceCall { audio, sel ->
                container.runForegroundCall(audio = audio, persona = sel)
            },
            saveAndExtract = SaveAndExtract { text, capturedAt, personaSel ->
                container.saveAndExtract(entryText = text, capturedAt = capturedAt, persona = personaSel)
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
    val stats = remember(container) { deriveStats(container) }
    val meta = remember(clock, zoneId) { deriveMeta(clock, zoneId) }
    CaptureScreen(
        viewModel = viewModel,
        stats = stats,
        meta = meta,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun deriveInitialReadiness(container: AppContainer): ModelReadiness {
    // Synchronous presence + size check — no SHA-256 hashing of a 3.66 GB file blocking REC.
    // The engine's own `initialize()` rejects a corrupt artifact at first foreground call, so
    // the corruption case surfaces as `InferenceFailed` rather than a multi-second UI gate.
    val store = container.mainModelArtifactStore
    val file = store.artifactFile
    val ready = runCatching { file.exists() && file.length() == store.manifest.expectedByteSize }.getOrDefault(false)
    return if (ready) ModelReadiness.Ready else ModelReadiness.Loading
}

private fun deriveStats(container: AppContainer): CaptureStats {
    val kept = container.entryStore.countCompleted().toInt()
    val visible = container.patternStore.findVisibleSortedByLastSeen()
    val active = visible.count { it.state == PatternState.ACTIVE }
    val hitsThisMonth = visible.sumOf { it.supportingEntries.size }
    return CaptureStats(kept = kept, active = active, hitsThisMonth = hitsThisMonth, cloud = 0)
}

private fun deriveMeta(clock: Clock, zoneId: ZoneId): CaptureMeta {
    val now = clock.instant().atZone(zoneId)
    return CaptureMeta(
        weekdayLabel = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US).uppercase(Locale.US),
        monthDayLabel = now.format(MONTH_DAY_FORMATTER).uppercase(Locale.US),
        timeLabel = now.format(TIME_FORMATTER),
        // Day counter + streak are demo-mocked until the install-timestamp prefs land in a
        // follow-up; the screen renders DAY 1 / 0 d on a fresh install rather than fake history.
        dayNumber = 1,
        streakDays = 0,
    )
}

private val MONTH_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

// 12-hour clock with AM/PM marker — "9:41 AM" / "9:41 PM". The Scoreboard date strip leans on
// tabular nums in the display style so the AM/PM suffix lands as legible chrome without throwing
// the column alignment.
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
