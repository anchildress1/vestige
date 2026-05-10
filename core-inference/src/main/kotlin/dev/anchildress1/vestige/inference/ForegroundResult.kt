package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/**
 * Outcome of one foreground capture call. The call does not retry on parse failure — STT-C
 * tracks the rate (ADR-002 §"Structured-output reliability"). `elapsedMs` + `completedAt` are
 * recorded on every result so the latency budget (ADR-002 §"Latency budget") can be measured.
 */
sealed interface ForegroundResult {
    val persona: Persona
    val rawResponse: String
    val elapsedMs: Long
    val completedAt: Instant

    data class Success(
        override val persona: Persona,
        override val rawResponse: String,
        override val elapsedMs: Long,
        override val completedAt: Instant,
        val transcription: String,
        val followUp: String,
    ) : ForegroundResult

    /**
     * `recoveredTranscription` carries the user's transcription when the transcription block
     * parsed cleanly but the follow-up block didn't, so the caller can still advance
     * `entry_text` instead of dropping the user's words.
     */
    data class ParseFailure(
        override val persona: Persona,
        override val rawResponse: String,
        override val elapsedMs: Long,
        override val completedAt: Instant,
        val reason: ParseReason,
        val recoveredTranscription: String? = null,
    ) : ForegroundResult

    enum class ParseReason {
        EMPTY_RESPONSE,
        MISSING_TRANSCRIPTION,
        MISSING_FOLLOW_UP,

        /** Response carried multiple `<transcription>` and/or `<follow_up>` blocks. */
        AMBIGUOUS_BLOCKS,
    }
}
