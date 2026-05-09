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
 * Anything before `## TRANSCRIPTION` is ignored (Gemma tends to preface structured output with a
 * short courtesy line). Anything after `## FOLLOW_UP` is treated as part of the follow-up body.
 * STT-C will measure parse-success rate on a real E4B transcript set.
 */
internal object ForegroundResponseParser {

    private const val TRANSCRIPTION_HEADER = "## TRANSCRIPTION"
    private const val FOLLOW_UP_HEADER = "## FOLLOW_UP"

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
        val transcriptionStart = raw.indexOf(TRANSCRIPTION_HEADER)
        val followUpStart = if (transcriptionStart >= 0) {
            raw.indexOf(FOLLOW_UP_HEADER, startIndex = transcriptionStart + TRANSCRIPTION_HEADER.length)
        } else {
            -1
        }
        val transcription = if (transcriptionStart >= 0 && followUpStart >= 0) {
            raw.substring(transcriptionStart + TRANSCRIPTION_HEADER.length, followUpStart).trim()
        } else {
            ""
        }
        val followUp = if (followUpStart >= 0) {
            raw.substring(followUpStart + FOLLOW_UP_HEADER.length).trim()
        } else {
            ""
        }
        return when {
            transcriptionStart < 0 -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)
            followUpStart < 0 -> Extracted.Bad(ForegroundResult.ParseReason.MISSING_FOLLOW_UP)
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
