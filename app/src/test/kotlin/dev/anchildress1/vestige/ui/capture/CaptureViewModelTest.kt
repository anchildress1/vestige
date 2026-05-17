package dev.anchildress1.vestige.ui.capture

import app.cash.turbine.test
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // Cohesive state-machine suite; splitting would fragment the lifecycle coverage.
class CaptureViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-05-14T09:41:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle with injected persona and Loading readiness by default`() {
        val vm = newViewModel(persona = Persona.HARDASS)
        val state = vm.state.value
        assertTrue(state is CaptureUiState.Idle)
        assertEquals(Persona.HARDASS, state.persona)
        assertEquals(ModelReadiness.Loading, state.modelReadiness)
    }

    @Test
    fun `startRecording is gated on Ready readiness`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.startRecording()
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `successful voice flow runs Idle -- Recording -- Inferring -- Reviewing`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio).also { it.queueLevels(0.2f, 0.5f) }
        val save = RecordingSaveAndExtract()
        val vm = voiceVm("they asked again", "what did they actually want", voice, save)

        vm.state.test {
            assertTrue(awaitItem() is CaptureUiState.Idle)
            vm.startRecording()
            val recording = awaitItem()
            assertTrue("expected Recording, was $recording", recording is CaptureUiState.Recording)
            voice.emitNextLevel()
            val afterLevel = awaitItem()
            assertTrue(afterLevel is CaptureUiState.Recording)
            assertTrue((afterLevel as CaptureUiState.Recording).recentLevels.any { it > 0f })

            // Inferring → Reviewing collapses through StateFlow's conflation under
            // UnconfinedTestDispatcher (the post-completion path runs synchronously). Assert the
            // terminal Reviewing state directly; the Inferring beat is exercised on-device where
            // the foreground call's ~24-33 s wall-clock spans many frames.
            voice.completeWithResult()
            val reviewing = expectMostRecentItem()
            assertTrue("expected Reviewing, was $reviewing", reviewing is CaptureUiState.Reviewing)
            val review = (reviewing as CaptureUiState.Reviewing).review
            assertEquals("they asked again", review.transcription)
            assertEquals("what did they actually want", review.followUp)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, save.invocations.get())
    }

    @Test
    fun `voice path keeps call-1 transcription authoritative over call-2 echo`() = runTest(dispatcher) {
        // Option C: call 1 transcribes the audio; call 2 generates the history-conditioned
        // follow-up and echoes a transcription. The verbatim user words must come from call 1 —
        // call 2's echo ("garbled echo") must never overwrite them in Reviewing or in the save.
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flowOf(ForegroundStreamEvent.Transcription("i kept reopening it"))
            },
            textInference = ForegroundTextInferenceCall { _, _, _ ->
                flowOf(ForegroundStreamEvent.Terminal(successResult("garbled echo", "what were you avoiding")))
            },
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.state.test {
            assertTrue(awaitItem() is CaptureUiState.Idle)
            vm.startRecording()
            assertTrue(awaitItem() is CaptureUiState.Recording)
            voice.completeWithResult()

            val reviewing = expectMostRecentItem() as CaptureUiState.Reviewing
            assertEquals("i kept reopening it", reviewing.review.transcription)
            assertEquals("what were you avoiding", reviewing.review.followUp)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, save.invocations.get())
        assertEquals("save persists the call-1 transcription, not call-2's echo", "i kept reopening it", save.lastText)
    }

    @Test
    fun `voice flow passes audio durationMs to saveAndExtract`() = runTest(dispatcher) {
        // AudioChunk(FloatArray(16), 16_000) → durationMs = 16 * 1_000 / 16_000 = 1L
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract()
        val vm = voiceVm("they asked again", "what did they want", voice, save)

        vm.startRecording()
        voice.completeWithResult()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(audio.durationMs, save.lastDurationMs)
    }

    @Test
    fun `parse failure surfaces InferenceFailed PARSE_FAILED`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val inference = FakeForegroundInference(
            ForegroundResult.ParseFailure(
                persona = Persona.WITNESS,
                rawResponse = "",
                elapsedMs = 100,
                completedAt = clock.instant(),
                reason = ForegroundResult.ParseReason.EMPTY_RESPONSE,
            ),
        )
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            voice = voice,
            inference = inference,
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        val terminal = vm.state.value
        assertTrue(terminal is CaptureUiState.Idle)
        assertEquals(
            CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
            (terminal as CaptureUiState.Idle).error,
        )
        assertEquals(0, save.invocations.get())
    }

    @Test
    fun `inference engine failure surfaces InferenceFailed ENGINE_FAILED`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val inference = ForegroundInferenceCall { _, _ -> error("engine boom") }
        val vm = newViewModel(
            voice = voice,
            inference = inference,
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        val terminal = vm.state.value as CaptureUiState.Idle
        assertEquals(
            CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.ENGINE_FAILED),
            terminal.error,
        )
    }

    @Test
    fun `mic denied keeps state Idle and surfaces MicDenied`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.onMicDenied()
        val state = vm.state.value as CaptureUiState.Idle
        assertEquals(CaptureError.MicDenied, state.error)
    }

    @Test
    fun `mic permanently blocked surfaces MicBlocked, not MicDenied`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.onMicDenied(permanentlyBlocked = true)
        val state = vm.state.value as CaptureUiState.Idle
        assertEquals(CaptureError.MicBlocked, state.error)
    }

    @Test
    fun `dismissError clears the error in Idle`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.onMicDenied()
        vm.dismissError()
        assertNull((vm.state.value as CaptureUiState.Idle).error)
    }

    @Test
    fun `stopRecording is a no-op when not recording`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.stopRecording()
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `concurrent startRecording calls are idempotent`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val inference = FakeForegroundInference(successResult("x", "y"))
        val vm = newViewModel(
            voice = voice,
            inference = inference,
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        vm.startRecording()
        vm.startRecording()
        // Only one recording job runs; only one result is consumed.
        assertEquals(1, voice.invokeCount.get())
    }

    @Test
    fun `null audio returns Recording to Idle without running inference`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = null)
        val inferenceCalls = AtomicInteger(0)
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                inferenceCalls.incrementAndGet()
                terminal(parseFailure())
            },
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        assertTrue(vm.state.value is CaptureUiState.Recording)
        voice.completeWithResult()
        advanceUntilIdle()

        assertTrue(vm.state.value is CaptureUiState.Idle)
        assertEquals(0, inferenceCalls.get())
    }

    @Test
    fun `discard cancels mid-flight recording and returns to Idle (pos)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val vm = newViewModel(
            voice = voice,
            inference = FakeForegroundInference(successResult("", "")),
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        assertTrue(vm.state.value is CaptureUiState.Recording)
        vm.discard()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `discard emits stop signal before cancelling capture (edge — real AudioRecord gets interrupted)`() =
        runTest(dispatcher) {
            val stopSeen = CompletableDeferred<Unit>()
            val voice = VoiceCapture { _, stopFlow ->
                stopFlow.first()
                stopSeen.complete(Unit)
                null
            }
            val vm = newViewModel(
                voice = voice,
                inference = FakeForegroundInference(successResult("", "")),
                save = RecordingSaveAndExtract(),
                initialReadiness = ModelReadiness.Ready,
            )

            vm.startRecording()
            vm.discard()
            advanceUntilIdle()

            assertTrue("discard must notify the capture adapter before cancellation", stopSeen.isCompleted)
            assertTrue(vm.state.value is CaptureUiState.Idle)
        }

    @Test
    fun `discard from Idle is a no-op (neg — only available during RECORDING)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = null)
        val vm = newViewModel(
            voice = voice,
            inference = FakeForegroundInference(successResult("", "")),
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        val before = vm.state.value
        vm.discard()
        advanceUntilIdle()
        assertEquals(before, vm.state.value)
    }

    @Test
    fun `discard from Inferring is a no-op (neg — out of scope after STOP)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val pending = CompletableDeferred<ForegroundResult>()
        val vm = newViewModel(
            voice = voice,
            inference = SuspendingForegroundInference(pending),
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Inferring)

        vm.discard()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Inferring)
        pending.complete(successResult("", ""))
        advanceUntilIdle()
    }

    @Test
    fun `acknowledgeReview retains lastReview on Idle`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val vm = voiceVm("hello", "what next", voice, save = RecordingSaveAndExtract())
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Reviewing)
        vm.acknowledgeReview()
        val idle = vm.state.value as CaptureUiState.Idle
        assertEquals("hello", idle.lastReview?.transcription)
    }

    @Test
    fun `voice path queries retrieval with the call-1 transcription and threads history to save`() =
        runTest(dispatcher) {
            val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
            val history = listOf(HistoryChunk(patternId = null, text = "a prior entry about the same loop"))
            val lookup = RecordingHistoryRetrieval(history)
            val save = RecordingSaveAndExtract()
            val vm = newViewModel(
                voice = voice,
                inference = ForegroundInferenceCall { _, _ ->
                    flowOf(ForegroundStreamEvent.Transcription("i keep reopening the same ticket"))
                },
                textInference = ForegroundTextInferenceCall { t, _, h ->
                    // call 2 must receive the looked-up history
                    assertEquals(history, h)
                    flowOf(ForegroundStreamEvent.Terminal(successResult(t, "what pulls you back to it")))
                },
                save = save,
                retrieveHistory = lookup,
                initialReadiness = ModelReadiness.Ready,
            )

            vm.startRecording()
            voice.completeWithResult()
            advanceUntilIdle()

            assertEquals("i keep reopening the same ticket", lookup.lastQuery)
            assertEquals(1, lookup.calls.get())
            assertEquals(history, save.lastHistory)
            assertTrue(vm.state.value is CaptureUiState.Reviewing)
        }

    @Test
    fun `typed path queries retrieval with the typed text and threads history to save`() = runTest(dispatcher) {
        val history = listOf(HistoryChunk(patternId = null, text = "earlier note"))
        val lookup = RecordingHistoryRetrieval(history)
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            save = save,
            textInference = ForegroundTextInferenceCall { t, _, h ->
                assertEquals(history, h)
                flowOf(ForegroundStreamEvent.Terminal(successResult(t, "and then what")))
            },
            retrieveHistory = lookup,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.submitTyped("just got off the call again")
        advanceUntilIdle()

        assertEquals("just got off the call again", lookup.lastQuery)
        assertEquals(history, save.lastHistory)
        assertEquals(1, save.invocations.get())
    }

    @Test
    fun `lookup failure degrades to empty history and the capture still completes`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flowOf(ForegroundStreamEvent.Transcription("words the lookup will choke on"))
            },
            textInference = ForegroundTextInferenceCall { t, _, h ->
                assertTrue("a degraded lookup must pass empty history", h.isEmpty())
                flowOf(ForegroundStreamEvent.Terminal(successResult(t, "still asks a question")))
            },
            save = save,
            retrieveHistory = HistoryRetrieval { error("history store unavailable") },
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertTrue("capture must complete despite a failed lookup", vm.state.value is CaptureUiState.Reviewing)
        assertEquals(1, save.invocations.get())
        assertTrue(save.lastHistory.isEmpty())
    }

    @Test
    fun `submitTyped below minimum length is ignored`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.submitTyped("hi")
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `submitTyped runs the foreground text call and reviews with the model follow-up`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            save = save,
            textInference = ForegroundTextInferenceCall { text, persona, _ ->
                terminal(
                    ForegroundResult.Success(
                        persona = persona,
                        rawResponse = "<x/>",
                        elapsedMs = 800,
                        completedAt = clock.instant(),
                        transcription = text,
                        followUp = "and then what",
                    ),
                )
            },
            initialReadiness = ModelReadiness.Ready,
        )

        vm.submitTyped("just got off the call again")
        advanceUntilIdle()

        assertEquals(1, save.invocations.get())
        val reviewing = vm.state.value as CaptureUiState.Reviewing
        assertEquals("just got off the call again", reviewing.review.transcription)
        assertEquals("and then what", reviewing.review.followUp)
    }

    @Test
    fun `submitTyped is a silent no-op when the model is not Ready (parity with disabled REC)`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract()
        val textCalls = AtomicInteger(0)
        val vm = newViewModel(
            save = save,
            textInference = ForegroundTextInferenceCall { _, _, _ ->
                textCalls.incrementAndGet()
                terminal(parseFailure())
            },
            initialReadiness = ModelReadiness.Loading,
        )

        vm.submitTyped("just typed it")
        advanceUntilIdle()

        assertTrue(vm.state.value is CaptureUiState.Idle)
        assertEquals(0, save.invocations.get())
        assertEquals(0, textCalls.get())
    }

    @Test
    fun `submitTyped parse failure surfaces InferenceFailed PARSE_FAILED`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            save = save,
            textInference = ForegroundTextInferenceCall { _, _, _ -> terminal(parseFailure()) },
            initialReadiness = ModelReadiness.Ready,
        )

        vm.submitTyped("typed but the model choked")
        advanceUntilIdle()

        val idle = vm.state.value as CaptureUiState.Idle
        assertEquals(
            CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
            idle.error,
        )
        assertEquals(0, save.invocations.get())
    }

    @Test
    fun `setModelReadiness flips chrome across phases without losing other slots`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Loading)
        vm.setModelReadiness(ModelReadiness.Ready)
        assertEquals(ModelReadiness.Ready, vm.state.value.modelReadiness)
    }

    @Test
    fun `setModelReadiness updates Recording Inferring and Reviewing phases`() = runTest(dispatcher) {
        val recordingVoice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val recordingVm = newViewModel(voice = recordingVoice, initialReadiness = ModelReadiness.Ready)
        recordingVm.startRecording()
        recordingVm.setModelReadiness(ModelReadiness.Paused)
        assertEquals(ModelReadiness.Paused, recordingVm.state.value.modelReadiness)
        recordingVm.discard()
        advanceUntilIdle()

        val pending = CompletableDeferred<ForegroundResult>()
        val inferringVoice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val inferringVm = newViewModel(
            voice = inferringVoice,
            inference = SuspendingForegroundInference(pending),
            initialReadiness = ModelReadiness.Ready,
        )
        inferringVm.startRecording()
        inferringVoice.completeWithResult()
        advanceUntilIdle()
        inferringVm.setModelReadiness(ModelReadiness.Downloading(25))
        assertEquals(ModelReadiness.Downloading(25), inferringVm.state.value.modelReadiness)
        pending.complete(parseFailure())
        advanceUntilIdle()

        val reviewingVm = reviewedViewModel()
        reviewingVm.setModelReadiness(ModelReadiness.Loading)
        assertEquals(ModelReadiness.Loading, reviewingVm.state.value.modelReadiness)
    }

    @Test
    fun `setPersona is reflected across phases`() {
        val vm = newViewModel(persona = Persona.WITNESS, initialReadiness = ModelReadiness.Ready)
        vm.setPersona(Persona.EDITOR)
        assertEquals(Persona.EDITOR, vm.state.value.persona)
    }

    @Test
    fun `setPersona updates Recording Inferring and Reviewing phases`() = runTest(dispatcher) {
        val recordingVoice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val recordingVm = newViewModel(voice = recordingVoice, initialReadiness = ModelReadiness.Ready)
        recordingVm.startRecording()
        recordingVm.setPersona(Persona.HARDASS)
        assertEquals(Persona.HARDASS, recordingVm.state.value.persona)
        recordingVm.discard()
        advanceUntilIdle()

        val pending = CompletableDeferred<ForegroundResult>()
        val inferringVoice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val inferringVm = newViewModel(
            voice = inferringVoice,
            inference = SuspendingForegroundInference(pending),
            initialReadiness = ModelReadiness.Ready,
        )
        inferringVm.startRecording()
        inferringVoice.completeWithResult()
        advanceUntilIdle()
        inferringVm.setPersona(Persona.EDITOR)
        assertEquals(Persona.EDITOR, inferringVm.state.value.persona)
        pending.complete(parseFailure())
        advanceUntilIdle()

        val reviewingVm = reviewedViewModel()
        reviewingVm.setPersona(Persona.HARDASS)
        assertEquals(Persona.HARDASS, reviewingVm.state.value.persona)
    }

    @Test
    fun `Idle-only events ignore non-idle phases`() = runTest(dispatcher) {
        val vm = reviewedViewModel()

        vm.dismissError()
        vm.acknowledgeReview()
        vm.onMicDenied()

        val idle = vm.state.value as CaptureUiState.Idle
        assertEquals(CaptureError.MicDenied, idle.error)
    }

    // ─── 30s cap audio cue (pre-warn at 28s, single fire) ────────────────────

    @Test
    fun `limit warning cue fires once when elapsed crosses 28s (pos)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val cue = CountingLimitWarningCue()
        val advancing = AdvancingClock()
        val vm = newViewModel(
            voice = voice,
            initialReadiness = ModelReadiness.Ready,
            clockOverride = advancing,
            limitWarningCue = cue,
        )
        voice.queueLevels(0.1f, 0.2f, 0.3f)

        vm.startRecording()
        advancing.offsetMs = 5_000L
        voice.emitNextLevel()
        assertEquals("no cue before threshold", 0, cue.fireCount.get())

        advancing.offsetMs = 28_001L
        voice.emitNextLevel()
        assertEquals("first cross of the 28s line fires the cue", 1, cue.fireCount.get())

        advancing.offsetMs = 29_500L
        voice.emitNextLevel()
        assertEquals("subsequent level updates past the threshold do not re-fire", 1, cue.fireCount.get())
    }

    @Test
    fun `limit warning cue does not fire when recording stops before 28s (neg)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val cue = CountingLimitWarningCue()
        val advancing = AdvancingClock()
        val vm = newViewModel(
            voice = voice,
            initialReadiness = ModelReadiness.Ready,
            clockOverride = advancing,
            limitWarningCue = cue,
        )
        voice.queueLevels(0.1f, 0.2f)

        vm.startRecording()
        advancing.offsetMs = 10_000L
        voice.emitNextLevel()
        advancing.offsetMs = 27_999L
        voice.emitNextLevel()

        assertEquals(0, cue.fireCount.get())
    }

    @Test
    fun `limit warning cue resets between recordings (edge — fresh session re-arms)`() = runTest(dispatcher) {
        val voiceA = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val voiceB = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val cue = CountingLimitWarningCue()
        val advancing = AdvancingClock()
        var active: FakeVoiceCapture = voiceA
        val routing = VoiceCapture { onLevel, stopFlow -> active.invoke(onLevel, stopFlow) }
        val vm = voiceVm(
            transcription = "",
            followUp = "",
            voice = routing,
            save = RecordingSaveAndExtract(),
            clockOverride = advancing,
            limitWarningCue = cue,
        )

        voiceA.queueLevels(0.1f)
        vm.startRecording()
        advancing.offsetMs = 28_500L
        voiceA.emitNextLevel()
        assertEquals(1, cue.fireCount.get())

        voiceA.completeWithResult()
        advanceUntilIdle()
        vm.acknowledgeReview()

        active = voiceB
        voiceB.queueLevels(0.1f)
        advancing.offsetMs = 50_000L
        vm.startRecording()
        advancing.offsetMs = 78_500L
        voiceB.emitNextLevel()
        assertEquals("second recording must re-arm and fire again past its own threshold", 2, cue.fireCount.get())
    }

    @Suppress("LongParameterList")
    private fun newViewModel(
        persona: Persona = Persona.WITNESS,
        voice: VoiceCapture = VoiceCapture { _, _ -> null },
        inference: ForegroundInferenceCall = ForegroundInferenceCall { _, _ ->
            error("inference call not expected in this test")
        },
        save: SaveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> },
        textInference: ForegroundTextInferenceCall = ForegroundTextInferenceCall { _, _, _ ->
            error("text inference call not expected in this test")
        },
        retrieveHistory: HistoryRetrieval = HistoryRetrieval { emptyList() },
        initialReadiness: ModelReadiness = ModelReadiness.Loading,
        clockOverride: Clock = clock,
        limitWarningCue: LimitWarningCue = LimitWarningCue {},
    ): CaptureViewModel = CaptureViewModel(
        initialPersona = persona,
        recordVoice = voice,
        foregroundInference = inference,
        saveAndExtract = save,
        foregroundTextInference = textInference,
        retrieveHistory = retrieveHistory,
        clock = clockOverride,
        zoneId = ZoneOffset.UTC,
        initialReadiness = initialReadiness,
        limitWarningCue = limitWarningCue,
    )

    // Wires both legs of the option-C voice flow: call 1 yields the transcription, call 2 yields
    // the history-conditioned follow-up. transcriptionOverride means call 2's echo is irrelevant.
    @Suppress("LongParameterList")
    private fun voiceVm(
        transcription: String,
        followUp: String,
        voice: VoiceCapture,
        save: SaveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> },
        initialReadiness: ModelReadiness = ModelReadiness.Ready,
        clockOverride: Clock = clock,
        limitWarningCue: LimitWarningCue = LimitWarningCue {},
        retrieveHistory: HistoryRetrieval = HistoryRetrieval { emptyList() },
    ): CaptureViewModel = newViewModel(
        voice = voice,
        inference = ForegroundInferenceCall { _, _ -> flowOf(ForegroundStreamEvent.Transcription(transcription)) },
        textInference = ForegroundTextInferenceCall { t, _, _ ->
            flowOf(ForegroundStreamEvent.Terminal(successResult(t, followUp)))
        },
        save = save,
        retrieveHistory = retrieveHistory,
        initialReadiness = initialReadiness,
        clockOverride = clockOverride,
        limitWarningCue = limitWarningCue,
    )

    private fun reviewedViewModel(): CaptureViewModel {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val vm = voiceVm("review me", "already reviewed", voice, save = RecordingSaveAndExtract())
        vm.startRecording()
        voice.completeWithResult()
        return vm
    }

    private fun successResult(transcription: String, followUp: String): ForegroundResult.Success =
        ForegroundResult.Success(
            persona = Persona.WITNESS,
            rawResponse = "",
            elapsedMs = 0L,
            completedAt = clock.instant(),
            transcription = transcription,
            followUp = followUp,
        )

    private fun parseFailure(): ForegroundResult.ParseFailure = ForegroundResult.ParseFailure(
        persona = Persona.WITNESS,
        rawResponse = "",
        elapsedMs = 0,
        completedAt = clock.instant(),
        reason = ForegroundResult.ParseReason.EMPTY_RESPONSE,
    )

    // Wraps a final ForegroundResult as a single-Terminal stream — the streaming surface's
    // collapsed shape for tests that only care about the terminal verdict, not live deltas.
    private fun terminal(result: ForegroundResult): Flow<ForegroundStreamEvent> =
        flowOf(ForegroundStreamEvent.Terminal(result))
}

