package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
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
        dataDir = newInMemoryObjectBoxDirectory("vestige-pattern-repo-")
        boxStore = openInMemoryBoxStore(dataDir)
        store = PatternStore(boxStore, clock)
        repo = PatternRepo(store, clock)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    private fun seed(patternId: String = "p".repeat(64), state: PatternState = PatternState.ACTIVE): String {
        store.put(
            PatternEntity(
                patternId = patternId,
                kind = PatternKind.TEMPLATE_RECURRENCE,
                signatureJson = "{}",
                title = "Aftermath Loop",
                firstSeenTimestamp = now.toEpochMilli() - 1_000,
                lastSeenTimestamp = now.toEpochMilli(),
                state = state,
            ),
        )
        return patternId
    }

    @Test
    fun `drop transitions active to dropped`() {
        val id = seed()
        repo.drop(id)
        assertEquals(PatternState.DROPPED, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `drop with undo returns dropped pattern to active`() {
        val id = seed()
        repo.drop(id)
        repo.drop(id, undo = true)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `drop undo rejects rows that are not currently DROPPED`() {
        // Misrouted undo callback on a CLOSED pattern must NOT reopen it. ADR-003 Addendum
        // 2026-05-13b — a stale snackbar from a previous action shouldn't bypass the
        // model-detected terminal.
        val id = seed(state = PatternState.CLOSED)
        val raised = runCatching { repo.drop(id, undo = true) }
        assertTrue("drop undo on CLOSED must throw", raised.exceptionOrNull() is IllegalArgumentException)
        assertEquals(PatternState.CLOSED, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `skip default sets 7-day window`() {
        val id = seed()
        repo.skip(id)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.SNOOZED, row.state)
        val expected = now.toEpochMilli() + 7L * 24 * 60 * 60 * 1000
        assertEquals(expected, row.snoozedUntil)
    }

    @Test
    fun `skip with custom days honors the override`() {
        val id = seed()
        repo.skip(id, days = 3)
        val expected = now.toEpochMilli() + 3L * 24 * 60 * 60 * 1000
        assertEquals(expected, store.findByPatternId(id)!!.snoozedUntil)
    }

    @Test
    fun `skip undo rejects rows that are not currently SNOOZED`() {
        val id = seed()
        // Pattern is ACTIVE; skip-undo must not "un-skip" an active row (no-op-via-error).
        val raised = runCatching { repo.skip(id, undo = true) }
        assertTrue("skip undo on ACTIVE must throw", raised.exceptionOrNull() is IllegalArgumentException)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `skip undo clears snoozedUntil and returns to active`() {
        val id = seed()
        repo.skip(id)
        repo.skip(id, undo = true)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.ACTIVE, row.state)
        assertNull(row.snoozedUntil)
    }

    @Test
    fun `actions survive BoxStore close and reopen by name`() {
        val id = seed()
        repo.skip(id, days = 1)
        boxStore.close()
        // ObjectBox keys in-memory stores by their `memory:` URI; reopening with the same path
        // reattaches to the same backing registry. That's the invariant under test — process-
        // local close/reopen idempotency, not on-disk durability.
        boxStore = openInMemoryBoxStore(dataDir)
        store = PatternStore(boxStore, clock)
        repo = PatternRepo(store, clock)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.SNOOZED, row.state)
        assertNotNull(row.snoozedUntil)
    }

    @Test
    fun `drop followed by a second drop without undo is rejected — DROPPED is terminal`() {
        val id = seed()
        repo.drop(id)
        // DROPPED is terminal to the strict validator; a non-undo re-drop self-loops illegally.
        val raised = runCatching { repo.drop(id) }
        assertTrue("DROPPED→DROPPED must throw", raised.isFailure)
    }

    @Test
    fun `restart from DROPPED returns the pattern to ACTIVE`() {
        val id = seed()
        repo.drop(id)
        repo.restart(id)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.ACTIVE, row.state)
        assertNull(row.snoozedUntil)
    }

    @Test
    fun `restart from SNOOZED returns the pattern to ACTIVE and clears snoozedUntil`() {
        val id = seed()
        repo.skip(id)
        assertNotNull(store.findByPatternId(id)!!.snoozedUntil)
        repo.restart(id)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.ACTIVE, row.state)
        assertNull(row.snoozedUntil)
    }

    @Test
    fun `restart from CLOSED returns the pattern to ACTIVE`() {
        // CLOSED is model-detected only — seed it directly since no user action produces it.
        val id = seed(state = PatternState.CLOSED)
        repo.restart(id)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `restart rejects ACTIVE rows — restart is terminal-only`() {
        val id = seed()
        val raised = runCatching { repo.restart(id) }
        assertTrue("ACTIVE → restart must throw", raised.isFailure)
    }

    @Test
    fun `restart undo returns the row to the previousState terminal`() {
        val id = seed()
        repo.drop(id)
        repo.restart(id)
        repo.restart(id, undo = true, previousState = PatternState.DROPPED)
        assertEquals(PatternState.DROPPED, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `restart undo restores the original snoozedUntil when returning to SNOOZED`() {
        val id = seed()
        repo.skip(id, days = 3)
        val originalSnoozedUntil = store.findByPatternId(id)!!.snoozedUntil
        assertNotNull(originalSnoozedUntil)

        repo.restart(id)
        repo.restart(
            id,
            undo = true,
            previousState = PatternState.SNOOZED,
            previousSnoozedUntil = originalSnoozedUntil,
        )

        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.SNOOZED, row.state)
        assertEquals(originalSnoozedUntil, row.snoozedUntil)
    }

    @Test
    fun `restart undo rejects rows that are not currently ACTIVE`() {
        val id = seed()
        repo.drop(id)
        // Row is DROPPED — undo path expects ACTIVE and must reject.
        val raised = runCatching {
            repo.restart(id, undo = true, previousState = PatternState.DROPPED)
        }
        assertTrue("undo precondition must reject non-ACTIVE rows", raised.isFailure)
    }
}
