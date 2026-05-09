package dev.anchildress1.vestige.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class EntryFilenameTest {

    @Test
    fun `slug strips stop words and keeps salient content`() {
        val slug = EntryFilename.generateSlug(
            "Standup ran long again. I was fine before it, then completely flattened by 11.",
        )
        // First six non-stop-words after lowercasing. `i`, `was`, `before`, `it`, `by` are stops;
        // `then`, `completely`, `flattened`, `11` are not (concrete words win — slug carries
        // the salient content).
        assertEquals("standup-ran-long-again-fine-then", slug)
    }

    @Test
    fun `slug truncates to max length and trims trailing hyphen`() {
        val text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet"
        val slug = EntryFilename.generateSlug(text)
        assertTrue(slug.length <= EntryFilename.SLUG_MAX_CHARS) {
            "Slug must be ≤ ${EntryFilename.SLUG_MAX_CHARS} chars, was ${slug.length}"
        }
        assertTrue(!slug.endsWith('-')) { "Slug must not end with hyphen, was '$slug'" }
    }

    @Test
    fun `slug falls back to entry on empty or all-stop-word text`() {
        assertEquals("entry", EntryFilename.generateSlug(""))
        assertEquals("entry", EntryFilename.generateSlug("   "))
        assertEquals("entry", EntryFilename.generateSlug("the and or of for to"))
    }

    @Test
    fun `slug strips punctuation and special characters`() {
        val slug = EntryFilename.generateSlug("Q3 launch doc — risk section!!! (urgent)")
        // After punctuation strip + stop-word filter, the joined six words run to 33 chars;
        // the ≤32-char cap drops the trailing `t` from `urgent`. Filename uniqueness is the
        // job — readability is the slug's job, fidelity to the original word isn't.
        assertEquals("q3-launch-doc-risk-section-urgen", slug)
    }

    @Test
    fun `filename uses ISO-8601 second UTC with hyphenated colons`() {
        // 2026-05-09T14:32:15Z → epoch millis
        val timestamp = Instant.parse("2026-05-09T14:32:15Z").toEpochMilli()
        val filename = EntryFilename.buildFilename(timestamp, "Quick capture about standup")
        assertEquals("2026-05-09T14-32-15Z--quick-capture-standup.md", filename)
    }

    @Test
    fun `resolveUnique returns input when no file exists`(@TempDir dir: File) {
        val name = "2026-05-09T14-32-15Z--quick.md"
        assertEquals(name, EntryFilename.resolveUnique(dir, name))
    }

    @Test
    fun `resolveUnique appends -2 -3 on collision`(@TempDir dir: File) {
        val name = "2026-05-09T14-32-15Z--quick.md"
        File(dir, name).writeText("")
        assertEquals("2026-05-09T14-32-15Z--quick-2.md", EntryFilename.resolveUnique(dir, name))
        File(dir, "2026-05-09T14-32-15Z--quick-2.md").writeText("")
        assertEquals("2026-05-09T14-32-15Z--quick-3.md", EntryFilename.resolveUnique(dir, name))
    }
}
