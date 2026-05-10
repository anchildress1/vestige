package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import java.time.Instant

/**
 * Parses the foreground call's `<transcription>...</transcription><follow_up>...</follow_up>`
 * envelope. Tags must appear in that order, exactly one of each — duplicates surface as
 * `AMBIGUOUS_BLOCKS` (with the lone clean transcription preserved when one parsed). Tag bodies
 * are auto-trimmed; an empty body counts as missing.
 *
 * Known limitation: a transcription containing the literal tag markers will trip the
 * delimiter-collision case. No text-delimiter format dodges this without out-of-band escaping;
 * STT-C measures the rate on real output.
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
                recoveredTranscription = outcome.recoveredTranscription,
            )
        }
    }

    private fun extract(raw: String): Extracted = when {
        raw.isBlank() -> Extracted.Bad(ForegroundResult.ParseReason.EMPTY_RESPONSE)
        else -> splitOnTags(raw)
    }

    private fun splitOnTags(raw: String): Extracted {
        val transcriptionMatches = TRANSCRIPTION_TAG.findAll(raw).toList()
        val followUpMatches = FOLLOW_UP_TAG.findAll(raw).toList()
        if (transcriptionMatches.size > 1 || followUpMatches.size > 1) {
            val recoveredTranscription = transcriptionMatches.singleOrNull()
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeUnless(String::isEmpty)
            return Extracted.Bad(
                reason = ForegroundResult.ParseReason.AMBIGUOUS_BLOCKS,
                recoveredTranscription = recoveredTranscription,
            )
        }
        val transcriptionMatch = transcriptionMatches.singleOrNull()
        val followUpMatch = followUpMatches.singleOrNull()
        val orderedFollowUp = followUpMatch?.takeIf {
            transcriptionMatch == null ||
                it.range.first > transcriptionMatch.range.last
        }
        val transcription = transcriptionMatch?.groupValues?.get(1)?.trim().orEmpty()
        val followUp = orderedFollowUp?.groupValues?.get(1)?.trim().orEmpty()
        // Missing transcription wins over missing follow_up — losing the user's words is the
        // more fundamental failure to surface.
        return when {
            transcriptionMatch == null || transcription.isEmpty() ->
                Extracted.Bad(ForegroundResult.ParseReason.MISSING_TRANSCRIPTION)

            orderedFollowUp == null || followUp.isEmpty() -> Extracted.Bad(
                reason = ForegroundResult.ParseReason.MISSING_FOLLOW_UP,
                recoveredTranscription = transcription,
            )

            else -> Extracted.Ok(transcription, followUp)
        }
    }

    private sealed interface Extracted {
        data class Ok(val transcription: String, val followUp: String) : Extracted
        data class Bad(val reason: ForegroundResult.ParseReason, val recoveredTranscription: String? = null) :
            Extracted
    }
}
