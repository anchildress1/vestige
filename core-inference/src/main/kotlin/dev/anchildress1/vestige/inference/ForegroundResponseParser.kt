package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/**
 * Parses Gemma 4's foreground response text into a [ForegroundResult]. Format is
 * XML-style tags per ADR-002 §"Structured-output reliability" — JSON parse-rate on E4B is the
 * documented risk that pushed us off JSON in Action Item #2, and `## TRANSCRIPTION` / `## FOLLOW_UP`
 * markdown headers were rejected (codex review round 2) because a verbatim transcription can
 * legitimately contain those marker lines and would be silently sliced.
 *
 * Expected shape:
 * ```
 * <transcription>the user's spoken words verbatim</transcription>
 * <follow_up>the model's follow-up question or remark</follow_up>
 * ```
 *
 * **Exactly one of each tag.** Codex review rounds 3 and 4 walked us through the failure modes
 * of guessing which block is "the answer" when the model emits more than one of either tag —
 * first-pair picks an echoed example, last-pair picks a trailing reminder, and either heuristic
 * silently corrupts saved entries. The honest contract: the system prompt asks the model for
 * exactly one transcription block and exactly one follow_up block; if it returns more, that
 * is `AMBIGUOUS_BLOCKS` and the caller decides what to do (re-prompt, mark as parse-error,
 * surface the failure to STT-C instrumentation).
 *
 * Tags must appear in the order transcription → follow_up. Tags are case-sensitive and matched
 * non-greedily so a multi-line body works. Content is preserved verbatim — no trimming — to
 * honor the `personas/shared.txt` "exact and unaltered" transcription rule (codex round 4 P2).
 * Empty / whitespace-only bodies still register as MISSING_*, but otherwise the captured text
 * passes through byte-for-byte. STT-C measures parse-success rate on a real E4B transcript set.
 */
internal object ForegroundResponseParser {

    private val TRANSCRIPTION_TAG = Regex("(?s)<transcription>(.*?)</transcription>")
    private val FOLLOW_UP_TAG = Regex("(?s)<follow_up>(.*?)</follow_up>")

    fun parse(raw: String, persona: Persona, elapsedMs: Long, completedAt: Instant): ForegroundResult {
        val outcome = extract(raw)
        return when (outcome) {
            is Extracted.Ok -> ForegroundResult.Success(
                persona = persona,
                rawResponse = raw,
                elapsedMs = elapsedMs,
                completedAt = completedAt,
                transcription = outcome.transcription,
                followUp = outcome.followUp,
            )

            is Extracted.Bad -> ForegroundResult.ParseFailure(
                persona = persona,
                rawResponse = raw,
                elapsedMs = elapsedMs,
                completedAt = completedAt,
                reason = outcome.reason,
            )
        }
    }

    private fun extract(raw: String): Extracted = when {
        raw.isBlank() -> Extracted.Bad(ForegroundResult.ParseReason.EMPTY_RESPONSE)
        else -> splitOnHeaders(raw)
    }

    private fun splitOnHeaders(raw: String): Extracted {
        val transcriptionMatches = TRANSCRIPTION_TAG.findAll(raw).toList()
        val followUpMatches = FOLLOW_UP_TAG.findAll(raw).toList()
        if (transcriptionMatches.size > 1 || followUpMatches.size > 1) {
            return Extracted.Bad(ForegroundResult.ParseReason.AMBIGUOUS_BLOCKS)
        }
        val transcriptionMatch = transcriptionMatches.singleOrNull()
        val followUpMatch = followUpMatches.singleOrNull()
        val orderedFollowUp = followUpMatch?.takeIf {
            transcriptionMatch == null ||
                it.range.first > transcriptionMatch.range.last
        }
        // Verbatim — no `.trim()`. The transcription contract from `personas/shared.txt` says
        // "exact and unaltered"; collapsing wrapping whitespace would mutate user content.
        val transcription = transcriptionMatch?.groupValues?.get(1).orEmpty()
        val followUp = orderedFollowUp?.groupValues?.get(1).orEmpty()
        return when {
            transcriptionMatch == null -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)
            orderedFollowUp == null -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_FOLLOW_UP)
            transcription.isBlank() -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)
            followUp.isBlank() -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_FOLLOW_UP)
            else -> Extracted.Ok(transcription, followUp)
        }
    }

    private sealed interface Extracted {
        data class Ok(val transcription: String, val followUp: String) : Extracted
        data class Bad(val reason: ForegroundResult.ParseReason) : Extracted
    }
}
