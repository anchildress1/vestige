package dev.anchildress1.vestige.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Route-level tests for CaptureScreen — verifies it picks the right layout for each
 * CaptureUiState variant and forwards the VM's open-entry event to the host. State
 * transitions themselves are covered in CaptureViewModelTest.
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
        val vm = idleViewModel(ModelReadiness.Ready)
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onNodeWithContentDescription(CaptureCopy.REC_LABEL_IDLE).assertIsDisplayed()
        composeRule.onNodeWithText("WHAT HAPPENED?", substring = true).assertIsDisplayed()
    }

    @Test
    fun `recording state renders LiveLayout chrome`() {
        val vm = parkedRecordingViewModel()
        vm.startRecording()
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        composeRule.onNodeWithText(CaptureCopy.LIVE_RECORDING_EYEBROW).assertIsDisplayed()
    }

    @Test
    fun `submitting state shows a spinner, not the old reading page`() {
        val vm = submittingViewModel()
        vm.startRecording()
        composeRule.setContent { VestigeTheme { captureScreen(vm) } }
        // Chrome is present, but neither the idle hero nor the recording eyebrow — and the old
        // "Reading the entry." review page is gone for good.
        composeRule.onNodeWithContentDescription("Gemma 4 local model. Local only.").assertIsDisplayed()
        composeRule.onAllNodesWithText("WHAT HAPPENED?", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText(CaptureCopy.LIVE_RECORDING_EYEBROW).assertCountEquals(0)
        composeRule.onAllNodesWithText("Reading the entry.").assertCountEquals(0)
    }

    @Test
    fun `bottom nav HISTORY tab fires onHistoryTap`() {
        var historyTaps = 0
        val vm = idleViewModel(ModelReadiness.Ready)
        composeRule.setContent {
            VestigeTheme {
                captureScreen(vm, chrome = IdleChromeCallbacks(onHistoryTap = { historyTaps += 1 }))
            }
        }
        composeRule.onNodeWithText("HISTORY")
            .assertExists()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, historyTaps) }
    }

    @Test
    fun `a finished capture forwards the new entry id to the host`() {
        var openedEntryId: Long? = null
        val vm = voiceViewModel(entryId = 99L)
        composeRule.setContent {
            VestigeTheme {
                captureScreen(vm, chrome = IdleChromeCallbacks(onOpenEntryDetail = { openedEntryId = it }))
            }
        }
        vm.startRecording()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(99L, openedEntryId) }
    }

    @Composable
    private fun captureScreen(vm: CaptureViewModel, chrome: IdleChromeCallbacks = IdleChromeCallbacks()) {
        CaptureScreen(viewModel = vm, chrome = chrome)
    }

    private fun idleViewModel(readiness: ModelReadiness): CaptureViewModel = CaptureViewModel(
        initialPersona = Persona.WITNESS,
        recordVoice = VoiceCapture { _, _ -> null },
        foregroundInference = ForegroundInferenceCall { _, _ -> error("unreached") },
        saveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> 1L },
        foregroundTextInference = ForegroundTextInferenceCall { _, _, _ -> error("unused") },
        clock = clock,
        zoneId = ZoneOffset.UTC,
        initialReadiness = readiness,
    )

    private fun parkedRecordingViewModel(): CaptureViewModel = CaptureViewModel(
        initialPersona = Persona.WITNESS,
        recordVoice = VoiceCapture { _, _ -> kotlinx.coroutines.suspendCancellableCoroutine { } },
        foregroundInference = ForegroundInferenceCall { _, _ -> error("unreached") },
        saveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> 1L },
        foregroundTextInference = ForegroundTextInferenceCall { _, _, _ -> error("unused") },
        clock = clock,
        zoneId = ZoneOffset.UTC,
        initialReadiness = ModelReadiness.Ready,
    )

    // Audio lands, but the foreground prompt parks before terminal — the VM stays in Submitting.
    private fun submittingViewModel(): CaptureViewModel = CaptureViewModel(
        initialPersona = Persona.WITNESS,
        recordVoice = VoiceCapture { _, _ -> AudioChunk(FloatArray(16), 16_000, isFinal = true) },
        foregroundInference = ForegroundInferenceCall { _, _ ->
            flow { kotlinx.coroutines.awaitCancellation() }
        },
        saveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> 1L },
        foregroundTextInference = ForegroundTextInferenceCall { _, _, _ -> error("unused") },
        clock = clock,
        zoneId = ZoneOffset.UTC,
        initialReadiness = ModelReadiness.Ready,
    )

    private fun voiceViewModel(entryId: Long): CaptureViewModel = CaptureViewModel(
        initialPersona = Persona.WITNESS,
        recordVoice = VoiceCapture { _, _ -> AudioChunk(FloatArray(16), 16_000, isFinal = true) },
        foregroundInference = ForegroundInferenceCall { _, _ ->
            flowOf(
                ForegroundStreamEvent.Transcription("something happened"),
                ForegroundStreamEvent.Terminal(
                    dev.anchildress1.vestige.inference.ForegroundResult.Success(
                        persona = Persona.WITNESS,
                        rawResponse = "",
                        elapsedMs = 0L,
                        completedAt = clock.instant(),
                        transcription = "something happened",
                        followUp = "what happened before that",
                    ),
                ),
            )
        },
        saveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> entryId },
        foregroundTextInference = ForegroundTextInferenceCall { _, _, _ ->
            flowOf(ForegroundStreamEvent.Transcription("something happened"))
        },
        clock = clock,
        zoneId = ZoneOffset.UTC,
        initialReadiness = ModelReadiness.Ready,
    )
}
