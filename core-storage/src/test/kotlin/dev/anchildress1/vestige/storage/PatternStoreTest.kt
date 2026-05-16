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
import org.junit.Assert.assertThrows
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
class PatternStoreTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataDir: File
    private lateinit var store: PatternStore
    private val now: Instant = Instant.parse("2026-05-11T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        dataDir = newInMemoryObjectBoxDirectory("objectbox-pattern-")
        boxStore = openInMemoryBoxStore(dataDir)
        store = PatternStore(boxStore, clock)
    }

    @After
    fun tearDown() {
        boxStore.close()
        BoxStore.deleteAllFiles(dataDir)
    }

    private fun seed(state: PatternState = PatternState.ACTIVE, patternId: String = "p".repeat(64)): PatternEntity {
        val entity = PatternEntity(
            patternId = patternId,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{}",
            title = "Aftermath Loop",
            firstSeenTimestamp = now.toEpochMilli() - 1_000_000,
            lastSeenTimestamp = now.toEpochMilli(),
            state = state,
        )
        store.put(entity)
        return entity
    }

    @Test
    fun `active to dropped is legal`() {
        val seeded = seed()
        val updated = store.transitionState(seeded.patternId, PatternState.DROPPED)
        assertEquals(PatternState.DROPPED, updated.state)
        assertEquals(now.toEpochMilli(), updated.stateChangedTimestamp)
    }

    @Test
    fun `active to snoozed requires future snoozedUntil`() {
        val seeded = seed()
        val until = now.toEpochMilli() + 7L * 24 * 60 * 60 * 1000
        val updated = store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = until)
        assertEquals(PatternState.SNOOZED, updated.state)
        assertEquals(until, updated.snoozedUntil)
    }

    @Test
    fun `snoozed to dropped clears snoozedUntil`() {
        val seeded = seed()
        store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        val updated = store.transitionState(seeded.patternId, PatternState.DROPPED)
        assertEquals(PatternState.DROPPED, updated.state)
        assertNull(updated.snoozedUntil)
    }

    @Test
    fun `snoozed to active clears snoozedUntil`() {
        val seeded = seed()
        store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        val updated = store.transitionState(seeded.patternId, PatternState.ACTIVE)
        assertEquals(PatternState.ACTIVE, updated.state)
        assertNull(updated.snoozedUntil)
    }

    @Test
    fun `below_threshold to active is legal`() {
        val seeded = seed(state = PatternState.BELOW_THRESHOLD)
        val updated = store.transitionState(seeded.patternId, PatternState.ACTIVE)
        assertEquals(PatternState.ACTIVE, updated.state)
    }

    @Test
    fun `dropped is terminal — any transition out throws`() {
        val seeded = seed(state = PatternState.DROPPED)
        for (target in PatternState.entries.filter { it != PatternState.DROPPED }) {
            assertThrows(IllegalStateException::class.java) {
                if (target == PatternState.SNOOZED) {
                    store.transitionState(seeded.patternId, target, snoozedUntilMs = now.toEpochMilli() + 1_000)
                } else {
                    store.transitionState(seeded.patternId, target)
                }
            }
        }
    }

    @Test
    fun `closed is terminal — any transition out throws`() {
        val seeded = seed(state = PatternState.CLOSED)
        for (target in PatternState.entries.filter { it != PatternState.CLOSED }) {
            assertThrows(IllegalStateException::class.java) {
                if (target == PatternState.SNOOZED) {
                    store.transitionState(seeded.patternId, target, snoozedUntilMs = now.toEpochMilli() + 1_000)
                } else {
                    store.transitionState(seeded.patternId, target)
                }
            }
        }
    }

    @Test
    fun `snooze with past snoozedUntil is rejected`() {
        val seeded = seed()
        assertThrows(IllegalArgumentException::class.java) {
            store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() - 1)
        }
    }

    @Test
    fun `missing patternId raises explicit error`() {
        val raised = assertThrows(IllegalStateException::class.java) {
            store.transitionState(patternId = "missing", target = PatternState.DROPPED)
        }
        assertNotNull(raised.message)
    }

    @Test
    fun `illegal transitions matrix — every rejected pair throws`() {
        // ADR-003 §"Lifecycle & state transitions" — enumerate every state pair and assert
        // the validator rejects exactly the pairs the ADR forbids. Legal pairs land in their
        // dedicated tests above; this one is the negative matrix.
        val illegalFromActive = setOf(PatternState.ACTIVE) // self-transitions not legal
        val illegalFromSnoozed = setOf(PatternState.SNOOZED, PatternState.BELOW_THRESHOLD, PatternState.CLOSED)
        val illegalFromBelowThreshold = PatternState.entries.toSet() -
            setOf(PatternState.ACTIVE, PatternState.BELOW_THRESHOLD)

        assertAllRejected(PatternState.ACTIVE, illegalFromActive)
        assertAllRejected(PatternState.SNOOZED, illegalFromSnoozed)
        assertAllRejected(PatternState.BELOW_THRESHOLD, illegalFromBelowThreshold)
    }

    private fun assertAllRejected(from: PatternState, illegalTargets: Set<PatternState>) {
        for (target in illegalTargets) {
            // Fresh row per assertion — the validator mutates state on success, and a prior
            // successful transition inside the same row would let an "illegal" follow-up pass.
            val pid = "${from.serial}-${target.serial}".padEnd(64, 'x')
            seed(state = from, patternId = pid)
            assertThrows(
                "Illegal transition $from->$target must throw",
                Throwable::class.java,
            ) {
                if (target == PatternState.SNOOZED) {
                    store.transitionState(pid, target, snoozedUntilMs = now.toEpochMilli() + 1_000)
                } else {
                    store.transitionState(pid, target)
                }
            }
        }
    }

    @Test
    fun `findVisibleSortedByLastSeen surfaces all four user-visible states newest-first`() {
        val base = now.toEpochMilli()
        seed(state = PatternState.ACTIVE, patternId = "a".repeat(64)).also {
            it.lastSeenTimestamp = base - 400
            store.put(it)
        }
        seed(state = PatternState.SNOOZED, patternId = "b".repeat(64)).also {
            it.lastSeenTimestamp = base - 300
            it.snoozedUntil = base + 86_400_000
            store.put(it)
        }
        seed(state = PatternState.CLOSED, patternId = "c".repeat(64)).also {
            it.lastSeenTimestamp = base - 200
            store.put(it)
        }
        seed(state = PatternState.DROPPED, patternId = "d".repeat(64)).also {
            it.lastSeenTimestamp = base - 100
            store.put(it)
        }
        // Hidden internal state — must not appear in the visible result set.
        seed(state = PatternState.BELOW_THRESHOLD, patternId = "e".repeat(64)).also {
            it.lastSeenTimestamp = base - 50
            store.put(it)
        }

        val visible = store.findVisibleSortedByLastSeen()
        val ids = visible.map { it.patternId }
        assertEquals(
            listOf("d".repeat(64), "c".repeat(64), "b".repeat(64), "a".repeat(64)),
            ids,
        )
    }

    @Test
    fun `findVisibleSortedByLastSeen returns empty when the table only holds BELOW_THRESHOLD rows`() {
        seed(state = PatternState.BELOW_THRESHOLD, patternId = "z".repeat(64))
        assertEquals(emptyList<PatternEntity>(), store.findVisibleSortedByLastSeen())
    }

    @Test
    fun `findVisibleSortedByLastSeen returns empty on an empty table`() {
        assertEquals(emptyList<PatternEntity>(), store.findVisibleSortedByLastSeen())
    }

    @Test
    fun `promoteExpiredSkips promotes a past-window snoozed row to active and clears snoozedUntil`() {
        val id = "exp".padEnd(64, 'x')
        seed(state = PatternState.ACTIVE, patternId = id)
        store.transitionState(id, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        // Rewind the window so it sits in the past relative to the fixed clock.
        store.findByPatternId(id)!!.also {
            it.snoozedUntil = now.toEpochMilli() - 1
            store.put(it)
        }

        val promoted = store.promoteExpiredSkips()

        assertEquals(listOf(id), promoted)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.ACTIVE, row.state)
        assertNull(row.snoozedUntil)
    }

    @Test
    fun `promoteExpiredSkips promotes a row whose snoozedUntil equals now (boundary, lte)`() {
        val id = "bnd".padEnd(64, 'x')
        seed(state = PatternState.ACTIVE, patternId = id)
        store.transitionState(id, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        store.findByPatternId(id)!!.also {
            it.snoozedUntil = now.toEpochMilli()
            store.put(it)
        }

        val promoted = store.promoteExpiredSkips()

        assertEquals(listOf(id), promoted)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `promoteExpiredSkips does not promote a snoozed row whose window is in the future`() {
        val id = "fut".padEnd(64, 'x')
        seed(state = PatternState.ACTIVE, patternId = id)
        store.transitionState(id, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 86_400_000)

        val promoted = store.promoteExpiredSkips()

        assertEquals(emptyList<String>(), promoted)
        val row = store.findByPatternId(id)!!
        assertEquals(PatternState.SNOOZED, row.state)
        assertEquals(now.toEpochMilli() + 86_400_000, row.snoozedUntil)
    }

    @Test
    fun `promoteExpiredSkips ignores a snoozed row with a null snoozedUntil`() {
        // A SNOOZED row with no window can't be "expired" — leave it untouched.
        val id = "nul".padEnd(64, 'x')
        seed(state = PatternState.SNOOZED, patternId = id).also {
            it.snoozedUntil = null
            store.put(it)
        }

        val promoted = store.promoteExpiredSkips()

        assertEquals(emptyList<String>(), promoted)
        assertEquals(PatternState.SNOOZED, store.findByPatternId(id)!!.state)
    }

    @Test
    fun `promoteExpiredSkips is a no-op when nothing is expired`() {
        seed(state = PatternState.ACTIVE, patternId = "a".repeat(64))

        assertEquals(emptyList<String>(), store.promoteExpiredSkips())
    }

    @Test
    fun `promoteExpiredSkips only touches snoozed rows and leaves active dropped closed alone`() {
        seed(state = PatternState.ACTIVE, patternId = "act".padEnd(64, 'x'))
        seed(state = PatternState.DROPPED, patternId = "drp".padEnd(64, 'x'))
        seed(state = PatternState.CLOSED, patternId = "cls".padEnd(64, 'x'))
        val snoozedId = "snz".padEnd(64, 'x')
        seed(state = PatternState.ACTIVE, patternId = snoozedId)
        store.transitionState(snoozedId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        store.findByPatternId(snoozedId)!!.also {
            it.snoozedUntil = now.toEpochMilli() - 1
            store.put(it)
        }

        val promoted = store.promoteExpiredSkips()

        assertEquals(listOf(snoozedId), promoted)
        assertEquals(PatternState.ACTIVE, store.findByPatternId("act".padEnd(64, 'x'))!!.state)
        assertEquals(PatternState.DROPPED, store.findByPatternId("drp".padEnd(64, 'x'))!!.state)
        assertEquals(PatternState.CLOSED, store.findByPatternId("cls".padEnd(64, 'x'))!!.state)
    }

    @Test
    fun `findSnoozed returns only snoozed rows`() {
        seed(state = PatternState.ACTIVE, patternId = "a".repeat(64))
        val snoozedId = "s".repeat(64)
        seed(state = PatternState.ACTIVE, patternId = snoozedId)
        store.transitionState(snoozedId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)

        val snoozed = store.findSnoozed()

        assertEquals(listOf(snoozedId), snoozed.map { it.patternId })
    }

    @Test
    fun `promoteExpiredSkips promotes all expired rows without stranding the cohort`() {
        // Verifies the per-row resilience invariant documented in promoteExpiredSkips: if one row
        // fails its transition (concurrent writer), the remaining expired rows still promote.
        // This positive case seeds two expired + one future row and asserts all three are handled.
        val expiredId1 = "ex1".padEnd(64, 'x')
        val expiredId2 = "ex2".padEnd(64, 'x')
        val futureId = "fu2".padEnd(64, 'x')

        for (id in listOf(expiredId1, expiredId2, futureId)) {
            seed(state = PatternState.ACTIVE, patternId = id)
            store.transitionState(id, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        }
        for (id in listOf(expiredId1, expiredId2)) {
            store.findByPatternId(id)!!.also {
                it.snoozedUntil = now.toEpochMilli() - 1
                store.put(it)
            }
        }

        val promoted = store.promoteExpiredSkips()

        assertEquals(setOf(expiredId1, expiredId2), promoted.toSet())
        assertEquals(PatternState.ACTIVE, store.findByPatternId(expiredId1)!!.state)
        assertEquals(PatternState.ACTIVE, store.findByPatternId(expiredId2)!!.state)
        assertEquals(PatternState.SNOOZED, store.findByPatternId(futureId)!!.state)
    }

    @Test
    fun `findSnoozed returns empty when no rows are snoozed`() {
        seed(state = PatternState.ACTIVE, patternId = "a".repeat(64))

        assertEquals(emptyList<PatternEntity>(), store.findSnoozed())
    }

    @Test
    fun `state survives BoxStore close and reopen by name`() {
        val seeded = seed()
        store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        boxStore.close()
        // In-memory stores are keyed by their `memory:` URI — reopening with the same path
        // reattaches to the same registry. Disk durability lives in VestigeBoxStoreOpenTest.
        boxStore = openInMemoryBoxStore(dataDir)
        store = PatternStore(boxStore, clock)
        val readBack = store.findByPatternId(seeded.patternId)!!
        assertEquals(PatternState.SNOOZED, readBack.state)
    }
}
