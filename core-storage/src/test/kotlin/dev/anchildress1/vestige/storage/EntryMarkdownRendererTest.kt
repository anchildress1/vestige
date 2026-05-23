package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.TemplateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EntryMarkdownRendererTest {

    @Test
    fun `filenameFor returns stored filename when present`() {
        val entry = EntryEntity(markdownFilename = "stable.md", entryText = "ignored", timestampEpochMs = 0L)

        assertEquals("stable.md", EntryMarkdownRenderer.filenameFor(entry))
    }

    @Test
    fun `filenameFor derives deterministic filename when row has no stored filename`() {
        val entry = EntryEntity(
            entryText = "Tuesday meeting flattened me",
            timestampEpochMs = Instant.parse("2026-05-19T13:30:00Z").toEpochMilli(),
        )

        assertEquals("2026-05-19T13-30-00Z--tuesday-meeting-flattened.md", EntryMarkdownRenderer.filenameFor(entry))
    }

    @Test
    fun `render writes frontmatter and body from the objectbox row`() {
        val entry = EntryEntity(
            markdownFilename = "one.md",
            entryText = "invoice again",
            followUpText = "What happened after the invoice?",
            persona = Persona.HARDASS,
            timestampEpochMs = Instant.parse("2026-05-19T13:30:00Z").toEpochMilli(),
            durationMs = 12_000L,
            templateLabel = TemplateLabel.AUDIT,
            recurrenceLink = "pattern-1",
            vocabularyWord = "hollow",
            statedCommitmentJson = """{"text":"send invoice"}""",
            confidenceJson = """{"tags":"CANONICAL"}""",
            entryObservationsJson = """[{"text":"invoice repeated"}]""",
            lensReceiptsJson = """[{"lens":"LITERAL","extracted":true}]""",
        )
        val markdown = EntryMarkdownRenderer.render(entry)

        assertTrue(markdown.contains("timestamp: 2026-05-19T13:30:00Z"))
        assertTrue(markdown.contains("duration_ms: 12000"))
        assertTrue(markdown.contains("persona: hardass"))
        assertTrue(markdown.contains("follow_up: What happened after the invoice?"))
        assertTrue(markdown.contains("template_label: audit"))
        assertTrue(markdown.contains("recurrence_link: pattern-1"))
        assertTrue(markdown.contains("vocabulary: hollow"))
        assertTrue(markdown.contains("""stated_commitment: {"text":"send invoice"}"""))
        assertTrue(markdown.contains("tags: []"))
        assertTrue(markdown.contains("""confidence: {"tags":"CANONICAL"}"""))
        assertTrue(markdown.contains("""entry_observations: [{"text":"invoice repeated"}]"""))
        assertTrue(markdown.contains("""lens_receipts: [{"lens":"LITERAL","extracted":true}]"""))
        assertTrue(markdown.endsWith("\ninvoice again\n"))
    }

    @Test
    fun `render writes null frontmatter and preserves existing body newline`() {
        val entry = EntryEntity(
            entryText = "already newline\n",
            timestampEpochMs = 0L,
            followUpText = null,
            templateLabel = null,
            recurrenceLink = null,
            statedCommitmentJson = null,
            confidenceJson = "",
            entryObservationsJson = "",
            lensReceiptsJson = "",
        )

        val markdown = EntryMarkdownRenderer.render(entry)

        assertTrue(markdown.contains("follow_up: null"))
        assertTrue(markdown.contains("template_label: null"))
        assertTrue(markdown.contains("recurrence_link: null"))
        assertTrue(markdown.contains("vocabulary: null"))
        assertTrue(markdown.contains("stated_commitment: null"))
        assertTrue(markdown.contains("tags: []"))
        assertTrue(markdown.contains("confidence: {}"))
        assertTrue(markdown.contains("entry_observations: []"))
        assertTrue(markdown.contains("lens_receipts: []"))
        assertTrue(markdown.endsWith("\nalready newline\n"))
    }

    @Test
    fun `render emits tags as inline empty array when entry has no tags`() {
        // Regression: bare `tags:` parses as null in YAML 1.2, breaking round-trip importers that
        // expect the field to always carry a sequence type. Empty rows must emit `tags: []`.
        val entry = EntryEntity(markdownFilename = "x.md", entryText = "body", timestampEpochMs = 0L)

        val markdown = EntryMarkdownRenderer.render(entry)

        assertTrue("tags must serialize as inline empty array", markdown.contains("\ntags: []\n"))
        assertEquals(
            "tags line must be the canonical inline form, never the bare-key form",
            -1,
            markdown.indexOf("\ntags:\n"),
        )
    }

    @Test
    fun `render quotes YAML scalar values containing a colon`() {
        val entry = EntryEntity(
            markdownFilename = "x.md",
            entryText = "body",
            timestampEpochMs = 0L,
            followUpText = "key: value",
        )

        val markdown = EntryMarkdownRenderer.render(entry)

        assertTrue("follow_up must be quoted when it contains `:`", markdown.contains("""follow_up: "key: value""""))
    }

    @Test
    fun `render escapes embedded newlines and quotes in scalar values`() {
        val entry = EntryEntity(
            markdownFilename = "x.md",
            entryText = "body",
            timestampEpochMs = 0L,
            followUpText = "line1\nline2 \"quoted\"",
        )

        val markdown = EntryMarkdownRenderer.render(entry)

        assertTrue(markdown.contains("""follow_up: "line1\nline2 \"quoted\"""""))
    }

    @Test
    fun `render emits exactly two frontmatter fences`() {
        val entry = EntryEntity(markdownFilename = "x.md", entryText = "body", timestampEpochMs = 0L)

        val markdown = EntryMarkdownRenderer.render(entry)

        // Count standalone `---\n` fence lines — header opens, header closes, nothing else.
        val fenceCount = markdown.lineSequence().count { it == "---" }
        assertEquals(2, fenceCount)
    }
}
