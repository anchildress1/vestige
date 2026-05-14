package dev.anchildress1.vestige.ui.capture

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CancellationException
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
    private val saveTypedEntry: SaveTypedEntry = SaveTypedEntry { text, capturedAt, persona ->
        saveAndExtract(text, capturedAt, persona)
    },
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

    private val stopSignal: MutableSharedFlow<Unit> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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

    fun onMicDenied() {
        _state.update { current ->
            (current as? CaptureUiState.Idle)?.copy(error = CaptureError.MicDenied)
                ?: CaptureUiState.Idle(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                    error = CaptureError.MicDenied,
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
        if (recordingJob?.isActive == true) return
        val current = _state.value
        if (current !is CaptureUiState.Idle || current.modelReadiness !is ModelReadiness.Ready) return

        val persona = current.persona
        val readiness = current.modelReadiness
        val meter = AudioLevelMeter(windowSize = levelWindowSize)
        _state.value = CaptureUiState.Recording(
            persona = persona,
            modelReadiness = readiness,
            elapsedMs = 0L,
            recentLevels = meter.levels,
        )
        limitWarningFired = false
        val startedAtMs = clock.millis()
        recordingJob = viewModelScope.launch {
            try {
                val audio = recordVoice(
                    onLevel = { level -> onRecordingLevel(meter, level, startedAtMs) },
                    stopFlow = stopSignal,
                )
                if (audio == null) {
                    _state.value = CaptureUiState.Idle(persona = persona, modelReadiness = readiness)
                    return@launch
                }
                _state.value = CaptureUiState.Inferring(
                    persona = persona,
                    modelReadiness = readiness,
                    startedAtEpochMs = clock.millis(),
                )
                runInference(audio, persona, readiness)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "Recording job failed", error)
                emitInferenceError(persona, readiness, CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
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
                _state.value = CaptureUiState.Idle(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                )
            }
        }
    }

    /** Type-fallback path: save the typed entry text and route through the background extraction pipeline. */
    fun submitTyped(text: String) {
        val trimmed = text.trim()
        if (trimmed.length < MIN_TYPED_LENGTH) return
        val current = _state.value
        if (current !is CaptureUiState.Idle) return
        viewModelScope.launch {
            _state.value = CaptureUiState.Inferring(
                persona = current.persona,
                modelReadiness = current.modelReadiness,
                startedAtEpochMs = clock.millis(),
            )
            try {
                saveTypedEntry(trimmed, ZonedDateTime.now(clock.withZone(zoneId)), current.persona)
                _state.value = CaptureUiState.Reviewing(
                    persona = current.persona,
                    modelReadiness = current.modelReadiness,
                    review = ReviewState(
                        transcription = trimmed,
                        followUp = "",
                        persona = current.persona,
                        elapsedMs = 0L,
                    ),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "submitTyped save failed", error)
                emitInferenceError(
                    current.persona,
                    current.modelReadiness,
                    CaptureError.InferenceFailed.Reason.ENGINE_FAILED,
                )
            }
        }
    }

    private fun onRecordingLevel(meter: AudioLevelMeter, level: Float, startedAtMs: Long) {
        // VoiceCapture surfaces 0..1 RMS values directly — meter ring-buffer turns them into a
        // chronological window for the live bar strip. Clamp defensively in case a fake driver
        // overshoots in tests.
        val clamped = level.coerceIn(0f, 1f)
        meter.push(samples = floatArrayOf(clamped), count = 1)
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

    private suspend fun runInference(audio: AudioChunk, persona: Persona, readiness: ModelReadiness) {
        try {
            val result = foregroundInference(audio, persona)
            when (result) {
                is ForegroundResult.Success -> {
                    saveAndExtract(result.transcription, ZonedDateTime.now(clock.withZone(zoneId)), persona)
                    _state.value = CaptureUiState.Reviewing(
                        persona = persona,
                        modelReadiness = readiness,
                        review = ReviewState(
                            transcription = result.transcription,
                            followUp = result.followUp,
                            persona = persona,
                            elapsedMs = result.elapsedMs,
                        ),
                    )
                }

                is ForegroundResult.ParseFailure -> emitInferenceError(
                    persona,
                    readiness,
                    CaptureError.InferenceFailed.Reason.PARSE_FAILED,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "Foreground inference timed out", timeout)
            emitInferenceError(persona, readiness, CaptureError.InferenceFailed.Reason.TIMED_OUT)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.e(TAG, "Foreground inference failed", error)
            emitInferenceError(persona, readiness, CaptureError.InferenceFailed.Reason.ENGINE_FAILED)
        }
    }

    private fun emitInferenceError(
        persona: Persona,
        readiness: ModelReadiness,
        reason: CaptureError.InferenceFailed.Reason,
    ) {
        _state.value = CaptureUiState.Idle(
            persona = persona,
            modelReadiness = readiness,
            error = CaptureError.InferenceFailed(reason),
        )
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

/** Runs one foreground (single-turn) call against the local model. */
fun interface ForegroundInferenceCall {
    suspend operator fun invoke(audio: AudioChunk, persona: Persona): ForegroundResult
}

/** Routes a transcription (voice or typed) into the two-tier save + background extraction pipeline. */
fun interface SaveAndExtract {
    suspend operator fun invoke(text: String, capturedAt: ZonedDateTime, persona: Persona)
}

/** Persists typed fallback entries without requiring the local model to be ready first. */
fun interface SaveTypedEntry {
    suspend operator fun invoke(text: String, capturedAt: ZonedDateTime, persona: Persona)
}
