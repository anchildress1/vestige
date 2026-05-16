package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EntryStoreTest {

    private lateinit var tempRoot: File
    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var markdownDir: File
    private lateinit var markdownStore: MarkdownEntryStore
    private lateinit var entryStore: EntryStore

    @Before
    fun setUp() {
        tempRoot = newModuleTempRoot("entry-store-")
        dataDir = newInMemoryObjectBoxDirectory("objectbox-")
        markdownDir = File(tempRoot, "markdown-${System.nanoTime()}").apply { mkdirs() }
        boxStore = openInMemoryBoxStore(dataDir)
        markdownStore = MarkdownEntryStore(markdownDir)
        entryStore = EntryStore(boxStore, markdownStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        cleanupObjectBoxTempRoot(tempRoot, dataDir)
    }

    @Test
    fun `createPendingEntry persists transcription with PENDING status and writes markdown body`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        assertNotNull(row)
        assertEquals(SAMPLE_TEXT, row!!.entryText)
        assertNull(row.followUpText)
        assertEquals(Persona.WITNESS, row.persona)
        assertEquals(SAMPLE_INSTANT.toEpochMilli(), row.timestampEpochMs)
        assertEquals(ExtractionStatus.PENDING, row.extractionStatus)
        assertEquals(0, row.attemptCount)
        assertNull(row.lastError)
        assertTrue(
            "markdownFilename should follow the {iso}--{slug}.md contract: ${row.markdownFilename}",
            row.markdownFilename.matches(Regex("\\d{4}-\\d{2}-\\d{2}T[\\d-]+Z--[a-z0-9-]+\\.md")),
        )

        val mdFile = File(File(markdownDir, "entries"), row.markdownFilename)
        assertTrue("markdown file should exist at ${mdFile.absolutePath}", mdFile.isFile)
        assertTrue(mdFile.readText().endsWith("$SAMPLE_TEXT\n"))
    }

    @Test
    fun `createPendingEntry rejects blank entryText`() {
        assertThrows(IllegalArgumentException::class.java) {
            entryStore.createPendingEntry("   ", SAMPLE_INSTANT)
        }
    }

    @Test
    fun `createPendingEntry stores durationMs and it round-trips through markdown`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT, durationMs = 242_000L)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        assertEquals(242_000L, row.durationMs)

        val mdFile = File(File(markdownDir, "entries"), row.markdownFilename)
        assertTrue(
            "markdown must contain duration_ms: 242000",
            mdFile.readText().contains("duration_ms: 242000"),
        )
    }

    @Test
    fun `createPendingEntry persists followUpText and persona for saved single-turn transcript`() {
        val id = entryStore.createPendingEntry(
            entryText = SAMPLE_TEXT,
            timestamp = SAMPLE_INSTANT,
            followUpText = "What happened right after the crash?",
            persona = Persona.EDITOR,
        )

        val row = boxStore.boxFor<EntryEntity>().get(id)
        assertEquals("What happened right after the crash?", row.followUpText)
        assertEquals(Persona.EDITOR, row.persona)

        val mdFile = File(File(markdownDir, "entries"), row.markdownFilename)
        val markdown = mdFile.readText()
        assertTrue(markdown.contains("persona: editor"))
        assertTrue(markdown.contains("follow_up: What happened right after the crash?"))
    }

    @Test
    fun `completeEntry rewrites markdown with full frontmatter and updates row to COMPLETED`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        val resolved = resolvedSample()

        entryStore.completeEntry(id, resolved, TemplateLabel.AFTERMATH)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        assertEquals(ExtractionStatus.COMPLETED, row.extractionStatus)
        assertEquals(TemplateLabel.AFTERMATH, row.templateLabel)
        assertEquals("crashed", row.energyDescriptor)
        assertEquals("a3f9c2b8d4e7f1a2", row.recurrenceLink)
        assertNull(row.lastError)

        val tagNames = row.tags.map { it.name }.sorted()
        assertEquals(listOf("flattened", "standup", "tuesday-meeting"), tagNames)

        val confidence = JSONObject(row.confidenceJson)
        assertEquals(ConfidenceVerdict.CANONICAL.name, confidence.getString("tags"))
        assertEquals(ConfidenceVerdict.CANONICAL.name, confidence.getString("energy_descriptor"))

        val commitment = JSONObject(row.statedCommitmentJson!!)
        assertEquals("review the doc by Friday", commitment.getString("text"))

        val mdFile = File(File(markdownDir, "entries"), row.markdownFilename)
        val md = mdFile.readText()
        assertTrue("frontmatter should include template_label", md.contains("template_label: aftermath"))
        assertTrue("frontmatter should include tags", md.contains("  - tuesday-meeting"))
    }

    @Test
    fun `completeEntry rejects when entry row does not exist`() {
        val resolved = ResolvedExtraction(emptyMap())
        assertThrows(EntryPersistenceException::class.java) {
            entryStore.completeEntry(9_999L, resolved, TemplateLabel.AUDIT)
        }
    }

    @Test
    fun `completeEntry maintains TagEntity entryCount across reads`() {
        val id1 = entryStore.createPendingEntry("first capture", SAMPLE_INSTANT)
        val id2 = entryStore.createPendingEntry("second capture", SAMPLE_INSTANT.plusSeconds(120))
        val resolved = resolvedSample()

        entryStore.completeEntry(id1, resolved, TemplateLabel.AFTERMATH)
        entryStore.completeEntry(id2, resolved, TemplateLabel.AFTERMATH)

        val tagBox = boxStore.boxFor<TagEntity>()
        val standup = tagBox.all.firstOrNull { it.name == "standup" }
        assertNotNull(standup)
        assertEquals(2, standup!!.entryCount)
    }

    @Test
    fun `failEntry sets terminal status and lastError without touching attemptCount`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)

        entryStore.failEntry(id, ExtractionStatus.FAILED, "lens-parse-fail")

        val row = boxStore.boxFor<EntryEntity>().get(id)
        assertEquals(ExtractionStatus.FAILED, row.extractionStatus)
        assertEquals("lens-parse-fail", row.lastError)
        assertEquals(0, row.attemptCount)
    }

    @Test
    fun `failEntry rejects non-terminal status`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        assertThrows(IllegalArgumentException::class.java) {
            entryStore.failEntry(id, ExtractionStatus.RUNNING, "still working")
        }
    }

    @Test
    fun `mostRecentNonTerminalEntryId returns newest pending or running row`() {
        val first = entryStore.createPendingEntry("first", SAMPLE_INSTANT)
        val second = entryStore.createPendingEntry("second", SAMPLE_INSTANT.plusSeconds(60))
        entryStore.failEntry(first, ExtractionStatus.FAILED, "done failing")

        assertEquals(second, entryStore.mostRecentNonTerminalEntryId())
    }

    @Test
    fun `createPendingEntry rolls back row when markdown write fails`() {
        // Make the entries directory a regular file so mkdirs() fails inside MarkdownEntryStore.
        val entriesPath = File(markdownDir, "entries")
        markdownDir.mkdirs()
        entriesPath.writeText("not-a-directory")

        val countBefore = boxStore.boxFor<EntryEntity>().count()
        val failure = assertThrows(EntryPersistenceException::class.java) {
            entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        }
        assertNotNull(failure.cause)

        val countAfter = boxStore.boxFor<EntryEntity>().count()
        assertEquals(countBefore, countAfter)
    }

    @Test
    fun `completeEntry persists confidence verdicts for every resolved field`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        val mixed = ResolvedExtraction(
            mapOf(
                "tags" to ResolvedField(listOf("focus"), ConfidenceVerdict.CANONICAL),
                "energy_descriptor" to ResolvedField(null, ConfidenceVerdict.AMBIGUOUS),
                "stated_commitment" to ResolvedField(null, ConfidenceVerdict.CANONICAL),
                "recurrence_link" to ResolvedField(
                    "abc",
                    ConfidenceVerdict.CANDIDATE,
                    sourceLens = Lens.INFERENTIAL,
                ),
            ),
        )

        entryStore.completeEntry(id, mixed, TemplateLabel.TUNNEL_EXIT)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        val confidence = JSONObject(row.confidenceJson)
        assertEquals(ConfidenceVerdict.CANONICAL.name, confidence.getString("tags"))
        assertEquals(ConfidenceVerdict.AMBIGUOUS.name, confidence.getString("energy_descriptor"))
        assertEquals(ConfidenceVerdict.CANDIDATE.name, confidence.getString("recurrence_link"))
        assertNull(row.energyDescriptor)
        // CANDIDATE tag values are still persisted as the row's recurrenceLink scalar — the
        // pattern engine consults the verdict map before promoting them.
        assertEquals("abc", row.recurrenceLink)
    }

    @Test
    fun `completeEntry skips AMBIGUOUS tags from the ToMany link`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        val resolved = ResolvedExtraction(
            mapOf(
                "tags" to ResolvedField(listOf("rejected-tag"), ConfidenceVerdict.AMBIGUOUS),
            ),
        )

        entryStore.completeEntry(id, resolved, TemplateLabel.AUDIT)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        assertTrue(row.tags.isEmpty())
        val tagBox = boxStore.boxFor<TagEntity>()
        assertFalse(tagBox.all.any { it.name == "rejected-tag" })
    }

    @Test
    fun `entryObservationsJson defaults to empty array when resolver omits observations`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        entryStore.completeEntry(id, resolvedSample(), TemplateLabel.AFTERMATH)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        val observations = JSONArray(row.entryObservationsJson)
        assertEquals(0, observations.length())
    }

    @Test
    fun `completeEntry serializes provided observations into entryObservationsJson`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        val observations = listOf(
            EntryObservation(
                text = "You said \"fine\" and \"flattened\" in the same entry.",
                evidence = ObservationEvidence.VOCABULARY_CONTRADICTION,
                fields = listOf("vocabulary_contradictions"),
            ),
            EntryObservation(
                text = "You said you'd talk to her — flagged.",
                evidence = ObservationEvidence.COMMITMENT_FLAG,
                fields = listOf("stated_commitment"),
            ),
        )

        entryStore.completeEntry(id, resolvedSample(), TemplateLabel.AFTERMATH, observations)

        val row = boxStore.boxFor<EntryEntity>().get(id)
        val array = JSONArray(row.entryObservationsJson)
        assertEquals(2, array.length())
        val first = array.getJSONObject(0)
        assertEquals(observations[0].text, first.getString("text"))
        assertEquals("vocabulary-contradiction", first.getString("evidence"))
        assertEquals("vocabulary_contradictions", first.getJSONArray("fields").getString(0))
        val second = array.getJSONObject(1)
        assertEquals("commitment-flag", second.getString("evidence"))
    }

    @Test
    fun `appendObservation aborts when existing observations JSON is malformed`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        entryStore.completeEntry(id, resolvedSample(), TemplateLabel.AFTERMATH)
        // Simulate upstream corruption — overwrite the field with malformed JSON. A subsequent
        // append must refuse, not silently overwrite real persisted state with `[newObs]`.
        val box = boxStore.boxFor<EntryEntity>()
        val row = box.get(id)
        row.entryObservationsJson = "{not-json"
        box.put(row)

        val callout = EntryObservation(
            text = "Worth noting.",
            evidence = ObservationEvidence.PATTERN_CALLOUT,
            fields = emptyList(),
        )
        val raised = runCatching { entryStore.appendObservation(id, callout) }
        assertTrue(
            "appendObservation must reject malformed existing JSON",
            raised.exceptionOrNull() is EntryPersistenceException,
        )
        // Row stays corrupt — but we did not destroy whatever observations were there.
        assertEquals("{not-json", box.get(id).entryObservationsJson)
    }

    @Test
    fun `appendObservation succeeds on empty array (legit empty)`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        entryStore.completeEntry(id, resolvedSample(), TemplateLabel.AFTERMATH)
        val callout = EntryObservation(
            text = "Worth noting.",
            evidence = ObservationEvidence.PATTERN_CALLOUT,
            fields = emptyList(),
        )
        entryStore.appendObservation(id, callout)
        val row = boxStore.boxFor<EntryEntity>().get(id)
        val observations = JSONArray(row.entryObservationsJson)
        assertEquals(1, observations.length())
        assertEquals("Worth noting.", observations.getJSONObject(0).getString("text"))
    }

    @Test
    fun `appendObservation appends to non-empty existing observations`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        val first = EntryObservation(
            text = "You used fine twice.",
            evidence = ObservationEvidence.VOCABULARY_CONTRADICTION,
            fields = listOf("fine"),
        )
        entryStore.completeEntry(id, resolvedSample(), null, listOf(first))

        val second = EntryObservation(
            text = "Committed to review by Friday.",
            evidence = ObservationEvidence.COMMITMENT_FLAG,
            fields = emptyList(),
        )
        entryStore.appendObservation(id, second)

        val arr = JSONArray(boxStore.boxFor<EntryEntity>().get(id).entryObservationsJson)
        assertEquals(2, arr.length())
        assertEquals("You used fine twice.", arr.getJSONObject(0).getString("text"))
        assertEquals("Committed to review by Friday.", arr.getJSONObject(1).getString("text"))
    }

    @Test
    fun `markdownFilename remains stable across complete after create`() {
        val id = entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        val firstName = boxStore.boxFor<EntryEntity>().get(id).markdownFilename

        entryStore.completeEntry(id, resolvedSample(), TemplateLabel.AFTERMATH)
        val secondName = boxStore.boxFor<EntryEntity>().get(id).markdownFilename

        assertEquals(firstName, secondName)
        assertNotEquals("", firstName)
    }

    private fun resolvedSample() = ResolvedExtraction(
        mapOf(
            "tags" to ResolvedField(
                listOf("tuesday-meeting", "standup", "flattened"),
                ConfidenceVerdict.CANONICAL,
            ),
            "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
            "recurrence_link" to ResolvedField("a3f9c2b8d4e7f1a2", ConfidenceVerdict.CANONICAL),
            "stated_commitment" to ResolvedField(
                mapOf(
                    "text" to "review the doc by Friday",
                    "topic_or_person" to "Nora",
                    "entry_id" to null,
                ),
                ConfidenceVerdict.CANONICAL,
            ),
        ),
    )

    // listCompleted tests

    @Test
    fun `listCompleted returns only COMPLETED rows in newest-first order`() {
        val id1 = entryStore.createPendingEntry("oldest", SAMPLE_INSTANT)
        val id2 = entryStore.createPendingEntry("newest", SAMPLE_INSTANT.plusSeconds(60))
        entryStore.createPendingEntry("pending", SAMPLE_INSTANT.plusSeconds(120))
        entryStore.completeEntry(id1, emptyResolved, templateLabel = null)
        entryStore.completeEntry(id2, emptyResolved, templateLabel = null)

        val rows = entryStore.listCompleted()
        assertEquals(2, rows.size)
        assertEquals("newest", rows[0].entryText)
        assertEquals("oldest", rows[1].entryText)
    }

    @Test
    fun `listCompleted returns empty list when no completed entries`() {
        entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        assertTrue(entryStore.listCompleted().isEmpty())
    }

    @Test
    fun `listCompleted respects the limit parameter`() {
        repeat(5) { i ->
            val id = entryStore.createPendingEntry("entry $i", SAMPLE_INSTANT.plusSeconds(i.toLong()))
            entryStore.completeEntry(id, emptyResolved, templateLabel = null)
        }
        val rows = entryStore.listCompleted(limit = 3)
        assertEquals(3, rows.size)
    }

    // lastCompleted tests

    @Test
    fun `lastCompleted returns the most recent completed entry`() {
        val id1 = entryStore.createPendingEntry("older", SAMPLE_INSTANT)
        val id2 = entryStore.createPendingEntry("newer", SAMPLE_INSTANT.plusSeconds(60))
        entryStore.completeEntry(id1, emptyResolved, templateLabel = null)
        entryStore.completeEntry(id2, emptyResolved, templateLabel = null)

        val last = entryStore.lastCompleted()
        assertNotNull(last)
        assertEquals("newer", last!!.entryText)
    }

    @Test
    fun `lastCompleted returns null when no completed entries`() {
        entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT)
        assertNull(entryStore.lastCompleted())
    }

    @Test
    fun `lastCompleted ignores PENDING entries`() {
        entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_INSTANT.plusSeconds(120))
        val id = entryStore.createPendingEntry("completed", SAMPLE_INSTANT)
        entryStore.completeEntry(id, emptyResolved, templateLabel = null)

        val last = entryStore.lastCompleted()
        assertNotNull(last)
        assertEquals("completed", last!!.entryText)
    }

    private val emptyResolved = ResolvedExtraction(emptyMap())

    private companion object {
        // 2026-05-11T07:21:24Z — fixed, arbitrary fixture instant; value is not significant.
        private val SAMPLE_INSTANT: Instant = Instant.ofEpochSecond(1_778_829_684L)
        private const val SAMPLE_TEXT =
            "Standup ran long. Fine before, then completely flattened by eleven."
    }
}
