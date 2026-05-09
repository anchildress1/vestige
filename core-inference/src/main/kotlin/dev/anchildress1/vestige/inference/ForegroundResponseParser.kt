package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/**
 * Parses Gemma 4's foreground response text into a [ForegroundResult]. Format is
 * markdown-with-headers per ADR-002 §"Structured-output reliability" — JSON parse-rate on E4B
 * is the documented risk that pushed us off JSON in Action Item #2.
 *
 * Expected shape (whitespace tolerant; the model sometimes wraps headers in extra blank lines):
 * ```
 * ## TRANSCRIPTION
 * the user's spoken words verbatim
 *
 * ## FOLLOW_UP
 * the model's follow-up question or remark
 * ```
 *
 * Headers are matched only when they appear as a **whole line** (optionally surrounded by
 * whitespace). A user dictating the literal text "## FOLLOW_UP" mid-sentence must not split the
 * transcription — the verbatim-transcription contract from `personas/shared.txt` requires that
 * inline occurrences are preserved as content, not re-interpreted as section markers.
 *
 * Anything before the `## TRANSCRIPTION` line is ignored (Gemma tends to preface structured
 * output with a short courtesy line). Anything after the `## FOLLOW_UP` line is treated as the
 * follow-up body. STT-C will measure parse-success rate on a real E4B transcript set.
 */
internal object ForegroundResponseParser {

    private val TRANSCRIPTION_HEADER_LINE = Regex("(?m)^[ \\t]*##[ \\t]+TRANSCRIPTION[ \\t]*\$")
    private val FOLLOW_UP_HEADER_LINE = Regex("(?m)^[ \\t]*##[ \\t]+FOLLOW_UP[ \\t]*\$")

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
        val transcriptionMatch = TRANSCRIPTION_HEADER_LINE.find(raw)
        val followUpMatch = transcriptionMatch?.let {
            FOLLOW_UP_HEADER_LINE.find(raw, startIndex = it.range.last + 1)
        }
        val transcription = if (transcriptionMatch != null && followUpMatch != null) {
            raw.substring(transcriptionMatch.range.last + 1, followUpMatch.range.first).trim()
        } else {
            ""
        }
        val followUp = if (followUpMatch != null) {
            raw.substring(followUpMatch.range.last + 1).trim()
        } else {
            ""
        }
        return when {
            transcriptionMatch == null -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)
            followUpMatch == null -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_FOLLOW_UP)
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
