package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.DetectedPattern
import dev.anchildress1.vestige.model.Persona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One short model call per newly-active pattern per ADR-003 §"Pattern title generation". Output is
 * capped at 24 characters, stripped of quotes/punctuation, and post-validated against the shared
 * forbidden-phrase list (interpretive language never lands in a title).
 *
 * Title generation is best-effort — the caller persists a deterministic fallback when the model
 * fails so a new pattern always lands with a non-empty title.
 */
class PatternTitleGenerator(
    private val engine: LiteRtLmEngine,
    private val personaPromptComposer: (Persona) -> String = PersonaPromptComposer::compose,
    private val templateLoader: () -> String = { loadResource("/observations/pattern-title.txt") },
    private val forbiddenPhraseDetector: (String) -> Boolean = ObservationResponseParser::containsForbiddenPhrase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Returns a cleaned title ≤24 chars, or `null` when the model output cannot be salvaged. */
    suspend fun generate(persona: Persona, pattern: DetectedPattern): String? = withContext(ioDispatcher) {
        val prompt = buildPrompt(persona, pattern)
        val raw = try {
            engine.generateText(prompt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "title generation threw ${error.javaClass.simpleName}: ${error.message}")
            return@withContext null
        }
        val cleaned = sanitize(raw) ?: return@withContext null
        if (forbiddenPhraseDetector(cleaned)) {
            Log.w(TAG, "title rejected — contained forbidden phrase")
            return@withContext null
        }
        cleaned
    }

    private fun buildPrompt(persona: Persona, pattern: DetectedPattern): String = buildString {
        append(personaPromptComposer(persona).trimEnd())
        append("\n\n")
        append(templateLoader().trimEnd())
        append("\n\n## SIGNATURE\n")
        append("kind: ${pattern.kind.serial}\n")
        append("signature: ${pattern.signatureJson}\n")
        append('\n')
    }

    private fun sanitize(raw: String): String? {
        val stripped = raw.trim()
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .removePrefix("```").removeSuffix("```")
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
        return stripped.takeIf { it.isNotEmpty() }?.let(::truncateToCap)
    }

    private fun truncateToCap(candidate: String): String {
        if (candidate.length <= MAX_TITLE_CHARS) return candidate
        val trimmed = candidate.substring(0, MAX_TITLE_CHARS)
        val lastSpace = trimmed.lastIndexOf(' ')
        return if (lastSpace > MAX_TITLE_CHARS / 2) trimmed.substring(0, lastSpace) else trimmed.trim()
    }

    companion object {
        const val MAX_TITLE_CHARS = 24

        private const val TAG = "VestigePatternTitle"

        private fun loadResource(path: String): String {
            val stream = PatternTitleGenerator::class.java.getResourceAsStream(path)
                ?: error("Pattern-title prompt resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
