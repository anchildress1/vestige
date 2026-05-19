package dev.anchildress1.vestige.ui.capture

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Single-screen state owner for `CaptureScreen`. Owns the recording job, the live level meter,
 * and the foreground-call lifecycle for one voice or typed entry. Collaborators are injected as
 * fun-interfaces so the JVM unit suite drives the full state machine without Android dependencies.
 *
 * Post-submit there is no in-Capture review surface: the entry persists on the call-1
 * transcription, the host is told to open it in History detail via [openEntryEvents], and
 * Capture resets to [CaptureUiState.Idle] only after the UI confirms it consumed that open
 * request. Call-2's persona follow-up is generated in the background and patched onto the entry
 * — the VM is Activity-scoped so that work survives the navigation away from Capture.
 *
 * Audio bytes never enter this VM — only RMS levels (0..1) and the final `AudioChunk` that the
 * foreground call consumes once.
 */
@Suppress(
    "LongParameterList", // Constructor seams: collaborators + clock + zone + readiness + windows.
    "TooManyFunctions", // Host events split across mic / model / persona / record / type lifecycles.
)
class CaptureViewModel(
    initialPersona: Persona,
    private val recordVoice: VoiceCapture,
    private val foregroundInference: ForegroundInferenceCall,
    private val saveAndExtract: SaveAndExtract,
    private val foregroundTextInference: ForegroundTextInferenceCall,
    private val retrieveHistory: HistoryRetrieval = HistoryRetrieval { emptyList() },
    private val attachFollowUp: AttachFollowUp = AttachFollowUp { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val initialReadiness: ModelReadiness = ModelReadiness.Loading,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val levelWindowSize: Int = LEVEL_WINDOW_SIZE,
    private val limitWarningCue: LimitWarningCue = LimitWarningCue {},
    private val limitWarningThresholdMs: Long = LIMIT_WARNING_THRESHOLD_MS,
) : ViewModel() {

    private val _state: MutableStateFlow<CaptureUiState> = MutableStateFlow(
        CaptureUiState.Idle(persona = initialPersona, modelReadiness = initialReadiness),
    )
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    // One-shot: "open this entry's detail in History". Buffered so a fast emit isn't lost if the
    // collector is mid-recomposition; consumed once by CaptureScreen.
    private val _openEntryEvents = Channel<Long>(Channel.BUFFERED)
    val openEntryEvents: Flow<Long> = _openEntryEvents.receiveAsFlow()

    // replay=1 + DROP_OLDEST: a STOP/DISCARD tap fired before `RealVoiceCapture` subscribes to
    // `stopFlow.first()` is buffered and delivered on subscribe. Without the replay slot, fast
    // taps race the subscriber and silently fall on the floor, leaving the recording to run to
    // the 30s cap.
    private val stopSignal: MutableSharedFlow<Unit> = MutableSharedFlow(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun primeStopSignal() {
        stopSignal.resetReplayCache()
    }

    private var recordingJob: Job? = null
    private var limitWarningFired: Boolean = false

    // Elapsed is anchored to the FIRST captured sample, not the REC tap. AudioRecord cold-start
    // (mic HAL warmup) elapses with zero onLevel callbacks; anchoring to the tap left the meter
    // and timer frozen during warmup, then jumping forward to "catch up" the instant the first
    // sample landed. Null until the first level of the session arrives.
    private var captureAnchorMs: Long? = null

    fun setModelReadiness(readiness: ModelReadiness) {
        _state.update { current ->
            when (current) {
                is CaptureUiState.Idle -> current.copy(modelReadiness = readiness)
                is CaptureUiState.Recording -> current.copy(modelReadiness = readiness)
                is CaptureUiState.Submitting -> current.copy(modelReadiness = readiness)
            }
        }
    }

    fun setPersona(persona: Persona) {
        _state.update { current ->
            when (current) {
                is CaptureUiState.Idle -> current.copy(persona = persona)
                is CaptureUiState.Recording -> current.copy(persona = persona)
                is CaptureUiState.Submitting -> current.copy(persona = persona)
            }
        }
    }

    /** [permanentlyBlocked] true ⇒ system-level "don't ask again"; surface the Settings path. */
    fun onMicDenied(permanentlyBlocked: Boolean = false) {
        val micError = if (permanentlyBlocked) CaptureError.MicBlocked else CaptureError.MicDenied
        _state.update { current ->
            (current as? CaptureUiState.Idle)?.copy(error = micError)
                ?: CaptureUiState.Idle(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                    error = micError,
                )
        }
    }

    fun dismissError() {
        _state.update { current ->
            when (current) {
                is CaptureUiState.Idle -> current.copy(error = null)
                else -> current
            }
        }
    }

    /**
     * Called by the host after the mic permission resolves to granted. No-op if a recording is
     * already running, the model is not ready, or the screen is already past idle.
     */
    fun startRecording() {
        val current = readyIdleState() ?: return
        val meter = AudioLevelMeter(windowSize = levelWindowSize)
        beginRecording(meter)
        recordingJob = launchRecordingJob(meter = meter, inferencePersona = current.persona)
    }

    private fun readyIdleState(): CaptureUiState.Idle? {
        val current = _state.value as? CaptureUiState.Idle
        return if (recordingJob?.isActive == true || current?.modelReadiness !is ModelReadiness.Ready) null else current
    }

    private fun beginRecording(meter: AudioLevelMeter) {
        primeStopSignal()
        _state.update { current ->
            if (current is CaptureUiState.Idle) {
                CaptureUiState.Recording(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                    elapsedMs = 0L,
                    recentLevels = meter.levels,
                )
            } else {
                current
            }
        }
        limitWarningFired = false
        captureAnchorMs = null
    }

    private fun launchRecordingJob(meter: AudioLevelMeter, inferencePersona: Persona): Job = viewModelScope.launch {
        try {
            val audio = captureAudio(meter)
            if (audio == null) {
                returnToIdleFromRecording()
                return@launch
            }
            transitionToSubmitting()
            runVoiceForeground(inferencePersona, audio)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.e(TAG, "Recording job failed", error)
            emitInferenceError(CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
        }
    }

    private suspend fun captureAudio(meter: AudioLevelMeter): AudioChunk? = recordVoice(
        onLevel = { level -> onRecordingLevel(meter, level) },
        stopFlow = stopSignal,
    )

    private fun returnToIdleFromRecording() {
        _state.update { current ->
            if (current is CaptureUiState.Recording) {
                CaptureUiState.Idle(persona = current.persona, modelReadiness = current.modelReadiness)
            } else {
                current
            }
        }
    }

    private fun transitionToSubmitting() {
        _state.update { current ->
            if (current is CaptureUiState.Recording) {
                CaptureUiState.Submitting(persona = current.persona, modelReadiness = current.modelReadiness)
            } else {
                current
            }
        }
    }

    /** Called by host on STOP tap. Idempotent. */
    fun stopRecording() {
        if (_state.value !is CaptureUiState.Recording) return
        stopSignal.tryEmit(Unit)
    }

    /**
     * Called by host on DISCARD tap. Only valid during RECORDING — after STOP the foreground
     * call is in flight and not cancellable. Awaits the recording job's cancellation so
     * `AudioCapture` releases the JNI handle and zeroes buffers before the UI surfaces Idle.
     */
    fun discard() {
        val current = _state.value
        if (current !is CaptureUiState.Recording) return
        val job = recordingJob ?: return
        recordingJob = null
        stopSignal.tryEmit(Unit)
        viewModelScope.launch {
            try {
                job.cancelAndJoin()
            } finally {
                _state.update { c ->
                    if (c is CaptureUiState.Recording) {
                        CaptureUiState.Idle(persona = c.persona, modelReadiness = c.modelReadiness)
                    } else {
                        c
                    }
                }
            }
        }
    }

    /**
     * Typed-entry path. The text is known immediately, so it persists straight away and the host
     * opens the entry; call-2 then generates the persona follow-up in the background. Silent
     * no-op when the model isn't Ready, exactly like a disabled REC button.
     */
    fun submitTyped(text: String) {
        val trimmed = text.trim()
        val current = _state.value as? CaptureUiState.Idle ?: return
        if (trimmed.length < MIN_TYPED_LENGTH || current.modelReadiness !is ModelReadiness.Ready) return
        val inferencePersona = current.persona
        viewModelScope.launch {
            _state.update { c ->
                if (c is CaptureUiState.Idle) {
                    CaptureUiState.Submitting(persona = c.persona, modelReadiness = c.modelReadiness)
                } else {
                    c
                }
            }
            try {
                val entryId = persistPending(trimmed, inferencePersona, durationMs = 0L)
                openEntry(entryId)
                launchFollowUp(entryId, trimmed, inferencePersona)
            } catch (timeout: TimeoutCancellationException) {
                Log.w(TAG, "Typed submit timed out", timeout)
                emitInferenceError(CaptureError.InferenceFailed.Reason.TIMED_OUT)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "Typed submit failed", error)
                emitInferenceError(CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
            }
        }
    }

    private fun onRecordingLevel(meter: AudioLevelMeter, level: Float) {
        // VoiceCapture surfaces 0..1 RMS values directly — meter ring-buffer turns them into a
        // chronological window for the live bar strip. Clamp defensively in case a fake driver
        // overshoots in tests.
        val now = clock.millis()
        val anchor = captureAnchorMs ?: now.also { captureAnchorMs = it }
        meter.pushLevel(level)
        val elapsed = now - anchor
        if (!limitWarningFired && elapsed >= limitWarningThresholdMs) {
            limitWarningFired = true
            limitWarningCue.fire()
        }
        _state.update { current ->
            if (current is CaptureUiState.Recording) {
                current.copy(elapsedMs = elapsed.coerceAtMost(maxDurationMs), recentLevels = meter.levels)
            } else {
                current
            }
        }
    }

    /**
     * Voice path: call 1 streams the transcription from the audio; the moment it lands the entry
     * is persisted and the host opens it. Retrieval and follow-up generation run only after that
     * handoff, so the user does not wait on vector lookup before seeing the entry.
     */
    private suspend fun runVoiceForeground(persona: Persona, audio: AudioChunk) {
        // null ⇒ the call threw and the matching error was already surfaced; "" ⇒ the call
        // returned no transcription event (mapped to PARSE_FAILED here). Keeping both signals
        // in one nullable String holds runVoiceForeground to a single happy path.
        val transcription = call1Transcription(audio, persona) ?: return
        if (transcription.isBlank()) {
            emitInferenceError(CaptureError.InferenceFailed.Reason.PARSE_FAILED)
            return
        }
        val entryId = persistPending(transcription, persona, audio.durationMs)
        openEntry(entryId)
        launchFollowUp(entryId, transcription, persona)
    }

    private suspend fun call1Transcription(audio: AudioChunk, persona: Persona): String? = try {
        foregroundInference(audio, persona)
            .mapNotNull { event ->
                when (event) {
                    is ForegroundStreamEvent.Transcription -> event.text
                    is ForegroundStreamEvent.Terminal -> (event.result as? ForegroundResult.Success)?.transcription
                    is ForegroundStreamEvent.FollowUpDelta -> null
                }
            }
            .firstOrNull()
            .orEmpty()
    } catch (timeout: TimeoutCancellationException) {
        Log.w(TAG, "Voice transcription timed out", timeout)
        emitInferenceError(CaptureError.InferenceFailed.Reason.TIMED_OUT)
        null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.e(TAG, "Voice transcription failed", error)
        emitInferenceError(CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
        null
    }

    private suspend fun persistPending(text: String, persona: Persona, durationMs: Long): Long = saveAndExtract(
        text = text,
        capturedAt = ZonedDateTime.now(clock.withZone(zoneId)),
        persona = persona,
        durationMs = durationMs,
        followUpText = null,
        retrievedHistory = emptyList(),
    )

    // Entry is persisted + background extraction kicked. Tell the host to open it in History
    // detail and keep Submitting alive until the route collector confirms it consumed the event.
    // Resetting here created a dead frame where Capture flashed blank before History painted.
    private fun openEntry(entryId: Long) {
        _openEntryEvents.trySend(entryId)
    }

    fun onOpenEntryHandled() {
        _state.update { c -> CaptureUiState.Idle(persona = c.persona, modelReadiness = c.modelReadiness) }
    }

    // Call 2 runs after the user has navigated to the entry — kept on viewModelScope (the VM is
    // Activity-scoped, so it survives leaving Capture). The persona follow-up is patched onto the
    // already-persisted entry; any failure just leaves the entry follow-up-less (the worse
    // outcome — losing the entry — can't happen, it's already saved).
    private fun launchFollowUp(entryId: Long, text: String, persona: Persona) {
        viewModelScope.launch {
            try {
                val history = retrieveHistorySafely(text)
                foregroundTextInference(text, persona, history)
                    .filterIsInstance<ForegroundStreamEvent.Terminal>()
                    .firstOrNull()
                    ?.let { terminal ->
                        val result = terminal.result
                        if (result is ForegroundResult.Success && result.followUp.isNotBlank()) {
                            attachFollowUp(entryId, result.followUp)
                        }
                    }
            } catch (timeout: TimeoutCancellationException) {
                Log.w(TAG, "Follow-up generation timed out for entry $entryId", timeout)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (persist: EntryPersistenceException) {
                // Persistence failure (disk full, markdown dir gone) is a different class than a
                // transient inference miss — error-tier so the lost-follow-up disk case is
                // greppable instead of hiding under the warn-tier generic handler.
                Log.e(TAG, "Follow-up persist failed for entry $entryId", persist)
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(TAG, "Follow-up generation failed for entry $entryId (${error.javaClass.simpleName})")
            }
        }
    }

    // Retrieval must never block the capture: a degraded query (no embeddings yet, store error)
    // yields an empty history and the follow-up proceeds context-free. Logs the exception class
    // only — never the query text (AGENTS.md: no raw user content in any sink).
    private suspend fun retrieveHistorySafely(query: String): List<HistoryChunk> = try {
        retrieveHistory(query)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.w(TAG, "Retrieval degraded, proceeding context-free (${error.javaClass.simpleName})")
        emptyList()
    }

    private fun emitInferenceError(reason: CaptureError.InferenceFailed.Reason) {
        _state.update { c ->
            CaptureUiState.Idle(
                persona = c.persona,
                modelReadiness = c.modelReadiness,
                error = CaptureError.InferenceFailed(reason),
            )
        }
    }

    override fun onCleared() {
        recordingJob?.cancel()
        viewModelScope.cancel()
        super.onCleared()
    }

    companion object {
        const val MAX_DURATION_MS: Long = 30_000L
        const val LEVEL_WINDOW_SIZE: Int = 42

        // Elapsed is real recorded-audio time (anchored to the first sample). The 30s hard cap
        // is also audio-time, so warn at 27s for a ~3s lead — 28s left only ~2s before the cap.
        const val LIMIT_WARNING_THRESHOLD_MS: Long = 27_000L
        private const val MIN_TYPED_LENGTH: Int = 3
        private const val TAG = "CaptureVM"
    }
}

/**
 * VoiceCapture contract — produces one `AudioChunk` per recording. [onLevel] receives RMS
 * samples in `[0, 1]` as the recording progresses. [stopFlow] is the early-stop signal the VM
 * raises on tap-stop. Returns `null` if the recording yielded no audio (cancelled before any
 * samples landed).
 */
fun interface VoiceCapture {
    suspend operator fun invoke(onLevel: (Float) -> Unit, stopFlow: Flow<Unit>): AudioChunk?
}

/** Streams one foreground (single-turn) call against the local model for a voice entry. */
fun interface ForegroundInferenceCall {
    operator fun invoke(audio: AudioChunk, persona: Persona): Flow<ForegroundStreamEvent>
}

/**
 * Streams a follow-up call given known text + retrieved prior-entry context. Used for typed
 * entries (text = the user's typed words) and for the voice path's call 2 (text = call-1's
 * authoritative transcription). The history is rendered into the system prompt by
 * `ForegroundInference`.
 */
fun interface ForegroundTextInferenceCall {
    operator fun invoke(
        text: String,
        persona: Persona,
        retrievedHistory: List<HistoryChunk>,
    ): Flow<ForegroundStreamEvent>
}

/**
 * Looks up prior-entry context for a query string. Implementations run off the UI thread and
 * MUST degrade to an empty list rather than throw — a failed retrieval can never block a capture.
 */
fun interface HistoryRetrieval {
    suspend operator fun invoke(query: String): List<HistoryChunk>
}

/**
 * Persists the transcription as a pending entry + kicks the background 3-lens extraction.
 * Returns the new entry id so the host can open it immediately.
 */
fun interface SaveAndExtract {
    @Suppress("LongParameterList") // Save+extract orchestration contract; a wrapper DTO would add indirection only.
    suspend operator fun invoke(
        text: String,
        capturedAt: ZonedDateTime,
        persona: Persona,
        durationMs: Long,
        followUpText: String?,
        retrievedHistory: List<HistoryChunk>,
    ): Long
}

/** Lands call-2's persona follow-up onto an already-persisted in-flight entry. */
fun interface AttachFollowUp {
    suspend operator fun invoke(entryId: Long, followUpText: String)
}
