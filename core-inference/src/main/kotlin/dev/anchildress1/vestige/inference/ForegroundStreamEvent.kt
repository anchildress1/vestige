package dev.anchildress1.vestige.inference

/**
 * Progressive output of a streaming foreground call. Streamed [Transcription]/[FollowUpDelta] are
 * a UI approximation; [Terminal]'s parsed [ForegroundResult] is authoritative and the only thing
 * saved.
 */
sealed interface ForegroundStreamEvent {
    data class Transcription(val text: String) : ForegroundStreamEvent

    data class FollowUpDelta(val text: String) : ForegroundStreamEvent

    data class Terminal(val result: ForegroundResult) : ForegroundStreamEvent
}
