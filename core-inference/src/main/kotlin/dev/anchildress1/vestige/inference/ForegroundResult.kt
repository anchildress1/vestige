package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/** Outcome of one foreground capture call. No retry on parse failure — STT-C tracks the rate. */
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

    /** `recoveredTranscription` is set when the transcription parsed but the follow_up didn't. */
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
