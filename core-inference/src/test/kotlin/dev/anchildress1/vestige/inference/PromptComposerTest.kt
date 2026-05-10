package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Lens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptComposerTest {

    private val entry = "Standup ran long. Flatlined by eleven. Q3 doc, risk section, twelve minutes on one sentence."

    @Test
    fun `lens swap differs only in the lens framing block`() {
        val literal = PromptComposer.compose(Lens.LITERAL, entry).text
        val skeptical = PromptComposer.compose(Lens.SKEPTICAL, entry).text

        assertNotEquals(literal, skeptical)

        val literalLensBlock = extractBlock(literal, header = "## Lens:", terminator = "## Surface:")
        val skepticalLensBlock = extractBlock(skeptical, header = "## Lens:", terminator = "## Surface:")
        assertNotEquals(literalLensBlock, skepticalLensBlock)

        val withoutLensLiteral = literal.replace(literalLensBlock, "")
        val withoutLensSkeptical = skeptical.replace(skepticalLensBlock, "")
        assertEquals(withoutLensLiteral, withoutLensSkeptical)
    }

    @Test
    fun `composed prompt contains all five surfaces in canonical order`() {
        val text = PromptComposer.compose(Lens.LITERAL, entry).text
        val ordered = listOf(
            "## Surface: Behavioral",
            "## Surface: State",
            "## Surface: Vocabulary",
            "## Surface: Commitment",
            "## Surface: Recurrence",
        )
        var cursor = 0
        ordered.forEach { marker ->
            val idx = text.indexOf(marker, startIndex = cursor)
            assertTrue(idx >= 0) { "Surface marker '$marker' missing or out of order in composed prompt" }
            cursor = idx + marker.length
        }
    }

    @Test
    fun `composed prompt contains the JSON output schema and entry text`() {
        val composed = PromptComposer.compose(Lens.INFERENTIAL, entry)
        assertTrue(composed.text.contains("## Output schema"))
        assertTrue(composed.text.contains("## ENTRY"))
        assertTrue(composed.text.contains(entry))
    }

    @Test
    fun `persona modules never appear in extraction prompts`() {
        // AGENTS.md guardrail 9 + ADR-002: extraction is persona-agnostic.
        Lens.entries.forEach { lens ->
            val text = PromptComposer.compose(lens, entry).text
            assertFalse(text.contains("Persona: Witness"))
            assertFalse(text.contains("Persona: Hardass"))
            assertFalse(text.contains("Persona: Editor"))
        }
    }

    @Test
    fun `retrieved history is capped at three chunks`() {
        val chunks = (1..6).map { HistoryChunk(patternId = "p$it", text = "chunk $it text") }
        val text = PromptComposer.compose(Lens.LITERAL, entry, retrievedHistory = chunks).text

        assertTrue(text.contains("pattern_id=p1"))
        assertTrue(text.contains("pattern_id=p2"))
        assertTrue(text.contains("pattern_id=p3"))
        assertFalse(text.contains("pattern_id=p4"))
        assertFalse(text.contains("pattern_id=p5"))
        assertFalse(text.contains("pattern_id=p6"))
    }

    @Test
    fun `oversize history chunks are truncated under the per-chunk char cap`() {
        val giant = "x".repeat(2000)
        val chunks = listOf(HistoryChunk(patternId = "big", text = giant))
        val text = PromptComposer.compose(Lens.LITERAL, entry, retrievedHistory = chunks).text

        // Cap is 600 chars + ellipsis. The full 2000-char block must not appear.
        assertFalse(text.contains(giant))
        assertTrue(text.contains("…"))
    }

    @Test
    fun `empty history renders the no-history sentinel`() {
        val text = PromptComposer.compose(Lens.LITERAL, entry).text
        assertTrue(text.contains("(no prior entries)"))
    }

    @Test
    fun `chunks without a pattern id render pattern_id=null`() {
        val chunks = listOf(HistoryChunk(patternId = null, text = "ad-hoc historical chunk"))
        val text = PromptComposer.compose(Lens.LITERAL, entry, retrievedHistory = chunks).text
        assertTrue(text.contains("pattern_id=null"))
    }

    @Test
    fun `blank entry text throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptComposer.compose(Lens.LITERAL, entryText = "   ")
        }
    }

    @Test
    fun `token estimate is positive and tracks input length`() {
        val short = PromptComposer.compose(Lens.LITERAL, "short entry.")
        val withHistory = PromptComposer.compose(
            Lens.LITERAL,
            "short entry.",
            retrievedHistory = listOf(HistoryChunk(patternId = "p1", text = "y".repeat(400))),
        )
        assertTrue(short.tokenEstimate > 0)
        assertTrue(withHistory.tokenEstimate > short.tokenEstimate) {
            "Adding a 400-char history chunk should grow the token estimate"
        }
    }

    @Test
    fun `compose is idempotent for identical inputs`() {
        val first = PromptComposer.compose(Lens.LITERAL, entry).text
        val second = PromptComposer.compose(Lens.LITERAL, entry).text
        assertEquals(first, second)
    }

    @Test
    fun `every lens loads its own framing block`() {
        Lens.entries.forEach { lens ->
            val text = PromptComposer.compose(lens, entry).text
            val expectedHeader = when (lens) {
                Lens.LITERAL -> "## Lens: Literal"
                Lens.INFERENTIAL -> "## Lens: Inferential"
                Lens.SKEPTICAL -> "## Lens: Skeptical"
            }
            assertTrue(text.contains(expectedHeader)) {
                "Lens $lens missing expected header '$expectedHeader' in composed prompt"
            }
        }
    }

    /**
     * Extract `## Lens: ...` through (but not including) the next `## Surface:` marker. Used to
     * isolate the lens framing block when asserting "lens swap differs only here".
     */
    private fun extractBlock(text: String, header: String, terminator: String): String {
        val start = text.indexOf(header)
        require(start >= 0) { "Header '$header' not found" }
        val end = text.indexOf(terminator, startIndex = start)
        require(end > start) { "Terminator '$terminator' not found after '$header'" }
        return text.substring(start, end)
    }
}
