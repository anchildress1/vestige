package dev.anchildress1.vestige

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.capture.CaptureMeta
import dev.anchildress1.vestige.ui.capture.CaptureScreen
import dev.anchildress1.vestige.ui.capture.CaptureStats
import dev.anchildress1.vestige.ui.capture.CaptureViewModel
import dev.anchildress1.vestige.ui.capture.ForegroundInferenceCall
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.capture.SaveAndExtract
import dev.anchildress1.vestige.ui.capture.VoiceCapture
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
    val viewModel = remember(container, persona) {
        CaptureViewModel(
            initialPersona = persona,
            // Voice path is stubbed in this commit; commit 5 swaps in the real AudioCapture +
            // ForegroundInference wiring against AppContainer. The stub returns null so the
            // recording job completes cleanly without reaching the inference call.
            recordVoice = VoiceCapture { _, _ -> null as AudioChunk? },
            foregroundInference = ForegroundInferenceCall { _, _ ->
                // Unreachable while the voice path is stubbed — type-only saves bypass this.
                error("ForegroundInference is not wired yet — commit 5 plumbs the real engine.")
            },
            saveAndExtract = SaveAndExtract { text, capturedAt, personaSel ->
                container.saveAndExtract(entryText = text, capturedAt = capturedAt, persona = personaSel)
            },
            clock = clock,
            zoneId = zoneId,
            initialReadiness = ModelReadiness.Ready,
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
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
