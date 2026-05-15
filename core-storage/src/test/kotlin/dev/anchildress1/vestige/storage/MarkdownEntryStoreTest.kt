package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Story 1.7 — markdown source-of-truth round-trip. ObjectBox provides the same `EntryEntity`
 * that the production code path wires up, so the test exercises the actual `lateinit var
 * tags: ToMany<TagEntity>` initialization that `box.put()` performs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MarkdownEntryStoreTest {

    private lateinit var tempRoot: File
    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var store: MarkdownEntryStore

    @Before
    fun setUp() {
        tempRoot = newModuleTempRoot("markdown-entry-store-")
        dataDir = newInMemoryObjectBoxDirectory("objectbox-md-")
        markdownDir = File(tempRoot, "markdown-${System.nanoTime()}").apply { mkdirs() }
        boxStore = openInMemoryBoxStore(dataDir)
        store = MarkdownEntryStore(markdownDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `empty entry round-trips through markdown and matches the ObjectBox row`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val entry = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS)
        entryBox.put(entry)

        val written = store.write(entry)

        // Filename matches the ISO + slug contract.
        assertEquals("2026-05-09T14-32-15Z--entry.md", written.name)

        val readBack = store.read(written)
        assertEquals(entry.markdownFilename, readBack.markdownFilename)
        assertEquals(entry.timestampEpochMs, readBack.timestampEpochMs)
        assertEquals(entry.entryText, readBack.entryText)
        assertEquals(entry.templateLabel, readBack.templateLabel)
        assertEquals(entry.energyDescriptor, readBack.energyDescriptor)
        assertEquals(entry.recurrenceLink, readBack.recurrenceLink)
        assertEquals(entry.statedCommitmentJson, readBack.statedCommitmentJson)
        assertEquals(entry.confidenceJson, readBack.confidenceJson)
        assertEquals(entry.entryObservationsJson, readBack.entryObservationsJson)
        assertEquals(ExtractionStatus.COMPLETED, readBack.extractionStatus)
        assertEquals(0, readBack.attemptCount)
        assertNull(readBack.lastError)
    }

    @Test
    fun `populated entry round-trips body content tags and label`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()
        val standup = TagEntity(name = "standup")
        val flattened = TagEntity(name = "flattened")
        tagBox.put(standup)
        tagBox.put(flattened)

        val entry = EntryEntity(
            timestampEpochMs = ISO_TIMESTAMP_MS,
            entryText = "Standup ran long again. Stared at the launch doc.",
            templateLabel = TemplateLabel.AFTERMATH,
            energyDescriptor = "flattened",
            extractionStatus = ExtractionStatus.PENDING,
        )
        entry.tags.add(standup)
        entry.tags.add(flattened)
        entryBox.put(entry)

        val written = store.write(entry)
        val tags = store.readTagNames(written)
        assertEquals(listOf("flattened", "standup"), tags) // sorted lexicographically per architecture-brief

        val readBack = store.read(written)
        assertEquals(entry.entryText, readBack.entryText)
        assertEquals(TemplateLabel.AFTERMATH, readBack.templateLabel)
        assertEquals("flattened", readBack.energyDescriptor)
    }

    @Test
    fun `existing markdownFilename on an entry is reused on rewrite`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val entry = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS, entryText = "first")
        entryBox.put(entry)
        val first = store.write(entry)

        entry.entryText = "rewritten body"
        entryBox.put(entry)
        val second = store.write(entry)

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals("rewritten body", store.read(second).entryText)
    }

    @Test
    fun `colliding new entries get -2 suffix`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val a = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS, entryText = "Same start words to collide")
        val b = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS, entryText = "Same start words to collide")
        entryBox.put(a)
        entryBox.put(b)

        val firstFile = store.write(a)
        val secondFile = store.write(b)

        assertNotEquals(firstFile.name, secondFile.name)
        assertTrue("Collision suffix expected on $secondFile", secondFile.name.contains("-2.md"))
    }

    @Test
    fun `read rejects file with no frontmatter fence`() {
        markdownDir.mkdirs()
        val bad = File(markdownDir, "broken.md").apply { writeText("plain body without frontmatter\n") }
        val raised = runCatching { store.read(bad) }
        assertTrue("Reading frontmatter-less file must throw", raised.isFailure)
    }

    @Test
    fun `read rejects a non-existent file`() {
        val nonExistent = File(markdownDir, "does-not-exist.md")
        val raised = runCatching { store.read(nonExistent) }
        assertTrue("read must throw for a non-existent file", raised.isFailure)
        assertTrue(
            "Expected IllegalArgumentException, got ${raised.exceptionOrNull()?.javaClass?.name}",
            raised.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `read rejects file with unsupported schema_version`() {
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val bad = File(entriesDir, "bad.md").apply {
            writeText(
                """
                ---
                schema_version: 99
                timestamp: 2026-05-09T14:32:15Z
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                confidence: {}
                entry_observations: []
                ---
                
                body
                """.trimIndent(),
            )
        }

        val raised = runCatching { store.read(bad) }
        assertTrue("Unsupported schema_version must fail fast", raised.isFailure)
        assertTrue(
            "Expected IllegalArgumentException, got ${raised.exceptionOrNull()?.javaClass?.name}",
            raised.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `read rejects file with missing schema_version`() {
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val bad = File(entriesDir, "bad.md").apply {
            writeText(
                """
                ---
                timestamp: 2026-05-09T14:32:15Z
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                confidence: {}
                entry_observations: []
                ---
                
                body
                """.trimIndent(),
            )
        }

        val raised = runCatching { store.read(bad) }
        assertTrue("Missing schema_version must fail fast", raised.isFailure)
        assertTrue(
            "Expected IllegalStateException, got ${raised.exceptionOrNull()?.javaClass?.name}",
            raised.exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun `readTagNames rejects a non-existent file`() {
        val nonExistent = File(markdownDir, "does-not-exist.md")
        val raised = runCatching { store.readTagNames(nonExistent) }
        assertTrue("readTagNames must throw for a non-existent file", raised.isFailure)
        assertTrue(
            "Expected IllegalArgumentException, got ${raised.exceptionOrNull()?.javaClass?.name}",
            raised.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `listAll returns empty list when entries directory has not been created yet`() {
        assertEquals(emptyList<File>(), store.listAll())
    }

    @Test
    fun `read populates all optional fields when frontmatter carries them`() {
        // Hand-write a frontmatter shape with templateLabel + energy + recurrence + commitment
        // *populated* (not the `null` literal). This pins the takeUnless { it == NULL } branches
        // for every optional field — the empty-entry round-trip only exercises the null side.
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "populated.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                timestamp: 2026-05-09T14:32:15Z
                template_label: aftermath
                energy_descriptor: flattened
                recurrence_link: pat-007
                stated_commitment: {"text":"ship the launch doc","topic_or_person":"work","entry_id":"e1"}
                tags:
                  - work
                confidence: {"templateLabel":"CANONICAL"}
                entry_observations: [{"text":"stared at doc","evidence":"capture","fields":["focus"]}]
                ---

                Standup ran long again.
                """.trimIndent(),
            )
        }
        val readBack = store.read(file)
        assertEquals(TemplateLabel.AFTERMATH, readBack.templateLabel)
        assertEquals("flattened", readBack.energyDescriptor)
        assertEquals("pat-007", readBack.recurrenceLink)
        assertEquals(
            """{"text":"ship the launch doc","topic_or_person":"work","entry_id":"e1"}""",
            readBack.statedCommitmentJson,
        )
        assertEquals("""{"templateLabel":"CANONICAL"}""", readBack.confidenceJson)
        assertEquals(Instant.parse("2026-05-09T14:32:15Z").toEpochMilli(), readBack.timestampEpochMs)
    }

    @Test
    fun `read defaults timestamp to zero when timestamp key is absent`() {
        // Pins the `?: 0L` fallback. Production write() always emits a timestamp; this exists to
        // guarantee read() doesn't NPE on a hand-edited file that drops it.
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "no-ts.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                confidence: {}
                entry_observations: []
                ---

                body
                """.trimIndent(),
            )
        }
        assertEquals(0L, store.read(file).timestampEpochMs)
    }

    @Test
    fun `parseFrontmatter ignores lines without a colon and indented continuation lines`() {
        // The `colonIndex <= 0 || line.startsWith("  ")` skip branch is otherwise unhit — happy-
        // path frontmatter is always well-formed.
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "noisy.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                # commentary line with no colon should be skipped, not crashed on
                timestamp: 2026-05-09T14:32:15Z
                  continuation indented under timestamp — must not be parsed as a key
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                confidence: {}
                entry_observations: []
                ---

                body
                """.trimIndent(),
            )
        }
        val readBack = store.read(file)
        assertEquals(Instant.parse("2026-05-09T14:32:15Z").toEpochMilli(), readBack.timestampEpochMs)
        assertNull(readBack.templateLabel) // the `null` literal still resolved correctly
    }

    @Test
    fun `readTagNames terminates the tags block at the next non-indented key`() {
        // Pins the third arm of the tags-block parser: a non-indented, non-blank line ends the
        // block. Without this branch coverage, mid-frontmatter list parsing relies on file order.
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "tags-then-keys.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                timestamp: 2026-05-09T14:32:15Z
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                  - work
                  - launch
                confidence: {}
                entry_observations: []
                ---

                body
                """.trimIndent(),
            )
        }
        // Only the two `  - …` items count; `confidence:` terminates the tags block and is not
        // mistakenly captured as a tag.
        assertEquals(listOf("work", "launch"), store.readTagNames(file))
    }

    @Test
    fun `read fails fast when the closing frontmatter fence is missing`() {
        // Without the explicit guard, `substringBefore`/`substringAfter` silently treat the whole
        // file as both frontmatter and body — a malformed file would round-trip as garbage.
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "missing-closing-fence.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                timestamp: 2026-05-09T14:32:15Z

                body without a closing fence
                """.trimIndent(),
            )
        }
        val ex = assertThrows(IllegalArgumentException::class.java) { store.read(file) }
        assertTrue(
            "Error must mention the missing closing fence: ${ex.message}",
            ex.message?.contains("closing frontmatter fence") == true,
        )
    }

    @Test
    fun `listAll returns empty list when the entries path is a regular file rather than a directory`() {
        // Pins the `!entriesDir.isDirectory` early return — guards against a caller accidentally
        // pointing baseDir at a parent that contains a *file* called `entries`.
        markdownDir.mkdirs()
        File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).writeText("not a directory")
        assertEquals(emptyList<File>(), store.listAll())
    }

    @Test
    fun `listAll returns sorted markdown files only`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val a =
            EntryEntity(timestampEpochMs = Instant.parse("2026-05-09T14:32:15Z").toEpochMilli(), entryText = "alpha")
        val b =
            EntryEntity(timestampEpochMs = Instant.parse("2026-05-08T08:11:00Z").toEpochMilli(), entryText = "bravo")
        entryBox.put(a)
        entryBox.put(b)
        store.write(a)
        store.write(b)

        // Throw a non-md sibling in the entries dir.
        File(markdownDir, "${MarkdownEntryStore.ENTRIES_SUBDIR}/notes.txt").writeText("ignore me")

        val all = store.listAll()
        assertEquals(2, all.size)
        assertTrue(all.all { it.name.endsWith(".md") })
        assertEquals(all.map { it.name }.sorted(), all.map { it.name })
    }

    // --- durationMs tests ---

    @Test
    fun `durationMs round-trips through write and read`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val entry = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS, durationMs = 242_000L)
        entryBox.put(entry)

        val written = store.write(entry)
        assertTrue("frontmatter must contain duration_ms: 242000", written.readText().contains("duration_ms: 242000"))

        val readBack = store.read(written)
        assertEquals(242_000L, readBack.durationMs)
    }

    @Test
    fun `zero durationMs writes duration_ms 0 and reads back as 0`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val entry = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS, durationMs = 0L)
        entryBox.put(entry)

        val written = store.write(entry)
        assertTrue("frontmatter must contain duration_ms: 0", written.readText().contains("duration_ms: 0"))

        val readBack = store.read(written)
        assertEquals(0L, readBack.durationMs)
    }

    @Test
    fun `malformed duration_ms in frontmatter returns 0 without throwing`() {
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "bad-duration.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                timestamp: 2026-05-09T14:32:15Z
                duration_ms: not-a-number
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                confidence: {}
                entry_observations: []
                ---

                body text
                """.trimIndent(),
            )
        }
        val readBack = store.read(file)
        assertEquals(0L, readBack.durationMs)
    }

    @Test
    fun `Long MAX_VALUE durationMs round-trips exactly`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val entry = EntryEntity(timestampEpochMs = ISO_TIMESTAMP_MS, durationMs = Long.MAX_VALUE)
        entryBox.put(entry)

        val written = store.write(entry)
        val readBack = store.read(written)
        assertEquals(Long.MAX_VALUE, readBack.durationMs)
    }

    @Test
    fun `missing duration_ms key in frontmatter returns 0 without throwing`() {
        markdownDir.mkdirs()
        val entriesDir = File(markdownDir, MarkdownEntryStore.ENTRIES_SUBDIR).apply { mkdirs() }
        val file = File(entriesDir, "no-duration.md").apply {
            writeText(
                """
                ---
                schema_version: 1
                timestamp: 2026-05-09T14:32:15Z
                template_label: null
                energy_descriptor: null
                recurrence_link: null
                stated_commitment: null
                tags:
                confidence: {}
                entry_observations: []
                ---

                body text
                """.trimIndent(),
            )
        }
        val readBack = store.read(file)
        assertEquals(0L, readBack.durationMs)
    }

    private companion object {
        val ISO_TIMESTAMP_MS: Long = Instant.parse("2026-05-09T14:32:15Z").toEpochMilli()
    }
}
