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
@Suppress("LargeClass") // One cohesive VM state-machine suite — mic/model/persona/record/submit paths.
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
    fun `successful voice flow persists the entry, opens it, and returns Capture to Idle`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio).also { it.queueLevels(0.2f, 0.5f) }
        val save = RecordingSaveAndExtract(entryId = 42L)
        val attach = RecordingAttachFollowUp()
        val vm = voiceVm("they asked again", "what did they actually want", voice, save, attach)

        vm.openEntryEvents.test {
            vm.state.test {
                assertTrue(awaitItem() is CaptureUiState.Idle)
                vm.startRecording()
                assertTrue(awaitItem() is CaptureUiState.Recording)
                voice.emitNextLevel()
                assertTrue(awaitItem() is CaptureUiState.Recording)

                voice.completeWithResult()
                advanceUntilIdle()
                assertTrue(
                    "capture stays Submitting until the UI consumes the open-entry event",
                    expectMostRecentItem() is CaptureUiState.Submitting,
                )
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("host is told to open the persisted entry", 42L, awaitItem())
            vm.onOpenEntryHandled()
            assertTrue("capture resets only after the navigation handoff lands", vm.state.value is CaptureUiState.Idle)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, save.invocations.get())
        assertEquals("save persists the call-1 transcription", "they asked again", save.lastText)
        assertNull("entry persists without a follow-up; it lands later", save.lastFollowUpText)
        assertEquals(42L, attach.lastEntryId)
        assertEquals("what did they actually want", attach.lastFollowUp)
    }

    @Test
    fun `voice path keeps call-1 transcription authoritative over call-2 echo`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract(entryId = 7L)
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

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertEquals("save persists call-1, not call-2's echo", "i kept reopening it", save.lastText)
        assertEquals(1, save.invocations.get())
    }

    @Test
    fun `voice flow passes audio durationMs to saveAndExtract`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract()
        val vm = voiceVm("they asked again", "what did they want", voice, save)

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertEquals(audio.durationMs, save.lastDurationMs)
    }

    @Test
    fun `blank call-1 transcription surfaces PARSE_FAILED and never persists`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ -> flowOf(ForegroundStreamEvent.Transcription("   ")) },
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        val terminal = vm.state.value as CaptureUiState.Idle
        assertEquals(
            CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
            terminal.error,
        )
        assertEquals(0, save.invocations.get())
    }

    @Test
    fun `no transcription event surfaces PARSE_FAILED`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flowOf(ForegroundStreamEvent.Terminal(parseFailure()))
            },
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        val idle = vm.state.value as CaptureUiState.Idle
        assertEquals(
            CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
            idle.error,
        )
    }

    @Test
    fun `terminal foreground success persists transcription when stream scanner emitted no event`() =
        runTest(dispatcher) {
            val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
            val voice = FakeVoiceCapture(result = audio)
            val save = RecordingSaveAndExtract()
            val vm = newViewModel(
                voice = voice,
                inference = ForegroundInferenceCall { _, _ ->
                    flowOf(ForegroundStreamEvent.Terminal(successResult("terminal-only words", "what got missed?")))
                },
                textInference = ForegroundTextInferenceCall { _, _, _ ->
                    flowOf(ForegroundStreamEvent.Terminal(successResult("terminal-only words", "what got missed?")))
                },
                save = save,
                initialReadiness = ModelReadiness.Ready,
            )

            vm.startRecording()
            voice.completeWithResult()
            advanceUntilIdle()

            assertEquals("terminal-only words", save.lastText)
            assertEquals(1, save.invocations.get())
        }

    @Test
    fun `parse failure with recovered transcription still persists the entry`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract(entryId = 4L)
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flowOf(ForegroundStreamEvent.Terminal(parseFailure(recoveredTranscription = "recovered words")))
            },
            textInference = ForegroundTextInferenceCall { _, _, _ ->
                flowOf(ForegroundStreamEvent.Terminal(successResult("recovered words", "what did you leave out?")))
            },
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertEquals("recovered words", save.lastText)
        assertEquals(1, save.invocations.get())
        assertTrue(vm.state.value is CaptureUiState.Submitting)
    }

    @Test
    fun `inference engine failure on call-1 surfaces ENGINE_FAILED`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ -> error("engine boom") },
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        val idle = vm.state.value as CaptureUiState.Idle
        assertEquals(
            CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.ENGINE_FAILED),
            idle.error,
        )
    }

    @Test
    fun `call-2 failure still keeps the persisted entry and never errors Capture`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract(entryId = 9L)
        val attach = RecordingAttachFollowUp()
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flowOf(ForegroundStreamEvent.Transcription("the exact spoken words"))
            },
            textInference = ForegroundTextInferenceCall { _, _, _ -> error("call-2 boom") },
            save = save,
            attachFollowUp = attach,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertEquals("entry still saved", 1, save.invocations.get())
        assertTrue(
            "capture stays Submitting until the UI consumes the open-entry event",
            vm.state.value is CaptureUiState.Submitting,
        )
        vm.onOpenEntryHandled()
        assertTrue("capture is a clean Idle, not an error", vm.state.value is CaptureUiState.Idle)
        assertNull("no error band — the entry is safe", (vm.state.value as CaptureUiState.Idle).error)
        assertNull("no follow-up attached on a failed call-2", attach.lastFollowUp)
    }

    @Test
    fun `mic denied keeps state Idle and surfaces MicDenied`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.onMicDenied()
        assertEquals(CaptureError.MicDenied, (vm.state.value as CaptureUiState.Idle).error)
    }

    @Test
    fun `mic permanently blocked surfaces MicBlocked, not MicDenied`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.onMicDenied(permanentlyBlocked = true)
        assertEquals(CaptureError.MicBlocked, (vm.state.value as CaptureUiState.Idle).error)
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
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ -> flowOf(ForegroundStreamEvent.Transcription("x")) },
            textInference = ForegroundTextInferenceCall { _, _, _ ->
                flowOf(ForegroundStreamEvent.Terminal(successResult("x", "y")))
            },
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        vm.startRecording()
        vm.startRecording()
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
                flowOf(ForegroundStreamEvent.Terminal(parseFailure()))
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
            inference = ForegroundInferenceCall { _, _ -> flowOf(ForegroundStreamEvent.Transcription("x")) },
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
    fun `discard emits stop signal before cancelling capture (edge)`() = runTest(dispatcher) {
        val stopSeen = CompletableDeferred<Unit>()
        val voice = VoiceCapture { _, stopFlow ->
            stopFlow.first()
            stopSeen.complete(Unit)
            null
        }
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ -> flowOf(ForegroundStreamEvent.Transcription("x")) },
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
    fun `discard from Idle is a no-op (neg)`() = runTest(dispatcher) {
        val vm = newViewModel(voice = FakeVoiceCapture(result = null), initialReadiness = ModelReadiness.Ready)
        val before = vm.state.value
        vm.discard()
        advanceUntilIdle()
        assertEquals(before, vm.state.value)
    }

    @Test
    fun `discard from Submitting is a no-op (neg — out of scope after STOP)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val release = CompletableDeferred<Unit>()
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flow {
                    release.await()
                    emit(ForegroundStreamEvent.Transcription("words"))
                }
            },
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Submitting)

        vm.discard()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Submitting)
        release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `voice path opens entry before lookup resolves and threads history only to call-2`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val lookupRelease = CompletableDeferred<Unit>()
        val lookupCalls = AtomicInteger(0)
        var lookupQuery: String? = null
        val history = listOf(HistoryChunk(patternId = null, text = "a prior entry about the same loop"))
        val save = RecordingSaveAndExtract(entryId = 88L)
        val attach = RecordingAttachFollowUp()
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flowOf(ForegroundStreamEvent.Transcription("i keep reopening the same ticket"))
            },
            textInference = ForegroundTextInferenceCall { _, _, h ->
                assertEquals(history, h)
                flowOf(ForegroundStreamEvent.Terminal(successResult("echo", "what pulls you back")))
            },
            save = save,
            attachFollowUp = attach,
            lookupHistory = HistoryRetrieval { query ->
                lookupCalls.incrementAndGet()
                lookupQuery = query
                lookupRelease.await()
                history
            },
            initialReadiness = ModelReadiness.Ready,
        )

        vm.openEntryEvents.test {
            vm.startRecording()
            voice.completeWithResult()
            advanceUntilIdle()

            assertEquals(88L, awaitItem())
            assertEquals(1, save.invocations.get())
            assertTrue("foreground save must no longer wait for retrieval", save.lastHistory.isEmpty())
            assertEquals(1, lookupCalls.get())
            assertNull("follow-up must still be waiting on retrieval", attach.lastFollowUp)

            vm.onOpenEntryHandled()
            lookupRelease.complete(Unit)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("i keep reopening the same ticket", lookupQuery)
        assertEquals("what pulls you back", attach.lastFollowUp)
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
            textInference = ForegroundTextInferenceCall { _, _, h ->
                assertTrue("a degraded lookup must pass empty history", h.isEmpty())
                flowOf(ForegroundStreamEvent.Terminal(successResult("echo", "still asks")))
            },
            save = save,
            lookupHistory = HistoryRetrieval { error("history store unavailable") },
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

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
    fun `submitTyped persists, opens the entry and attaches the follow-up`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract(entryId = 5L)
        val attach = RecordingAttachFollowUp()
        val vm = newViewModel(
            save = save,
            attachFollowUp = attach,
            textInference = ForegroundTextInferenceCall { text, persona, _ ->
                flowOf(
                    ForegroundStreamEvent.Terminal(
                        ForegroundResult.Success(
                            persona = persona,
                            rawResponse = "<x/>",
                            elapsedMs = 800,
                            completedAt = clock.instant(),
                            transcription = text,
                            followUp = "and then what",
                        ),
                    ),
                )
            },
            initialReadiness = ModelReadiness.Ready,
        )

        vm.openEntryEvents.test {
            vm.submitTyped("just got off the call again")
            advanceUntilIdle()
            assertEquals(5L, awaitItem())
            assertTrue(vm.state.value is CaptureUiState.Submitting)
            vm.onOpenEntryHandled()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, save.invocations.get())
        assertEquals("just got off the call again", save.lastText)
        assertTrue(vm.state.value is CaptureUiState.Idle)
        assertEquals(5L, attach.lastEntryId)
        assertEquals("and then what", attach.lastFollowUp)
    }

    @Test
    fun `submitTyped is a silent no-op when the model is not Ready`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract()
        val textCalls = AtomicInteger(0)
        val vm = newViewModel(
            save = save,
            textInference = ForegroundTextInferenceCall { _, _, _ ->
                textCalls.incrementAndGet()
                flowOf(ForegroundStreamEvent.Terminal(parseFailure()))
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
    fun `submitTyped threads looked-up history only to call-2`() = runTest(dispatcher) {
        val history = listOf(HistoryChunk(patternId = null, text = "earlier note"))
        val lookup = RecordingHistoryLookup(history)
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            save = save,
            textInference = ForegroundTextInferenceCall { t, _, h ->
                assertEquals(history, h)
                flowOf(ForegroundStreamEvent.Terminal(successResult(t, "and then what")))
            },
            lookupHistory = lookup,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.submitTyped("just got off the call again")
        advanceUntilIdle()

        assertEquals("just got off the call again", lookup.lastQuery)
        assertTrue(save.lastHistory.isEmpty())
        assertEquals(1, save.invocations.get())
    }

    @Test
    fun `setModelReadiness flips chrome across phases without losing other slots`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Loading)
        vm.setModelReadiness(ModelReadiness.Ready)
        assertEquals(ModelReadiness.Ready, vm.state.value.modelReadiness)
    }

    @Test
    fun `setModelReadiness updates Recording and Submitting phases`() = runTest(dispatcher) {
        val recordingVoice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val recordingVm = newViewModel(voice = recordingVoice, initialReadiness = ModelReadiness.Ready)
        recordingVm.startRecording()
        recordingVm.setModelReadiness(ModelReadiness.Paused)
        assertEquals(ModelReadiness.Paused, recordingVm.state.value.modelReadiness)
        recordingVm.discard()
        advanceUntilIdle()

        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val release = CompletableDeferred<Unit>()
        val submittingVm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                flow {
                    release.await()
                    emit(ForegroundStreamEvent.Transcription("x"))
                }
            },
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        submittingVm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        assertTrue(submittingVm.state.value is CaptureUiState.Submitting)
        submittingVm.setModelReadiness(ModelReadiness.Downloading(25))
        assertEquals(ModelReadiness.Downloading(25), submittingVm.state.value.modelReadiness)
        release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `setPersona is reflected across phases`() {
        val vm = newViewModel(persona = Persona.WITNESS, initialReadiness = ModelReadiness.Ready)
        vm.setPersona(Persona.EDITOR)
        assertEquals(Persona.EDITOR, vm.state.value.persona)
    }

    // 30s cap audio cue (pre-warn at 28s, single fire)

    @Test
    fun `limit warning cue fires once when recorded audio crosses 27s (pos)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val cue = CountingLimitWarningCue()
        val advancing = AdvancingClock()
        val vm = newViewModel(
            voice = voice,
            initialReadiness = ModelReadiness.Ready,
            clockOverride = advancing,
            limitWarningCue = cue,
        )
        voice.queueLevels(0.1f, 0.2f, 0.3f, 0.4f)

        vm.startRecording()
        // First level anchors elapsed at 0; 26.999s of audio is still under the 27s line.
        advancing.offsetMs = 5_000L
        voice.emitNextLevel()
        assertEquals("anchor sample is t=0, no cue", 0, cue.fireCount.get())

        advancing.offsetMs = 31_999L
        voice.emitNextLevel()
        assertEquals("26.999s of audio — still under the line", 0, cue.fireCount.get())

        advancing.offsetMs = 32_001L
        voice.emitNextLevel()
        assertEquals("crossing 27s of recorded audio fires the cue", 1, cue.fireCount.get())

        advancing.offsetMs = 34_500L
        voice.emitNextLevel()
        assertEquals("subsequent level updates past the threshold do not re-fire", 1, cue.fireCount.get())
    }

    @Test
    fun `limit warning cue does not fire when recording stops before the 27s line (neg)`() = runTest(dispatcher) {
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

    @Suppress("LongParameterList")
    private fun newViewModel(
        persona: Persona = Persona.WITNESS,
        voice: VoiceCapture = VoiceCapture { _, _ -> null },
        inference: ForegroundInferenceCall = ForegroundInferenceCall { _, _ ->
            error("inference call not expected in this test")
        },
        save: SaveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> 1L },
        textInference: ForegroundTextInferenceCall = ForegroundTextInferenceCall { _, _, _ ->
            error("text inference call not expected in this test")
        },
        lookupHistory: HistoryRetrieval = HistoryRetrieval { emptyList() },
        attachFollowUp: AttachFollowUp = AttachFollowUp { _, _ -> },
        initialReadiness: ModelReadiness = ModelReadiness.Loading,
        clockOverride: Clock = clock,
        limitWarningCue: LimitWarningCue = LimitWarningCue {},
    ): CaptureViewModel = CaptureViewModel(
        initialPersona = persona,
        recordVoice = voice,
        foregroundInference = inference,
        saveAndExtract = save,
        foregroundTextInference = textInference,
        retrieveHistory = lookupHistory,
        attachFollowUp = attachFollowUp,
        clock = clockOverride,
        zoneId = ZoneOffset.UTC,
        initialReadiness = initialReadiness,
        limitWarningCue = limitWarningCue,
    )

    @Suppress("LongParameterList")
    private fun voiceVm(
        transcription: String,
        followUp: String,
        voice: VoiceCapture,
        save: SaveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> 1L },
        attachFollowUp: AttachFollowUp = AttachFollowUp { _, _ -> },
    ): CaptureViewModel = newViewModel(
        voice = voice,
        inference = ForegroundInferenceCall { _, _ -> flowOf(ForegroundStreamEvent.Transcription(transcription)) },
        textInference = ForegroundTextInferenceCall { t, _, _ ->
            flowOf(ForegroundStreamEvent.Terminal(successResult(t, followUp)))
        },
        save = save,
        attachFollowUp = attachFollowUp,
        initialReadiness = ModelReadiness.Ready,
    )

    private fun successResult(transcription: String, followUp: String): ForegroundResult.Success =
        ForegroundResult.Success(
            persona = Persona.WITNESS,
            rawResponse = "",
            elapsedMs = 0L,
            completedAt = clock.instant(),
            transcription = transcription,
            followUp = followUp,
        )

    private fun parseFailure(recoveredTranscription: String? = null): ForegroundResult.ParseFailure =
        ForegroundResult.ParseFailure(
            persona = Persona.WITNESS,
            rawResponse = "",
            elapsedMs = 0,
            completedAt = clock.instant(),
            reason = ForegroundResult.ParseReason.EMPTY_RESPONSE,
            recoveredTranscription = recoveredTranscription,
        )
}

private class RecordingHistoryLookup(private val result: List<HistoryChunk>) : HistoryRetrieval {
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

private class RecordingSaveAndExtract(private val entryId: Long = 1L) : SaveAndExtract {
    val invocations: AtomicInteger = AtomicInteger(0)
    var lastDurationMs: Long = -1L
    var lastText: String? = null
    var lastHistory: List<HistoryChunk> = emptyList()
    var lastFollowUpText: String? = null
    override suspend fun invoke(
        text: String,
        capturedAt: java.time.ZonedDateTime,
        persona: Persona,
        durationMs: Long,
        followUpText: String?,
        retrievedHistory: List<HistoryChunk>,
    ): Long {
        invocations.incrementAndGet()
        lastDurationMs = durationMs
        lastText = text
        lastHistory = retrievedHistory
        lastFollowUpText = followUpText
        return entryId
    }
}

private class RecordingAttachFollowUp : AttachFollowUp {
    var lastEntryId: Long? = null
    var lastFollowUp: String? = null
    override suspend fun invoke(entryId: Long, followUpText: String) {
        lastEntryId = entryId
        lastFollowUp = followUpText
    }
}

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
