package dev.anchildress1.vestige.ui.capture

import app.cash.turbine.test
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
        val inference = FakeForegroundInference(
            ForegroundResult.Success(
                persona = Persona.WITNESS,
                rawResponse = "<x/>",
                elapsedMs = 1_200,
                completedAt = clock.instant(),
                transcription = "they asked again",
                followUp = "what did they actually want",
            ),
        )
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            voice = voice,
            inference = inference,
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )

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
        val inference = FakeForegroundInference(
            ForegroundResult.Success(
                persona = Persona.WITNESS,
                rawResponse = "",
                elapsedMs = 100,
                completedAt = clock.instant(),
                transcription = "x",
                followUp = "y",
            ),
        )
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
    fun `discard cancels mid-flight recording and returns to Idle (pos)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val vm = newViewModel(
            voice = voice,
            inference = FakeForegroundInference(
                ForegroundResult.Success(
                    persona = Persona.WITNESS,
                    rawResponse = "",
                    elapsedMs = 100,
                    completedAt = clock.instant(),
                    transcription = "",
                    followUp = "",
                ),
            ),
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
    fun `discard from Idle is a no-op (neg — only available during RECORDING)`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = null)
        val vm = newViewModel(
            voice = voice,
            inference = FakeForegroundInference(
                ForegroundResult.Success(
                    persona = Persona.WITNESS,
                    rawResponse = "",
                    elapsedMs = 0,
                    completedAt = clock.instant(),
                    transcription = "",
                    followUp = "",
                ),
            ),
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
        pending.complete(
            ForegroundResult.Success(
                persona = Persona.WITNESS,
                rawResponse = "",
                elapsedMs = 0,
                completedAt = clock.instant(),
                transcription = "",
                followUp = "",
            ),
        )
        advanceUntilIdle()
    }

    @Test
    fun `acknowledgeReview retains lastReview on Idle`() = runTest(dispatcher) {
        val voice = FakeVoiceCapture(result = AudioChunk(FloatArray(16), 16_000, isFinal = true))
        val inference = FakeForegroundInference(
            ForegroundResult.Success(
                persona = Persona.WITNESS,
                rawResponse = "",
                elapsedMs = 100,
                completedAt = clock.instant(),
                transcription = "hello",
                followUp = "what next",
            ),
        )
        val vm = newViewModel(
            voice = voice,
            inference = inference,
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
        )
        vm.startRecording()
        voice.completeWithResult()
        advanceUntilIdle()
        assertTrue(vm.state.value is CaptureUiState.Reviewing)
        vm.acknowledgeReview()
        val idle = vm.state.value as CaptureUiState.Idle
        assertEquals("hello", idle.lastReview?.transcription)
    }

    @Test
    fun `submitTyped below minimum length is ignored`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Ready)
        vm.submitTyped("hi")
        assertTrue(vm.state.value is CaptureUiState.Idle)
    }

    @Test
    fun `submitTyped routes through saveAndExtract and lands in Reviewing`() = runTest(dispatcher) {
        val save = RecordingSaveAndExtract()
        val vm = newViewModel(
            save = save,
            initialReadiness = ModelReadiness.Ready,
        )
        vm.submitTyped("just got off the call again")
        advanceUntilIdle()
        assertEquals(1, save.invocations.get())
        val reviewing = vm.state.value as CaptureUiState.Reviewing
        assertEquals("just got off the call again", reviewing.review.transcription)
    }

    @Test
    fun `setModelReadiness flips chrome across phases without losing other slots`() {
        val vm = newViewModel(initialReadiness = ModelReadiness.Loading)
        vm.setModelReadiness(ModelReadiness.Ready)
        assertEquals(ModelReadiness.Ready, vm.state.value.modelReadiness)
    }

    @Test
    fun `setPersona is reflected across phases`() {
        val vm = newViewModel(persona = Persona.WITNESS, initialReadiness = ModelReadiness.Ready)
        vm.setPersona(Persona.EDITOR)
        assertEquals(Persona.EDITOR, vm.state.value.persona)
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
        val vm = newViewModel(
            voice = routing,
            inference = FakeForegroundInference(
                ForegroundResult.Success(
                    persona = Persona.WITNESS,
                    rawResponse = "",
                    elapsedMs = 0,
                    completedAt = clock.instant(),
                    transcription = "",
                    followUp = "",
                ),
            ),
            save = RecordingSaveAndExtract(),
            initialReadiness = ModelReadiness.Ready,
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
        save: SaveAndExtract = SaveAndExtract { _, _, _ -> },
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

    private class FakeForegroundInference(private val result: ForegroundResult) : ForegroundInferenceCall {
        override suspend fun invoke(audio: AudioChunk, persona: Persona): ForegroundResult = result
    }

    private class SuspendingForegroundInference(private val pending: CompletableDeferred<ForegroundResult>) :
        ForegroundInferenceCall {
        override suspend fun invoke(audio: AudioChunk, persona: Persona): ForegroundResult = pending.await()
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
        override suspend fun invoke(text: String, capturedAt: java.time.ZonedDateTime, persona: Persona) {
            invocations.incrementAndGet()
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
}
