package dev.anchildress1.vestige.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Route-level tests for CaptureScreen — verifies it picks the right layout for each CaptureUiState
 * variant and exposes the canonical content descriptions. State transitions themselves are covered
 * in CaptureViewModelTest; this suite checks the View-to-State binding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class CaptureScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-14T09:41:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `idle state renders IdleLayout content`() {
        val vm = newViewModel(readiness = ModelReadiness.Ready)
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onNodeWithContentDescription(CaptureCopy.REC_LABEL_IDLE).assertIsDisplayed()
        composeRule.onNodeWithText("WHAT JUST HAPPENED?", substring = true).assertIsDisplayed()
    }

    @Test
    fun `recording state renders LiveLayout chrome`() {
        // VoiceCapture suspends forever — state stays in Recording for the duration of the test.
        val vm = CaptureViewModel(
            initialPersona = Persona.WITNESS,
            recordVoice = VoiceCapture { _, _ ->
                kotlinx.coroutines.suspendCancellableCoroutine { /* park */ }
            },
            foregroundInference = ForegroundInferenceCall { _, _ -> error("unreached") },
            saveAndExtract = SaveAndExtract { _, _, _, _, _ -> },
            foregroundTextInference = ForegroundTextInferenceCall { _, _ -> error("unused") },
            clock = clock,
            zoneId = ZoneOffset.UTC,
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onNodeWithText(CaptureCopy.LIVE_RECORDING_EYEBROW).assertIsDisplayed()
    }

    @Test
    fun `inferring state renders the Reading the entry placeholder`() {
        val vm = newViewModel(readiness = ModelReadiness.Ready, startInInferringPhase = true)
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onNodeWithText(CaptureCopy.READING_PLACEHOLDER).assertIsDisplayed()
    }

    // --- Capture footer tests ---

    @Test
    fun `footer is hidden when lastEntryFooter is null`() {
        val vm = newViewModel(readiness = ModelReadiness.Ready)
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onAllNodesWithText(CaptureCopy.HISTORY_FOOTER_PREFIX).assertCountEquals(0)
        composeRule.onAllNodesWithTag("history_footer_link").assertCountEquals(0)
    }

    @Test
    fun `footer renders prefix date and duration when lastEntryFooter is present`() {
        val vm = newViewModel(readiness = ModelReadiness.Ready)
        val footer = LastEntryFooter(monthLabel = "JAN", dayLabel = "27", durationLabel = "4m 02s")
        composeRule.setContent {
            VestigeTheme { captureScreen(vm, chrome = IdleChromeCallbacks(lastEntryFooter = footer)) }
        }
        // Use count checks: footer is in composition but may be below viewport in test.
        composeRule.onAllNodesWithText(CaptureCopy.HISTORY_FOOTER_PREFIX, substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("JAN", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("27", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("4m 02s", substring = true).assertCountEquals(1)
    }

    @Test
    fun `History link is clickable with correct contentDescription`() {
        val vm = newViewModel(readiness = ModelReadiness.Ready)
        val footer = LastEntryFooter(monthLabel = "JAN", dayLabel = "27", durationLabel = "4m 02s")
        composeRule.setContent {
            VestigeTheme {
                captureScreen(vm, chrome = IdleChromeCallbacks(lastEntryFooter = footer, onHistoryTap = {}))
            }
        }
        composeRule.onNodeWithContentDescription(CaptureCopy.HISTORY_LINK_A11Y).assertHasClickAction()
    }

    // --- Reviewing state tests ---

    @Test
    fun `reviewing state renders DONE button`() {
        val vm = newReviewingViewModel()
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
    }

    @Test
    fun `reviewing state history link present when onOpenHistory provided`() {
        val vm = newReviewingViewModel()
        composeRule.setContent {
            VestigeTheme { captureScreen(vm, chrome = IdleChromeCallbacks(onHistoryTap = {})) }
        }
        composeRule.onNodeWithContentDescription(CaptureCopy.HISTORY_LINK_A11Y).assertHasClickAction()
    }

    @Test
    fun `reviewing state history link absent when onOpenHistory is null`() {
        val vm = newReviewingViewModel()
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onAllNodesWithText(CaptureCopy.HISTORY_LINK).assertCountEquals(0)
    }

    @Test
    fun `History link tap target is at least 48 dp tall`() {
        val vm = newViewModel(readiness = ModelReadiness.Ready)
        val footer = LastEntryFooter(monthLabel = "JAN", dayLabel = "27", durationLabel = "4m 02s")
        composeRule.setContent {
            VestigeTheme {
                captureScreen(vm, chrome = IdleChromeCallbacks(lastEntryFooter = footer, onHistoryTap = {}))
            }
        }
        composeRule.onNodeWithTag("history_footer_link").assertHeightIsAtLeast(48.dp)
    }

    @Composable
    private fun captureScreen(vm: CaptureViewModel, chrome: IdleChromeCallbacks = IdleChromeCallbacks()) {
        CaptureScreen(
            viewModel = vm,
            stats = CaptureStats(kept = 0, active = 0, hitsThisMonth = 0, cloud = 0),
            meta = CaptureMeta(
                weekdayLabel = "THU",
                monthDayLabel = "MAY 14",
                timeLabel = "09:41",
                dayNumber = 1,
                streakDays = 0,
            ),
            chrome = chrome,
        )
    }

    private fun newReviewingViewModel(): CaptureViewModel {
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        return CaptureViewModel(
            initialPersona = Persona.WITNESS,
            recordVoice = VoiceCapture { _, _ -> audio },
            foregroundInference = ForegroundInferenceCall { _, _ ->
                flowOf(
                    ForegroundStreamEvent.Terminal(
                        ForegroundResult.Success(
                            persona = Persona.WITNESS,
                            rawResponse = "",
                            elapsedMs = 0L,
                            completedAt = clock.instant(),
                            transcription = "something happened",
                            followUp = "sounds like a pattern",
                        ),
                    ),
                )
            },
            saveAndExtract = SaveAndExtract { _, _, _, _, _ -> },
            foregroundTextInference = ForegroundTextInferenceCall { _, _ -> error("unused") },
            clock = clock,
            zoneId = ZoneOffset.UTC,
            initialReadiness = ModelReadiness.Ready,
        ).also { it.startRecording() }
    }

    private fun newViewModel(readiness: ModelReadiness, startInInferringPhase: Boolean = false): CaptureViewModel {
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        return CaptureViewModel(
            initialPersona = Persona.WITNESS,
            // Recording driver completes immediately when triggered; not used for the Inferring
            // case which is reached only via a real engine path on-device.
            recordVoice = VoiceCapture { _, _ -> if (startInInferringPhase) audio else null },
            foregroundInference = ForegroundInferenceCall { _, _ ->
                // Suspend forever for the Inferring-phase test — VM stays in Inferring until
                // cancellation. The test only verifies that the route reaches the placeholder.
                kotlinx.coroutines.suspendCancellableCoroutine { /* park */ }
            },
            saveAndExtract = SaveAndExtract { _, _, _, _, _ -> },
            foregroundTextInference = ForegroundTextInferenceCall { _, _ -> error("unused") },
            clock = clock,
            zoneId = ZoneOffset.UTC,
            initialReadiness = readiness,
        ).also { if (startInInferringPhase) it.startRecording() }
    }
}
