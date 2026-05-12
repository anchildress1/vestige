package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Story 1.6 — write/read smoke test for the ObjectBox schema. Uses Robolectric's Application
 * context so the test runs on the JVM but exercises the same `MyObjectBox` Kapt-generated code
 * that ships in the APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EntryEntitySmokeTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-test-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    @Test
    fun `empty Entry round-trips with default operational fields`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val entry = EntryEntity()
        val newId = entryBox.put(entry)

        assertNotEquals(0L, newId)

        val readBack = entryBox.get(newId)
        assertNotNull(readBack)
        assertEquals(newId, readBack.id)
        assertEquals("", readBack.entryText)
        assertEquals(0L, readBack.timestampEpochMs)
        assertNull(readBack.templateLabel)
        assertNull(readBack.energyDescriptor)
        assertNull(readBack.recurrenceLink)
        assertNull(readBack.statedCommitmentJson)
        assertEquals("[]", readBack.entryObservationsJson)
        assertEquals("{}", readBack.confidenceJson)

        // Operational triplet defaults per ADR-001 §Q3
        assertEquals(ExtractionStatus.PENDING, readBack.extractionStatus)
        assertEquals(0, readBack.attemptCount)
        assertNull(readBack.lastError)
    }

    @Test
    fun `populated Entry persists template_label tags and operational fields`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val tagBox = boxStore.boxFor<TagEntity>()

        val tag = TagEntity(name = "standup", entryCount = 1)
        val tagId = tagBox.put(tag)

        val entry = EntryEntity(
            markdownFilename = "2026-05-09T10-32-15Z--standup-blast.md",
            entryText = "Standup ran long again.",
            timestampEpochMs = 1_715_252_335_000L,
            templateLabel = TemplateLabel.AFTERMATH,
            energyDescriptor = "flattened",
            extractionStatus = ExtractionStatus.RUNNING,
            attemptCount = 1,
            lastError = null,
        )
        entry.tags.add(tagBox[tagId])
        val entryId = entryBox.put(entry)

        val readBack = entryBox.get(entryId)
        assertEquals("2026-05-09T10-32-15Z--standup-blast.md", readBack.markdownFilename)
        assertEquals("Standup ran long again.", readBack.entryText)
        assertEquals(TemplateLabel.AFTERMATH, readBack.templateLabel)
        assertEquals(ExtractionStatus.RUNNING, readBack.extractionStatus)
        assertEquals(1, readBack.attemptCount)
        assertEquals(1, readBack.tags.size)
        assertEquals("standup", readBack.tags.first().name)
    }

    @Test
    fun `non-terminal recovery query returns only pending and running entry ids`() {
        val entryBox = boxStore.boxFor<EntryEntity>()
        val pendingId = entryBox.put(EntryEntity(extractionStatus = ExtractionStatus.PENDING))
        val runningId = entryBox.put(EntryEntity(extractionStatus = ExtractionStatus.RUNNING))
        entryBox.put(EntryEntity(extractionStatus = ExtractionStatus.COMPLETED))
        entryBox.put(EntryEntity(extractionStatus = ExtractionStatus.TIMED_OUT))
        entryBox.put(EntryEntity(extractionStatus = ExtractionStatus.FAILED))

        assertEquals(
            listOf(pendingId, runningId),
            VestigeBoxStore.findNonTerminalEntryIds(boxStore),
        )
    }

    @Test
    fun `Pattern entity round-trips ADR-003 fields and persists state via converter`() {
        val patternBox = boxStore.boxFor<PatternEntity>()
        val pattern = PatternEntity(
            patternId = "f".repeat(64),
            kind = dev.anchildress1.vestige.model.PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{\"kind\":\"template_recurrence\",\"key\":\"aftermath\"}",
            title = "Aftermath Loop",
            templateLabel = TemplateLabel.AFTERMATH.serial,
            firstSeenTimestamp = 1_715_000_000_000L,
            lastSeenTimestamp = 1_715_252_335_000L,
            state = dev.anchildress1.vestige.model.PatternState.ACTIVE,
            stateChangedTimestamp = 0L,
            latestCalloutText = "Fourth Aftermath in twelve. Worth noting.",
        )
        val newId = patternBox.put(pattern)
        val readBack = patternBox.get(newId)

        assertEquals("f".repeat(64), readBack.patternId)
        assertEquals(dev.anchildress1.vestige.model.PatternKind.TEMPLATE_RECURRENCE, readBack.kind)
        assertEquals(dev.anchildress1.vestige.model.PatternState.ACTIVE, readBack.state)
        assertEquals(TemplateLabel.AFTERMATH.serial, readBack.templateLabel)
        assertEquals("Aftermath Loop", readBack.title)
        assertNull(readBack.snoozedUntil)
    }

    @Test
    fun `Pattern patternId is unique`() {
        val patternBox = boxStore.boxFor<PatternEntity>()
        patternBox.put(
            PatternEntity(
                patternId = "a".repeat(64),
                signatureJson = "{}",
                title = "x",
                firstSeenTimestamp = 1L,
                lastSeenTimestamp = 1L,
            ),
        )
        val raised = runCatching {
            patternBox.put(
                PatternEntity(
                    patternId = "a".repeat(64),
                    signatureJson = "{}",
                    title = "y",
                    firstSeenTimestamp = 2L,
                    lastSeenTimestamp = 2L,
                ),
            )
        }
        assertFalse("Second insert with the same patternId must fail unique-index check", raised.isSuccess)
    }

    @Test
    fun `Tag uniqueness is enforced on the name index`() {
        val tagBox = boxStore.boxFor<TagEntity>()
        tagBox.put(TagEntity(name = "standup"))
        val raised = runCatching { tagBox.put(TagEntity(name = "standup")) }
        assertFalse(
            "Second insert of the same tag name must fail unique-index check",
            raised.isSuccess,
        )
    }
}