// Option-C voice call 1: a Success surfaces only as the Transcription event (the VM cancels the
// rest and runs call 2 for the follow-up); a ParseFailure surfaces as a Terminal with no
// Transcription, which the VM maps to PARSE_FAILED.
private class FakeForegroundInference(private val result: ForegroundResult) : ForegroundInferenceCall {
    override suspend fun invoke(audio: AudioChunk, persona: Persona): Flow<ForegroundStreamEvent> = when (result) {
        is ForegroundResult.Success -> flowOf(ForegroundStreamEvent.Transcription(result.transcription))
        is ForegroundResult.ParseFailure -> flowOf(ForegroundStreamEvent.Terminal(result))
    }
}

private class SuspendingForegroundInference(private val pending: CompletableDeferred<ForegroundResult>) :
    ForegroundInferenceCall {
    // The flow body awaits `pending` before emitting, so the VM stays in Inferring until the
    // test releases it — preserving the pre-streaming "suspended call" semantics these tests rely on.
    override suspend fun invoke(audio: AudioChunk, persona: Persona): Flow<ForegroundStreamEvent> =
        flow { emit(ForegroundStreamEvent.Terminal(pending.await())) }
}

private class RecordingHistoryRetrieval(private val result: List<HistoryChunk>) : HistoryRetrieval {
    val calls: AtomicInteger = AtomicInteger(0)
    var lastQuery: String? = null
    override suspend fun invoke(query: String): List<HistoryChunk> {
        calls.incrementAndGet()
        lastQuery = query
        return result
    }
}

