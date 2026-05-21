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
        val vm = voiceVm("they asked again", voice, save)

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
        assertEquals("entry persists the call-1 follow-up", "what keeps looping?", save.lastFollowUpText)
    }

    @Test
    fun `voice path persists call-1 transcription and follow-up from the same call`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract(entryId = 7L)
        val callCount = AtomicInteger(0)
        val vm = newViewModel(
            voice = voice,
            inference = ForegroundInferenceCall { _, _ ->
                callCount.incrementAndGet()
                flowOf(
                    ForegroundStreamEvent.Transcription("i kept reopening it"),
                    ForegroundStreamEvent.Terminal(successResult("i kept reopening it", "what did reopening buy you?")),
                )
            },
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertEquals("save persists call-1", "i kept reopening it", save.lastText)
        assertEquals("what did reopening buy you?", save.lastFollowUpText)
        assertEquals(1, save.invocations.get())
        assertEquals(1, callCount.get())
    }

    @Test
    fun `voice flow passes audio durationMs to saveAndExtract`() = runTest(dispatcher) {
        val audio = AudioChunk(FloatArray(16), sampleRateHz = 16_000, isFinal = true)
        val voice = FakeVoiceCapture(result = audio)
        val save = RecordingSaveAndExtract()
        val vm = voiceVm("they asked again", voice, save)

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
                save = save,
                initialReadiness = ModelReadiness.Ready,
            )

            vm.startRecording()
            voice.completeWithResult()
            advanceUntilIdle()

            assertEquals("terminal-only words", save.lastText)
            assertEquals("what got missed?", save.lastFollowUpText)
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
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )

        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()

        assertEquals("recovered words", save.lastText)
        assertNull(save.lastFollowUpText)
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
    fun `submitTyped below minimum length is ignored`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.submitTyped("hi")
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `submitTyped persists and opens the entry without a follow-up call`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract(entryId = 5L)
        val vm = newViewModel(
            save = save,
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
        assertNull(save.lastFollowUpText)
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `submitTyped is a silent no-op when the model is not Ready`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            save = save,
            initialReadiness = ModelReadiness.Loading,
        )

        vm.submitTyped("just typed it")
        advanceUntilIdle()

        assertTrue(vm.state.value is CaptureUiState.Idle)
        assertEquals(0, save.invocations.get())
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
        initialReadiness: ModelReadiness = ModelReadiness.Loading,
        clockOverride: Clock = clock,
        limitWarningCue: LimitWarningCue = LimitWarningCue {},
    ): CaptureViewModel = CaptureViewModel(
        initialPersona = persona,
        recordVoice = voice,
        foregroundInference = inference,
        saveAndExtract = save,
        clock = clockOverride,
        zoneId = ZoneOffset.UTC,
        initialReadiness = initialReadiness,
        limitWarningCue = limitWarningCue,
    )

    @Suppress("LongParameterList")
    private fun voiceVm(
        transcription: String,
        voice: VoiceCapture,
        save: SaveAndExtract = SaveAndExtract { _, _, _, _, _, _ -> 1L },
    ): CaptureViewModel = newViewModel(
        voice = voice,
        inference = ForegroundInferenceCall { _, _ ->
            flowOf(
                ForegroundStreamEvent.Transcription(transcription),
                ForegroundStreamEvent.Terminal(successResult(transcription, "what keeps looping?")),
            )
        },
        save = save,
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
