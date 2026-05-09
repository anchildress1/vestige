package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/**
 * Outcome of one foreground capture call (Phase 2 Story 2.2). Either the parsed
 * `{transcription, follow_up}` pair plus diagnostics, or a typed parse failure. Per ADR-002
 * §"Structured-output reliability" the foreground call does **not** retry on parse failure —
 * the error surfaces to the caller for instrumentation (STT-C parse-rate measurement) and the
 * UI fallback path described in `ux-copy.md` §"Error States".
 *
 * `elapsedMs` and `completedAt` are recorded on every result (success or failure) so ADR-002
 * §"Latency budget" can be validated against the documented 1–5 second target on the reference
 * device.
 */
sealed interface ForegroundResult {
    val persona: Persona
    val rawResponse: String
    val elapsedMs: Long
    val completedAt: Instant

    /** Parsed `{transcription, follow_up}`. Caller should append the user transcription to the
     *  transcript before rendering the model follow-up so the order matches the state machine in
     *  `CaptureSession` (`INFERRING -> TRANSCRIBED -> RESPONDED`). */
    data class Success(
        override val persona: Persona,
        override val rawResponse: String,
        override val elapsedMs: Long,
        override val completedAt: Instant,
        val transcription: String,
        val followUp: String,
    ) : ForegroundResult

    /** Structured parse failed. The raw response is preserved for instrumentation. STT-C tracks
     *  the rate of these per ADR-002 §"Structured-output reliability" / Action Item #2. */
    data class ParseFailure(
        override val persona: Persona,
        override val rawResponse: String,
        override val elapsedMs: Long,
        override val completedAt: Instant,
        val reason: ParseReason,
    ) : ForegroundResult

    enum class ParseReason {
        EMPTY_RESPONSE,
        MISSING_TRANSCRIPTION,
        MISSING_FOLLOW_UP,

        /**
         * Multiple `<transcription>` and/or `<follow_up>` blocks in the response. Per ADR-002
         * §"Structured-output reliability" the foreground prompt requires exactly one of each;
         * if Gemma echoes the schema, drifts into a reminder, or appends a second answer, we
         * surface a typed failure rather than guessing which block is the real one.
         */
        AMBIGUOUS_BLOCKS,
    }
}
