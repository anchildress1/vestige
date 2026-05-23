package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ResolvedExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Emits 1–2 per-entry observations per `concept-locked.md` §"Analysis (two-layer)" and
 * `adrs/ADR-002-multi-lens-extraction-pattern.md` §3.
 *
 * Observations are model-derived only: one short model call grounded in the resolved 3-lens
 * fields. The response is parsed + validated against the AGENTS.md §guardrail 7 /
 * `concept-locked.md` §"Voice rules" forbidden-phrase list. A single retry on validation
 * violation; if the retry still violates, the generator returns an empty list rather than
 * persisting noise. Deterministic, metadata-derived lines (e.g. capture-timestamp) are never
 * surfaced as observations — those are not model reads of the entry.
 *
 * The `pattern-callout` evidence type is never emitted here — it lives in the pattern engine's
 * deterministic post-append step (per ADR-002 §3).
 */
@Suppress("ReturnCount") // Retry-loop early-returns read clearer than nested when chains.
class ObservationGenerator(
    private val engine: LiteRtLmEngine,
    private val parser: (String) -> List<EntryObservation>? = ObservationResponseParser::parse,
    private val systemPromptLoader: () -> String = { loadResource("/observations/system.txt") },
    private val outputSchemaLoader: () -> String = { loadResource("/observations/output-schema.txt") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun generate(
        entryText: String,
        resolved: ResolvedExtraction,
        capturedAt: ZonedDateTime,
        temporalHistory: List<HistoryChunk> = emptyList(),
    ): List<EntryObservation> = withContext(ioDispatcher) {
        require(entryText.isNotBlank()) { "ObservationGenerator.generate requires a non-blank entryText" }
        runModel(entryText, resolved, capturedAt, temporalHistory)
    }

    private suspend fun runModel(
        entryText: String,
        resolved: ResolvedExtraction,
        capturedAt: ZonedDateTime,
        temporalHistory: List<HistoryChunk>,
    ): List<EntryObservation> {
        val systemInstruction = composeSystemInstruction(resolved, capturedAt, temporalHistory)
        val userText = entryText.trimEnd()
        repeat(MAX_MODEL_ATTEMPTS) { attempt ->
            val raw = attemptModelCall(systemInstruction, userText, attempt + 1) ?: return@repeat
            val parsed = parser(raw)
            if (!parsed.isNullOrEmpty()) {
                Log.d(TAG, "model attempt ${attempt + 1} produced ${parsed.size} observations")
                return parsed.take(MAX_OBSERVATIONS)
            }
            Log.w(TAG, "model attempt ${attempt + 1} produced no usable observations (parser logged the reason)")
        }
        Log.w(TAG, "observation model attempts exhausted; returning empty list")
        return emptyList()
    }

    private suspend fun attemptModelCall(systemInstruction: String, userText: String, attempt: Int): String? = try {
        buildString {
            engine.streamText(systemInstruction, userText).collect { append(it) }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") engineError: Exception) {
        // Native LiteRT-LM throws unchecked types we can't enumerate; the rest of the generator
        // treats a thrown attempt as "this attempt produced no usable text" and lets the retry
        // loop decide whether more attempts remain.
        Log.w(TAG, "model attempt $attempt threw ${engineError.javaClass.simpleName}")
        null
    }

    private fun composeSystemInstruction(
        resolved: ResolvedExtraction,
        capturedAt: ZonedDateTime,
        temporalHistory: List<HistoryChunk>,
    ): String = buildString {
        append(systemPromptLoader())
        append("\n\n")
        append(outputSchemaLoader())
        append("\n\n## CAPTURE TIME\n")
        append(capturedAt.format(CAPTURE_TIME_FORMAT))
        if (temporalHistory.isNotEmpty()) {
            append("\n\n## RECURRING CONTEXT\n")
            append(renderTemporalHistory(temporalHistory))
        }
        append("\n\n## RESOLVED FIELDS\n")
        append(renderResolved(resolved))
    }

    // Prior entries at this entry's weekday + time-of-day, oldest-context-first as fed. The count
    // (plus the current entry) is the recurrence signal the model reads — it is not told a number.
    private fun renderTemporalHistory(history: List<HistoryChunk>): String =
        history.mapIndexed { index, chunk -> "${index + 1}. ${chunk.text}" }.joinToString("\n")

    private fun renderResolved(resolved: ResolvedExtraction): String {
        if (resolved.fields.isEmpty()) return "(no resolved fields)"
        return resolved.fields.entries.joinToString(separator = "\n") { (key, field) ->
            "- $key (${field.verdict.name.lowercase()}): ${renderValue(field.value)}"
        }
    }

    private fun renderValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { renderValue(it) }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k=${renderValue(v)}" }
        else -> value.toString()
    }

    private companion object {
        private const val TAG = "VestigeObservationGen"
        private const val MAX_OBSERVATIONS = 2
        private const val MAX_MODEL_ATTEMPTS = 2

        // Local wall-clock the entry was captured at, e.g. "Sunday 03:14" — lets the model note
        // an odd-hour capture as a sourced observation instead of us fabricating one from metadata.
        private val CAPTURE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.US)

        private fun loadResource(path: String): String {
            val stream = ObservationGenerator::class.java.getResourceAsStream(path)
                ?: error("Observation prompt resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
