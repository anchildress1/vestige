package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.ResolvedExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Emits 1–2 per-entry observations per `concept-locked.md` §"Analysis (two-layer)" and
 * `adrs/ADR-002-multi-lens-extraction-pattern.md` §3.
 *
 * Strategy:
 *
 * 1. **Deterministic first.** Walks the resolved fields for stated commitments and vocabulary
 *    contradictions; checks the capture timestamp for goblin-hours (00:00–04:59 local) volunteered
 *    context. If 1+ deterministic observations land, they win — the model call is skipped.
 * 2. **Model fallback.** When deterministic assembly produces nothing useful, fires one short
 *    model call. The response is parsed + validated against the AGENTS.md §guardrail 7 /
 *    `concept-locked.md` §"Voice rules" forbidden-phrase list. A single retry on validation
 *    violation; if the retry still violates, the generator returns an empty list rather than
 *    persisting noise.
 *
 * The `pattern-callout` evidence type is never emitted here — it lives in the pattern engine's
 * deterministic post-append step (per ADR-002 §3).
 */
@Suppress("ReturnCount") // Deterministic-assembly guards are clearer as early-returns than nested when chains.
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
    ): List<EntryObservation> = withContext(ioDispatcher) {
        require(entryText.isNotBlank()) { "ObservationGenerator.generate requires a non-blank entryText" }

        val deterministic = buildDeterministic(resolved, capturedAt).take(MAX_OBSERVATIONS)
        if (deterministic.isNotEmpty()) {
            Log.d(TAG, "deterministic observations=${deterministic.size}; skipping model call")
            return@withContext deterministic
        }

        runModelFallback(entryText, resolved)
    }

    private fun buildDeterministic(resolved: ResolvedExtraction, capturedAt: ZonedDateTime): List<EntryObservation> {
        val out = mutableListOf<EntryObservation>()
        commitmentObservation(resolved)?.let { out += it }
        vocabularyContradictionObservation(resolved)?.let { out += it }
        if (out.isEmpty()) {
            goblinHoursObservation(capturedAt)?.let { out += it }
        }
        return out
    }

    private fun commitmentObservation(resolved: ResolvedExtraction): EntryObservation? {
        val commitment = resolved.fields[KEY_COMMITMENT]?.value as? Map<*, *> ?: return null
        val text = (commitment["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val topic = (commitment["topic_or_person"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val line = if (topic != null) {
            "You said you'd do this — flagged: \"$text\" (re: $topic)."
        } else {
            "You said you'd do this — flagged: \"$text\"."
        }
        return EntryObservation(line, ObservationEvidence.COMMITMENT_FLAG, listOf(KEY_COMMITMENT))
    }

    private fun vocabularyContradictionObservation(resolved: ResolvedExtraction): EntryObservation? {
        val contradictions = resolved.fields[KEY_VOCAB_CONTRADICTIONS]?.value as? List<*> ?: return null
        val pick = contradictions.firstOrNull() as? Map<*, *> ?: return null
        val termA = (pick["term_a"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val termB = (pick["term_b"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val line = "You said \"$termA\" and \"$termB\" in the same entry."
        return EntryObservation(line, ObservationEvidence.VOCABULARY_CONTRADICTION, listOf(KEY_VOCAB_CONTRADICTIONS))
    }

    private fun goblinHoursObservation(capturedAt: ZonedDateTime): EntryObservation? {
        if (capturedAt.hour !in GOBLIN_HOUR_RANGE) return null
        return EntryObservation(
            text = "Captured between midnight and 5am — flagged as goblin hours.",
            evidence = ObservationEvidence.VOLUNTEERED_CONTEXT,
            fields = listOf(KEY_CAPTURED_AT),
        )
    }

    private suspend fun runModelFallback(entryText: String, resolved: ResolvedExtraction): List<EntryObservation> {
        val prompt = composeModelPrompt(entryText, resolved)
        repeat(MAX_MODEL_ATTEMPTS) { attempt ->
            val raw = attemptModelCall(prompt, attempt + 1) ?: return@repeat
            val parsed = parser(raw)
            if (!parsed.isNullOrEmpty()) {
                Log.d(TAG, "model attempt ${attempt + 1} produced ${parsed.size} observations")
                return parsed.take(MAX_OBSERVATIONS)
            }
            Log.w(TAG, "model attempt ${attempt + 1} parsed null/empty or forbidden-phrase violation")
        }
        Log.w(TAG, "observation model fallback exhausted; returning empty list")
        return emptyList()
    }

    private suspend fun attemptModelCall(prompt: String, attempt: Int): String? = try {
        engine.generateText(prompt)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") engineError: Exception) {
        // Native LiteRT-LM throws unchecked types we can't enumerate; the rest of the generator
        // treats a thrown attempt as "this attempt produced no usable text" and lets the retry
        // loop decide whether more attempts remain.
        Log.w(TAG, "model attempt $attempt threw ${engineError.javaClass.simpleName}: ${engineError.message}")
        null
    }

    private fun composeModelPrompt(entryText: String, resolved: ResolvedExtraction): String = buildString {
        append(systemPromptLoader())
        append("\n\n")
        append(outputSchemaLoader())
        append("\n\n## RESOLVED FIELDS\n")
        append(renderResolved(resolved))
        append("\n\n## ENTRY\n")
        append(entryText.trimEnd())
        append('\n')
    }

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
        private val GOBLIN_HOUR_RANGE = 0..4

        private const val KEY_COMMITMENT = "stated_commitment"
        private const val KEY_VOCAB_CONTRADICTIONS = "vocabulary_contradictions"
        private const val KEY_CAPTURED_AT = "captured_at"

        private fun loadResource(path: String): String {
            val stream = ObservationGenerator::class.java.getResourceAsStream(path)
                ?: error("Observation prompt resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
