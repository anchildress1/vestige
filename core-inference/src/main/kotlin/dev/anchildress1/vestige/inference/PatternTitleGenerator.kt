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
        val raw = try {
            engine.generateText(buildSystemInstruction(persona), buildSignature(pattern))
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

    private fun buildSystemInstruction(persona: Persona): String = buildString {
        append(personaPromptComposer(persona).trimEnd())
        append("\n\n")
        append(templateLoader().trimEnd())
    }

    private fun buildSignature(pattern: DetectedPattern): String = buildString {
        append("## SIGNATURE\n")
        append("kind: ${pattern.kind.serial}\n")
        append("signature: ${pattern.signatureJson}")
    }

    private fun sanitize(raw: String): String? {
        // Drop fenced blocks WHOLE — `removePrefix("```")` only stripped bare backticks, so a
        // `\`\`\`text\nAftermath\n\`\`\`` payload kept the language token as the first line
        // and persisted that as the title. Strip the entire fence wrapper (any language tag)
        // before line-splitting.
        val unfenced = FENCED_BLOCK.replace(raw, "$1")
        val stripped = unfenced.trim()
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.let(::stripPunctuation)
            .orEmpty()
        return stripped.takeIf { it.isNotEmpty() }?.let(::truncateToCap)
    }

    /**
     * The pattern-title prompt forbids punctuation except an optional hyphen. Strip the rest
     * so a model returning `Aftermath Loop!` doesn't persist a title that violates the format
     * contract the UI + tests expect.
     */
    private fun stripPunctuation(candidate: String): String = candidate.replace(DISALLOWED_PUNCTUATION, "").trim()

    private fun truncateToCap(candidate: String): String {
        if (candidate.length <= MAX_TITLE_CHARS) return candidate
        val trimmed = candidate.substring(0, MAX_TITLE_CHARS)
        val lastSpace = trimmed.lastIndexOf(' ')
        return if (lastSpace > MAX_TITLE_CHARS / 2) trimmed.substring(0, lastSpace) else trimmed.trim()
    }

    companion object {
        const val MAX_TITLE_CHARS = 24

        private const val TAG = "VestigePatternTitle"

        // Matches a fenced block with optional language tag: ```[lang]\n…\n```
        private val FENCED_BLOCK: Regex = Regex("```[A-Za-z0-9_-]*\\s*\\n?([\\s\\S]*?)```")

        // Anything that isn't a letter, digit, hyphen, or ASCII space gets dropped.
        private val DISALLOWED_PUNCTUATION: Regex = Regex("[^A-Za-z0-9\\- ]")

        private fun loadResource(path: String): String {
            val stream = PatternTitleGenerator::class.java.getResourceAsStream(path)
                ?: error("Pattern-title prompt resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