private class CountingLimitWarningCue : LimitWarningCue {
    val fireCount: AtomicInteger = AtomicInteger(0)
    override fun fire() {
        fireCount.incrementAndGet()
    }
}

private class AdvancingClock(start: Instant = Instant.parse("2026-05-14T09:41:00Z")) : Clock() {
    private val baseline: Instant = start
    var offsetMs: Long = 0L
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = baseline.plusMillis(offsetMs)
}

private class RecordingSaveAndExtract : SaveAndExtract {
    val invocations: AtomicInteger = AtomicInteger(0)
    var lastDurationMs: Long = -1L
    var lastText: String? = null
    var lastHistory: List<HistoryChunk> = emptyList()
    override suspend fun invoke(
        text: String,
        capturedAt: java.time.ZonedDateTime,
        persona: Persona,
        durationMs: Long,
        followUpText: String?,
        retrievedHistory: List<HistoryChunk>,
    ) {
        invocations.incrementAndGet()
        lastDurationMs = durationMs
        lastText = text
        lastHistory = retrievedHistory
    }
}

/**
 * Drives the VM's recording job deterministically: tests call [emitNextLevel] to push pending
 * levels and [completeWithResult] to return the queued audio chunk. The driver itself suspends
 * until the test releases it.
 */
private class FakeVoiceCapture(private val result: AudioChunk?) : VoiceCapture {
    val invokeCount: AtomicInteger = AtomicInteger(0)
    private val pendingLevels: ArrayDeque<Float> = ArrayDeque()
    private val completion: CompletableDeferred<AudioChunk?> = CompletableDeferred()
    private var levelEmitter: ((Float) -> Unit)? = null

    fun queueLevels(vararg levels: Float) {
        pendingLevels.addAll(levels.toList())
    }

    fun emitNextLevel() {
        val level = pendingLevels.removeFirstOrNull() ?: return
        levelEmitter?.invoke(level)
    }

    fun completeWithResult() {
        completion.complete(result)
    }

    override suspend fun invoke(onLevel: (Float) -> Unit, stopFlow: Flow<Unit>): AudioChunk? {
        invokeCount.incrementAndGet()
        levelEmitter = onLevel
        return completion.await()
    }
}
