package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var store: MarkdownEntryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-md-${System.nanoTime()}")
        markdownDir = File(context.filesDir, "markdown-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        store = MarkdownEntryStore(markdownDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
        markdownDir.deleteRecursively()
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

    private companion object {
        val ISO_TIMESTAMP_MS: Long = Instant.parse("2026-05-09T14:32:15Z").toEpochMilli()
    }
}
