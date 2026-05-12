package dev.anchildress1.vestige.ui.patterns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class PatternFormattingTest {

    @Test
    fun `formatShortDate emits MMM d at the given zone`() {
        val ms = Instant.parse("2026-05-12T12:00:00Z").toEpochMilli()
        assertEquals("May 12", formatShortDate(ms, ZoneOffset.UTC))
    }

    @Test
    fun `snippetOf preserves short text unchanged`() {
        assertEquals("hello world", snippetOf("hello world"))
    }

    @Test
    fun `snippetOf truncates long text with an ellipsis`() {
        val input = "a".repeat(80)
        val snippet = snippetOf(input)
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length <= 61)
    }

    @Test
    fun `snippetOf collapses newlines into spaces`() {
        assertEquals("hello world", snippetOf("hello\nworld"))
    }
}
