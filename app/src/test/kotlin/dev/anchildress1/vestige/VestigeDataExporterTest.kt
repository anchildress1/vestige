package dev.anchildress1.vestige

import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import dev.anchildress1.vestige.ui.onboarding.OnboardingStep
import io.mockk.every
import io.mockk.mockk
import io.objectbox.BoxStore
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class VestigeDataExporterTest {

    private lateinit var tempRoot: File
    private lateinit var dataDir: File
    private lateinit var boxStore: BoxStore
    private lateinit var markdownDir: File
    private lateinit var markdownStore: MarkdownEntryStore

    private val onboardingPrefs: OnboardingPrefs = mockk {
        every { isComplete } returns true
        every { defaultPersona } returns Persona.HARDASS
        every { currentStep } returns OnboardingStep.Wiring
    }

    @BeforeEach
    fun setUp() {
        tempRoot = newModuleTempRoot("vestige-exporter-")
        dataDir = newInMemoryObjectBoxDirectory("ob-exporter-")
        boxStore = openInMemoryBoxStore(dataDir)
        markdownDir = File(tempRoot, "md").apply { mkdirs() }
        markdownStore = MarkdownEntryStore(markdownDir)
    }

    @AfterEach
    fun tearDown() {
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    private fun exporter(md: MarkdownEntryStore = markdownStore): VestigeDataExporter =
        VestigeDataExporter(boxStore, md, onboardingPrefs)

    private fun snapshotOf(out: ByteArrayOutputStream): JSONObject =
        JSONObject(unzip(out).getValue(VestigeDataExporter.SNAPSHOT_ENTRY))

    private fun unzip(out: ByteArrayOutputStream): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    private fun putEntry(entity: EntryEntity): EntryEntity {
        boxStore.boxFor(EntryEntity::class.java).put(entity)
        return entity
    }

    @Test
    fun `snapshot carries onboarding settings`() {
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        val settings = snapshotOf(out).getJSONObject("settings")
        assertEquals(true, settings.getBoolean("onboarding_complete"))
        assertEquals("HARDASS", settings.getString("default_persona"))
        assertEquals("Wiring", settings.getString("current_step"))
    }

    @Test
    fun `empty database still produces a well-formed parseable snapshot`() {
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        val snapshot = snapshotOf(out)
        assertEquals("vestige.full-export", snapshot.getString("format"))
        assertEquals(0, snapshot.getJSONArray("entries").length())
        assertEquals(0, snapshot.getJSONArray("patterns").length())
        assertEquals(0, snapshot.getJSONArray("markdown_files").length())
    }

    @Test
    fun `snapshot top-level sections include schema_version, tags, and callout_cooldowns`() {
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        val snapshot = snapshotOf(out)
        assertEquals(2, snapshot.getInt("schema_version"))
        assertTrue(snapshot.has("tags"))
        assertEquals(0, snapshot.getJSONArray("tags").length())
        assertTrue(snapshot.has("callout_cooldowns"))
        assertEquals(0, snapshot.getJSONArray("callout_cooldowns").length())
    }

    @Test
    fun `null entry columns serialize as JSON null, populated columns as their value`() {
        putEntry(
            EntryEntity(
                markdownFilename = "a.md",
                entryText = "no follow-up here",
                followUpText = null,
                persona = Persona.WITNESS,
                timestampEpochMs = 10L,
                energyDescriptor = "wired",
                recurrenceLink = null,
                statedCommitmentJson = null,
                lastError = null,
                vector = null,
            ),
        )
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        val entry = snapshotOf(out).getJSONArray("entries").getJSONObject(0)
        assertEquals("a", entry.getString("entry_id"))
        assertTrue(entry.isNull("follow_up_text"))
        assertTrue(entry.isNull("recurrence_link"))
        assertTrue(entry.isNull("stated_commitment_json"))
        assertTrue(entry.isNull("last_error"))
        assertTrue(entry.isNull("vector"))
        assertEquals("wired", entry.getString("energy_descriptor"))
        assertEquals("no follow-up here", entry.getString("entry_text"))
    }

    @Test
    fun `legacy null lensReceiptsJson exports as an empty array string`() {
        val entity = putEntry(
            EntryEntity(markdownFilename = "legacy.md", entryText = "legacy row", timestampEpochMs = 1L),
        )
        entity.lensReceiptsJson = null
        boxStore.boxFor(EntryEntity::class.java).put(entity)
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        assertEquals(
            "[]",
            snapshotOf(out).getJSONArray("entries").getJSONObject(0).getString("lens_receipts_json"),
        )
    }

    @Test
    fun `populated lensReceiptsJson is exported verbatim`() {
        val receipts = """[{"lens":"LITERAL","extracted":true}]"""
        val entity = putEntry(
            EntryEntity(markdownFilename = "r.md", entryText = "has receipts", timestampEpochMs = 2L),
        )
        entity.lensReceiptsJson = receipts
        boxStore.boxFor(EntryEntity::class.java).put(entity)
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        assertEquals(
            receipts,
            snapshotOf(out).getJSONArray("entries").getJSONObject(0).getString("lens_receipts_json"),
        )
    }

    @Test
    fun `snapshot is internally consistent — a pattern references the exported entry`() {
        val tag = TagEntity(name = "invoice", entryCount = 1)
        boxStore.boxFor(TagEntity::class.java).put(tag)
        val entry = putEntry(
            EntryEntity(markdownFilename = "one.md", entryText = "invoice again", timestampEpochMs = 3L),
        )
        entry.tags.add(tag)
        boxStore.boxFor(EntryEntity::class.java).put(entry)
        val pattern = PatternEntity(
            patternId = "p1",
            kind = dev.anchildress1.vestige.model.PatternKind.COMMITMENT_RECURRENCE,
            signatureJson = """{"topic_or_person":"invoice"}""",
            title = "Invoice",
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 3L,
            state = dev.anchildress1.vestige.model.PatternState.ACTIVE,
            latestCalloutText = "Invoice keeps showing up.",
        )
        boxStore.boxFor(PatternEntity::class.java).put(pattern)
        pattern.supportingEntries.add(entry)
        boxStore.boxFor(PatternEntity::class.java).put(pattern)
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        val snapshot = snapshotOf(out)
        val exportedEntry = snapshot.getJSONArray("entries").getJSONObject(0)
        val exportedPattern = snapshot.getJSONArray("patterns").getJSONObject(0)
        assertEquals("invoice", exportedEntry.getJSONArray("tags").getString(0))
        assertEquals("one", exportedEntry.getString("entry_id"))
        assertEquals(
            exportedEntry.getString("entry_id"),
            exportedPattern.getJSONArray("supporting_entry_ids").getString(0),
        )
        assertEquals(
            exportedEntry.getLong("objectbox_id"),
            exportedPattern.getJSONArray("supporting_entry_objectbox_ids").getLong(0),
        )
        assertEquals(
            exportedEntry.getString("markdown_filename"),
            exportedPattern.getJSONArray("supporting_entry_markdown_filenames").getString(0),
        )
    }

    @Test
    fun `markdown manifest matches the files actually archived`() {
        val entriesDir = File(markdownDir, "entries").apply { mkdirs() }
        File(entriesDir, "one.md").writeText("first")
        File(entriesDir, "two.md").writeText("second")
        val out = ByteArrayOutputStream()

        exporter().writeTo(out)

        val zip = unzip(out)
        assertEquals("first", zip["entries/one.md"])
        assertEquals("second", zip["entries/two.md"])
        val manifest = snapshotOf(out).getJSONArray("markdown_files")
        assertEquals(
            listOf("one.md", "two.md"),
            List(manifest.length()) { manifest.getString(it) }.sorted(),
        )
    }

    @Test
    fun `a mid-archive file failure writes no partial archive to the target`() {
        val good = File(markdownDir, "good.md").apply { writeText("kept") }
        val missing = File(markdownDir, "missing.md") // never created
        val failingMarkdown = mockk<MarkdownEntryStore> {
            every { listAll() } returns listOf(good, missing)
        }
        val out = ByteArrayOutputStream()

        assertThrows(Exception::class.java) { exporter(failingMarkdown).writeTo(out) }

        assertEquals(0, out.size())
    }
}
