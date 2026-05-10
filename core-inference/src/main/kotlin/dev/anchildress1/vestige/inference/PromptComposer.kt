package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.Lens

/**
 * One retrieved history chunk for the recurrence surface (ADR-002 §Q2). Top three are passed by
 * the caller; older entries are out-of-budget per the ~500-token cap.
 */
data class HistoryChunk(val patternId: String?, val text: String)

/**
 * The composed system prompt for one background lens call. [tokenEstimate] is the rough
 * 4-chars-per-token approximation logged for ADR-002 §"Token budget per call" validation.
 */
data class ComposedPrompt(val lens: Lens, val text: String, val tokenEstimate: Int)

/**
 * Builds the system prompt for one background-pass lens call by composing the lens framing on
 * top of all five surface instructions, the JSON output schema, the retrieved-history block, and
 * the entry text (`concept-locked.md` §"Multi-lens extraction architecture", Story 2.5,
 * ADR-002 §"Background lens prompt").
 *
 * Lens and surface text live as classpath resources at `lenses/{slug}.txt` and
 * `surfaces/{slug}.txt`. The composer loads each module independently — no surface text reads a
 * lens path and vice versa — so daily prompt tuning of one module cannot diff another
 * (ADR-002 §"Why separate storage").
 *
 * Persona modules are forbidden here. Lens framing is the only voice in extraction
 * (`AGENTS.md` guardrail 9; ADR-002 §"Background lens prompt").
 */
object PromptComposer {

    private const val SYSTEM_HEADER =
        "You are an extraction-only reader for a cognition-tracking app. " +
            "You do not greet the user, do not ask follow-up questions, and do not produce " +
            "tone. The user is not in this loop — your output is consumed by other code."

    private val SURFACE_ORDER: List<String> = listOf(
        "behavioral",
        "state",
        "vocabulary",
        "commitment",
        "recurrence",
    )

    /**
     * Compose the prompt for [lens] over [entryText] with optional [retrievedHistory] (top 3,
     * ~500-token cap per ADR-002 §Q2). The returned [ComposedPrompt] carries a token estimate
     * that the caller logs against the 2K-per-system-block budget.
     */
    fun compose(lens: Lens, entryText: String, retrievedHistory: List<HistoryChunk> = emptyList()): ComposedPrompt {
        require(entryText.isNotBlank()) { "PromptComposer.compose requires a non-blank entryText" }
        val capped = retrievedHistory.take(MAX_HISTORY_CHUNKS).map { it.copy(text = budgetText(it.text)) }

        val text = buildString {
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
            append("\n\n")
            append("## ENTRY")
            append('\n')
            append(entryText.trimEnd())
            append('\n')
        }

        val tokenEstimate = estimateTokens(text)
        Log.d(TAG, "compose lens=$lens chars=${text.length} tokens~=$tokenEstimate history=${capped.size}")
        return ComposedPrompt(lens = lens, text = text, tokenEstimate = tokenEstimate)
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
        // Per-chunk cap so the top-3 retrieved-history block stays inside the ~500-token budget
        // from ADR-002 §Q2. Truncation preserves the leading sentence — the call is system
        // context, not authoritative source text. Reserve room for the ellipsis so the cap is
        // strict (output length ≤ MAX_HISTORY_CHARS_PER_CHUNK).
        if (text.length <= MAX_HISTORY_CHARS_PER_CHUNK) return text
        val budget = MAX_HISTORY_CHARS_PER_CHUNK - ELLIPSIS.length
        return text.substring(0, budget).trimEnd() + ELLIPSIS
    }

    private fun renderHistory(chunks: List<HistoryChunk>): String {
        if (chunks.isEmpty()) {
            return "## RETRIEVED HISTORY\n(no prior entries)"
        }
        return buildString {
            append("## RETRIEVED HISTORY")
            chunks.forEachIndexed { index, chunk ->
                append('\n')
                val metadata = chunk.patternId?.let { "pattern_id=$it" } ?: "pattern_id unavailable; context-only"
                append("- [${index + 1}] $metadata\n")
                append("  ")
                append(chunk.text.replace("\n", "\n  "))
            }
        }
    }

    private fun estimateTokens(text: String): Int {
        // 4-chars-per-token is the cheap rule of thumb. Phase 2 measurements replace this with a
        // real tokenizer if the budget gets tight.
        if (text.isEmpty()) return 0
        return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    private const val TAG = "VestigePromptComposer"
    private const val MAX_HISTORY_CHUNKS = 3
    private const val MAX_HISTORY_CHARS_PER_CHUNK = 600
    private const val CHARS_PER_TOKEN = 4
    private const val ELLIPSIS = "…"
}
