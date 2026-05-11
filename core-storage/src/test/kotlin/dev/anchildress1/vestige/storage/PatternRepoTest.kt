package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PatternRepoTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var store: PatternStore
    private lateinit var repo: PatternRepo
    private val now: Instant = Instant.parse("2026-05-11T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-patternrepo-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
        store = PatternStore(boxStore, clock)
        repo = PatternRepo(store, clock)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    private fun seed(patternId: String = "p".repeat(64)): String {
        store.put(
            PatternEntity(
                patternId = patternId,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{}",
                title = "Aftermath Loop",
                firstSeenTimestamp = now.toEpochMilli() - 1_000,
                lastSeenTimestamp = now.toEpochMilli(),
                state = PatternState.ACTIVE,
            ),
        )
        return patternId
    }

    @Test
    fun `dismiss transitions active to dismissed`() {
        val id = seed()
        repo.dismiss(id)
        assertEquals(PatternState.DISMISSED, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `dismiss with undo returns dismissed pattern to active`() {
        val id = seed()
        repo.dismiss(id)
        repo.dismiss(id, undo = true)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `snooze default sets 7-day window`() {
        val id = seed()
        repo.snooze(id)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.SNOOZED, row.state)
        val expected = now.toEpochMilli() + 7L * 24 * 60 * 60 * 1000
        assertEquals(expected, row.snoozedUntil)
    }

    @Test
    fun `snooze with custom days honors the override`() {
        val id = seed()
        repo.snooze(id, days = 3)
        val expected = now.toEpochMilli() + 3L * 24 * 60 * 60 * 1000
        assertEquals(expected, store.findByPatternId(id)!!.snoozedUntil)
    }

    @Test
    fun `snooze undo clears snoozedUntil and returns to active`() {
        val id = seed()
        repo.snooze(id)
        repo.snooze(id, undo = true)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.ACTIVE, row.state)
        assertNull(row.snoozedUntil)
    }

    @Test
    fun `markResolved transitions active to resolved`() {
        val id = seed()
        repo.markResolved(id)
        assertEquals(PatternState.RESOLVED, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `markResolved with undo returns to active`() {
        val id = seed()
        repo.markResolved(id)
        repo.markResolved(id, undo = true)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `actions survive BoxStore restart`() {
        val id = seed()
        repo.snooze(id, days = 1)
        boxStore.close()
        boxStore = VestigeBoxStore.openAt(dataDir)
        store = PatternStore(boxStore, clock)
        repo = PatternRepo(store, clock)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.SNOOZED, row.state)
        assertNotNull(row.snoozedUntil)
    }

    @Test
    fun `dismiss followed by markResolved without undo is rejected — DISMISSED is terminal`() {
        val id = seed()
        repo.dismiss(id)
        val raised = runCatching { repo.markResolved(id) }
        assertTrue("DISMISSED→RESOLVED must throw", raised.isFailure)
    }
}
