package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.Lens

/** One retrieved history chunk surfaced as prior-entry context. Caller passes the top three. */
data class HistoryChunk(val patternId: String?, val text: String)

/**
 * Composed background-lens prompt. [systemInstruction] is the role/lens/surface/schema/history
 * context (the SDK's instruction channel); [userText] is the entry being analyzed (the message).
 * [tokenEstimate] uses a rough 4-chars-per-token rule over both halves.
 */
data class ComposedPrompt(val lens: Lens, val systemInstruction: String, val userText: String, val tokenEstimate: Int)

/**
 * Builds one background lens prompt by stacking the lens framing on top of the five surface
 * instructions, the output schema, the retrieved-history block, and the entry text. Lens and
 * surface text load independently from `resources/lenses/` and `resources/surfaces/` so a tweak
 * to one module cannot diff another. Persona modules never appear here — extraction is voice-free.
 */
object PromptComposer {

    private const val SYSTEM_HEADER =
        "You are an extraction-only reader for a cognition-tracking app. " +
            "You do not greet the user, do not ask follow-up questions, and do not produce " +
            "tone. The user is not in this loop — your output is consumed by other code. " +
            "Every tag is kebab-case and at most two words (one hyphen max), e.g. \"standup\", " +
            "\"battery-died\" — never longer."

    private val SURFACE_ORDER: List<String> = listOf(
        "behavioral",
        "state",
        "vocabulary",
        "commitment",
        "recurrence",
    )

    fun compose(lens: Lens, entryText: String, retrievedHistory: List<HistoryChunk> = emptyList()): ComposedPrompt {
        require(entryText.isNotBlank()) { "PromptComposer.compose requires a non-blank entryText" }
        val capped = retrievedHistory.take(MAX_HISTORY_CHUNKS).map { it.copy(text = budgetText(it.text)) }

        val systemInstruction = buildString {
            append(SYSTEM_HEADER)
            append("\n\n")
            append(loadLens(lens))
            SURFACE_ORDER.forEach { surface ->
                append("\n\n")
                append(loadSurface(surface))
            }
            append("\n\n")
            append(loadResource("/lenses/output-schema.txt"))
            append("\n\n")
            append(renderHistory(capped))
        }
        val userText = entryText.trimEnd()

        val tokenEstimate = estimateTokens(systemInstruction) + estimateTokens(userText)
        Log.d(
            TAG,
            "compose lens=$lens systemChars=${systemInstruction.length} " +
                "entryChars=${userText.length} tokens~=$tokenEstimate history=${capped.size}",
        )
        return ComposedPrompt(
            lens = lens,
            systemInstruction = systemInstruction,
            userText = userText,
            tokenEstimate = tokenEstimate,
        )
    }

    private fun loadLens(lens: Lens): String = loadResource(lensResourcePath(lens)).trimEnd()

    private fun loadSurface(slug: String): String = loadResource("/surfaces/$slug.txt").trimEnd()

    private fun lensResourcePath(lens: Lens): String = when (lens) {
        Lens.LITERAL -> "/lenses/literal.txt"
        Lens.INFERENTIAL -> "/lenses/inferential.txt"
        Lens.SKEPTICAL -> "/lenses/skeptical.txt"
    }

    private fun loadResource(path: String): String {
        val stream = PromptComposer::class.java.getResourceAsStream(path)
            ?: error("Prompt module resource missing: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun budgetText(text: String): String {
        // Reserve room for the ellipsis so the cap is strict (output ≤ MAX_HISTORY_CHARS_PER_CHUNK).
        if (text.length <= MAX_HISTORY_CHARS_PER_CHUNK) return text
        val budget = MAX_HISTORY_CHARS_PER_CHUNK - ELLIPSIS.length
        return text.substring(0, budget).trimEnd() + ELLIPSIS
    }

    // Prior entries as plain content — no pattern ids. Recurrence is judged on whether the current
    // entry repeats the behavior/state these describe; the app owns which pattern that maps to.
    private fun renderHistory(chunks: List<HistoryChunk>): String {
        if (chunks.isEmpty()) {
            return "## RETRIEVED HISTORY\n(no prior entries)"
        }
        return buildString {
            append("## RETRIEVED HISTORY")
            chunks.forEach { chunk ->
                append("\n- ")
                append(chunk.text.replace("\n", "\n  "))
            }
        }
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    private const val TAG = "VestigePromptComposer"
    private const val MAX_HISTORY_CHUNKS = 3
    private const val MAX_HISTORY_CHARS_PER_CHUNK = 600
    private const val CHARS_PER_TOKEN = 4
    private const val ELLIPSIS = "…"
}
