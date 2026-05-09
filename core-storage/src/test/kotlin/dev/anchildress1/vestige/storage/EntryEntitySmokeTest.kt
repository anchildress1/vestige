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
    fun `Pattern entity persists lifecycle state and observation timestamp`() {
        val patternBox = boxStore.boxFor<PatternEntity>()
        val pattern = PatternEntity(
            stableId = "aftermath-post-meeting",
            type = "aftermath-post-meeting",
            entryCount = 4,
            lifecycleState = "ACTIVE",
            lastObservedEpochMs = 1_715_252_335_000L,
        )
        val newId = patternBox.put(pattern)
        val readBack = patternBox.get(newId)

        assertEquals("aftermath-post-meeting", readBack.stableId)
        assertEquals(4, readBack.entryCount)
        assertEquals("ACTIVE", readBack.lifecycleState)
        assertEquals(1_715_252_335_000L, readBack.lastObservedEpochMs)
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
