package dev.anchildress1.vestige.ui.capture

import dev.anchildress1.vestige.model.Persona

/**
 * Screen-level state for `CaptureScreen`. The four phase variants drive the layout swap
 * (idle frame vs live recording vs inferring placeholder vs review). Persona and model
 * readiness are common slots — every variant carries them so the chrome (`AppTop` status
 * pill, persona dropdown) renders without phase-conditional reads.
 */
sealed interface CaptureUiState {
    val persona: Persona
    val modelReadiness: ModelReadiness

    /** Pre-recording. May carry the last `Reviewing` payload so the transcript persists across taps. */
    data class Idle(
        override val persona: Persona,
        override val modelReadiness: ModelReadiness,
        val lastReview: ReviewState? = null,
        val error: CaptureError? = null,
    ) : CaptureUiState

    /** Active 30 s capture. `elapsedMs` is wall-clock; `recentLevels` is a fixed-size RMS window. */
    data class Recording(
        override val persona: Persona,
        override val modelReadiness: ModelReadiness,
        val elapsedMs: Long,
        val recentLevels: List<Float>,
    ) : CaptureUiState

    /** Foreground call in flight. Holds the `Reading the entry.` placeholder per ux-copy.md. */
    data class Inferring(
        override val persona: Persona,
        override val modelReadiness: ModelReadiness,
        val startedAtEpochMs: Long,
    ) : CaptureUiState

    /** Single-turn transcript per STT-B v1 scope: one USER + one MODEL turn from the foreground call. */
    data class Reviewing(
        override val persona: Persona,
        override val modelReadiness: ModelReadiness,
        val review: ReviewState,
    ) : CaptureUiState
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
 * inference errors come from `ForegroundResult.ParseFailure` or thrown engine failures.
 */
sealed interface CaptureError {
    data object MicDenied : CaptureError
    data object MicBlocked : CaptureError
    data object MicUnavailable : CaptureError
    data class InferenceFailed(val reason: Reason) : CaptureError {
        enum class Reason { TIMED_OUT, PARSE_FAILED, ENGINE_FAILED }
    }
}

/** Single transcript pair from one foreground call. */
data class ReviewState(val transcription: String, val followUp: String, val persona: Persona, val elapsedMs: Long)
