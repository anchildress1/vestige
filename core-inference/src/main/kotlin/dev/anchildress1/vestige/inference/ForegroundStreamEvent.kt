package dev.anchildress1.vestige.inference

/**
 * Progressive output of a streaming foreground call. [Transcription] fires once the
 * `</transcription>` close tag lands; [FollowUpDelta] fires per streamed chunk of follow-up body
 * text; [Terminal] carries the authoritative [ForegroundResult] parsed from the complete buffer
 * by [ForegroundResponseParser] — the streamed deltas are a UI approximation, the terminal value
 * is the one that gets saved.
 */
sealed interface ForegroundStreamEvent {
    data class Transcription(val text: String) : ForegroundStreamEvent

    data class FollowUpDelta(val text: String) : ForegroundStreamEvent

    data class Terminal(val result: ForegroundResult) : ForegroundStreamEvent
}
