package dev.anchildress1.vestige.ui.capture

import dev.anchildress1.vestige.model.Persona

/**
 * Screen-level state for `CaptureScreen`. Three phases drive the layout swap: idle frame vs
 * live recording vs the brief submitting spinner. Post-submit the entry is persisted and the
 * host navigates to its detail in History — Capture has no review/inferring surface of its own.
 * Persona and model readiness are common slots so the chrome renders without phase reads.
 */
sealed interface CaptureUiState {
    val persona: Persona
    val modelReadiness: ModelReadiness

    /** Pre-recording resting state. */
    data class Idle(
        override val persona: Persona,
        override val modelReadiness: ModelReadiness,
        val error: CaptureError? = null,
    ) : CaptureUiState

    /** Active 30 s capture. `elapsedMs` is wall-clock; `recentLevels` is a fixed-size RMS window. */
    data class Recording(
        override val persona: Persona,
        override val modelReadiness: ModelReadiness,
        val elapsedMs: Long,
        val recentLevels: List<Float>,
    ) : CaptureUiState

    /**
     * Transient spinner between STOP (or typed submit) and the entry being persisted — the
     * window where call-1 transcription is still in flight and there is no entry to open yet.
     * Once the entry persists the VM emits a navigation event and returns to [Idle].
     */
    data class Submitting(override val persona: Persona, override val modelReadiness: ModelReadiness) : CaptureUiState
}

/** Local-model readiness drives the REC button + status-pill copy. */
sealed interface ModelReadiness {
    /** Engine initialized; both the voice and typed paths are usable. */
    object Ready : ModelReadiness

    /** Engine warming up after cold start. REC + typed both gated until Ready (ADR-013). */
    object Loading : ModelReadiness

    /** Active artifact download in progress. */
    data class Downloading(val percent: Int) : ModelReadiness {
        init {
            require(percent in PERCENT_RANGE) { "Downloading percent must be in 0..100 (got $percent)" }
        }

        private companion object {
            val PERCENT_RANGE = 0..100
        }
    }

    /** Wi-Fi went away mid-download. REC + typed both gated until Ready (ADR-013). */
    object Paused : ModelReadiness
}

/**
 * Surfaced as a transient banner / chrome state. Mic errors come from the permission flow;
 * inference errors come from a failed call-1 transcription (no entry was persisted).
 */
sealed interface CaptureError {
    object MicDenied : CaptureError
    object MicBlocked : CaptureError
    object MicUnavailable : CaptureError
    data class InferenceFailed(val reason: Reason) : CaptureError {
        enum class Reason { TIMED_OUT, PARSE_FAILED, ENGINE_FAILED }
    }
}
