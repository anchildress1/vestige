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
 * non-greedily so a multi-line body works.
 *
 * **Whitespace handling.** Tag bodies are `.trim()`-ed automatically and consistently. The
 * `personas/shared.txt` "exact and unaltered" rule is about the user's spoken words, not the
 * model's pretty-print wrapping — `<transcription>\n  hello\n</transcription>` and
 * `<transcription>hello</transcription>` should produce the same `entry_text` because the leading
 * and trailing whitespace is the model's formatting choice, not the user's content. Empty /
 * whitespace-only bodies still register as MISSING_*.
 *
 * **Known parse-failure case (STT-C measurement target).** A user dictating verbatim XML/HTML
 * markup that contains a literal `<transcription>` or `</transcription>` (or the matching
 * `<follow_up>` markers) inside the spoken content will trip the same delimiter-collision class
 * that pushed us off `## TRANSCRIPTION` markdown headers. There is no text-delimiter format
 * that side-steps this entirely without out-of-band escaping (length prefixes, JSON strings,
 * etc.); switching the format adds parse-rate risk on E4B that ADR-002 §"Structured-output
 * reliability" already explicitly accepts. Cognition-tracker dumps about behavior and attention
 * almost never include literal markup, so the trade-off lands here. STT-C measures the actual
 * rate on real model output and ADR-002 supersedes if it turns out to matter.
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
        // Auto-normalize wrapping whitespace. The model's pretty-print (`<tag>\n...\n</tag>`)
        // is formatting, not content — the user's spoken words are what's between the
        // boundary whitespace, not what surrounds it. Consistent stripping here means
        // downstream `entry_text` doesn't carry stray newlines into the markdown store.
        val transcription = transcriptionMatch?.groupValues?.get(1)?.trim().orEmpty()
        val followUp = orderedFollowUp?.groupValues?.get(1)?.trim().orEmpty()
        return when {
            transcriptionMatch == null -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)
            orderedFollowUp == null -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_FOLLOW_UP)
            transcription.isEmpty() -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)
            followUp.isEmpty() -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_FOLLOW_UP)
            else -> Extracted.Ok(transcription, followUp)
        }
    }

    private sealed interface Extracted {
        data class Ok(val transcription: String, val followUp: String) : Extracted
        data class Bad(val reason: ForegroundResult.ParseReason) : Extracted
    }
}
