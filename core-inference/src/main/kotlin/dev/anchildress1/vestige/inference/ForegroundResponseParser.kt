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
 * Tags must appear in the order transcription → follow_up. Tags are case-sensitive and matched
 * non-greedily so a multi-line body works. Content is allowed to contain almost anything —
 * collision with a literal closing tag (e.g., a user dictating "less than slash transcription
 * greater than") is the only remaining ambiguity, and that is vanishingly unlikely in spoken
 * cognition-tracker dumps. STT-C measures parse-success rate on a real E4B transcript set.
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
        val transcriptionMatch = TRANSCRIPTION_TAG.find(raw)
        val followUpMatch = transcriptionMatch?.let {
            FOLLOW_UP_TAG.find(raw, startIndex = it.range.last + 1)
        }
        val transcription = transcriptionMatch?.groupValues?.get(1)?.trim().orEmpty()
        val followUp = followUpMatch?.groupValues?.get(1)?.trim().orEmpty()
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
