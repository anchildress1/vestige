package dev.anchildress1.vestige.ui.capture

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Audio bytes never enter this VM — only RMS levels (0..1) and the final `AudioChunk` that the
 * foreground call consumes once.
 */
@Suppress(
    "LongParameterList", // Constructor seams: 3 collaborators + clock + zone + readiness + windows.
    "TooManyFunctions", // Host events split across mic / model / persona / record / type lifecycles.
)
class CaptureViewModel(
    initialPersona: Persona,
    private val recordVoice: VoiceCapture,
    private val foregroundInference: ForegroundInferenceCall,
    private val saveAndExtract: SaveAndExtract,
    private val foregroundTextInference: ForegroundTextInferenceCall,
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

    fun setModelReadiness(readiness: ModelReadiness) {
        _state.update { current ->
            when (current) {
                is CaptureUiState.Idle -> current.copy(modelReadiness = readiness)
                is CaptureUiState.Recording -> current.copy(modelReadiness = readiness)
                is CaptureUiState.Inferring -> current.copy(modelReadiness = readiness)
                is CaptureUiState.Reviewing -> current.copy(modelReadiness = readiness)
            }
        }
    }

    fun setPersona(persona: Persona) {
        _state.update { current ->
            when (current) {
                is CaptureUiState.Idle -> current.copy(persona = persona)
                is CaptureUiState.Recording -> current.copy(persona = persona)
                is CaptureUiState.Inferring -> current.copy(persona = persona)
                is CaptureUiState.Reviewing -> current.copy(persona = persona)
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

    fun acknowledgeReview() {
        _state.update { current ->
            when (current) {
                is CaptureUiState.Reviewing -> CaptureUiState.Idle(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                    lastReview = current.review,
                )

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
        val startedAtMs = clock.millis()
        beginRecording(meter)
        recordingJob = launchRecordingJob(
            meter = meter,
            startedAtMs = startedAtMs,
            inferencePersona = current.persona,
        )
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
    }

    private fun launchRecordingJob(meter: AudioLevelMeter, startedAtMs: Long, inferencePersona: Persona): Job =
        viewModelScope.launch {
            try {
                val audio = captureAudio(meter, startedAtMs)
                if (audio == null) {
                    returnToIdleFromRecording()
                    return@launch
                }
                transitionToInferring()
                runForeground(inferencePersona, audio.durationMs) {
                    foregroundInference(audio, inferencePersona)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "Recording job failed", error)
                emitInferenceError(CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
            }
        }

    private suspend fun captureAudio(meter: AudioLevelMeter, startedAtMs: Long): AudioChunk? = recordVoice(
        onLevel = { level -> onRecordingLevel(meter, level, startedAtMs) },
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

    private fun transitionToInferring() {
        _state.update { current ->
            if (current is CaptureUiState.Recording) {
                CaptureUiState.Inferring(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                    startedAtEpochMs = clock.millis(),
                )
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
     * Typed-entry path: runs the same foreground model call voice does, so a typed entry produces
     * the identical Reviewing surface (persona follow-up included). The model is required — when it
     * isn't Ready this is a silent no-op, exactly like a disabled REC button.
     */
    fun submitTyped(text: String) {
        val trimmed = text.trim()
        val current = _state.value as? CaptureUiState.Idle ?: return
        if (trimmed.length < MIN_TYPED_LENGTH || current.modelReadiness !is ModelReadiness.Ready) return
        val inferencePersona = current.persona
        viewModelScope.launch {
            _state.update { c ->
                if (c is CaptureUiState.Idle) {
                    CaptureUiState.Inferring(
                        persona = c.persona,
                        modelReadiness = c.modelReadiness,
                        startedAtEpochMs = clock.millis(),
                    )
                } else {
                    c
                }
            }
            runForeground(inferencePersona, durationMs = 0L) {
                foregroundTextInference(trimmed, inferencePersona)
            }
        }
    }

    private fun onRecordingLevel(meter: AudioLevelMeter, level: Float, startedAtMs: Long) {
        // VoiceCapture surfaces 0..1 RMS values directly — meter ring-buffer turns them into a
        // chronological window for the live bar strip. Clamp defensively in case a fake driver
        // overshoots in tests.
        meter.pushLevel(level)
        val elapsed = clock.millis() - startedAtMs
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

    // Shared foreground result handler for both the voice path (audio call) and the typed path
    // (text call). `durationMs` is the audio length for voice, 0 for typed.
    private suspend fun runForeground(persona: Persona, durationMs: Long, call: suspend () -> ForegroundResult) {
        try {
            when (val result = call()) {
                is ForegroundResult.Success -> {
                    saveAndExtract(
                        result.transcription,
                        ZonedDateTime.now(clock.withZone(zoneId)),
                        persona,
                        durationMs,
                        result.followUp,
                    )
                    _state.update { c ->
                        CaptureUiState.Reviewing(
                            persona = c.persona,
                            modelReadiness = c.modelReadiness,
                            review = ReviewState(
                                transcription = result.transcription,
                                followUp = result.followUp,
                                persona = c.persona,
                                elapsedMs = result.elapsedMs,
                            ),
                        )
                    }
                }

                is ForegroundResult.ParseFailure -> emitInferenceError(
                    CaptureError.InferenceFailed.Reason.PARSE_FAILED,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "Foreground inference timed out", timeout)
            emitInferenceError(CaptureError.InferenceFailed.Reason.TIMED_OUT)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.e(TAG, "Foreground inference failed", error)
            emitInferenceError(CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
        }
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
        const val LIMIT_WARNING_THRESHOLD_MS: Long = 28_000L
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

/** Runs one foreground (single-turn) call against the local model for a voice entry. */
fun interface ForegroundInferenceCall {
    suspend operator fun invoke(audio: AudioChunk, persona: Persona): ForegroundResult
}

/** Runs one foreground (single-turn) call for a typed entry — text in, `{transcription, follow_up}` out. */
fun interface ForegroundTextInferenceCall {
    suspend operator fun invoke(text: String, persona: Persona): ForegroundResult
}

/** Routes a transcription (voice or typed) into the two-tier save + background extraction pipeline. */
fun interface SaveAndExtract {
    suspend operator fun invoke(
        text: String,
        capturedAt: ZonedDateTime,
        persona: Persona,
        durationMs: Long,
        followUpText: String?,
    )
}
