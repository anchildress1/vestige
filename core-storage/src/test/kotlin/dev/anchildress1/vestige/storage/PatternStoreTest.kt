package dev.anchildress1.vestige.storage

import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dataDir = File(context.filesDir, "objectbox-pattern-${System.nanoTime()}")
        boxStore = VestigeBoxStore.openAt(dataDir)
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
    fun `active to dismissed is legal`() {
        val seeded = seed()
        val updated = store.transitionState(seeded.patternId, PatternState.DISMISSED)
        assertEquals(PatternState.DISMISSED, updated.state)
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
    fun `snoozed to dismissed clears snoozedUntil`() {
        val seeded = seed()
        store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        val updated = store.transitionState(seeded.patternId, PatternState.DISMISSED)
        assertEquals(PatternState.DISMISSED, updated.state)
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
    fun `dismissed is terminal — any transition out throws`() {
        val seeded = seed(state = PatternState.DISMISSED)
        for (target in PatternState.entries.filter { it != PatternState.DISMISSED }) {
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
    fun `resolved is terminal — any transition out throws`() {
        val seeded = seed(state = PatternState.RESOLVED)
        for (target in PatternState.entries.filter { it != PatternState.RESOLVED }) {
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
            store.transitionState(patternId = "missing", target = PatternState.DISMISSED)
        }
        assertNotNull(raised.message)
    }

    @Test
    fun `illegal transitions matrix — every rejected pair throws`() {
        // ADR-003 §"Lifecycle & state transitions" — enumerate every state pair and assert
        // the validator rejects exactly the pairs the ADR forbids. Legal pairs land in their
        // dedicated tests above; this one is the negative matrix.
        val illegalFromActive = setOf(PatternState.ACTIVE) // self-transitions not legal
        val illegalFromSnoozed = setOf(PatternState.SNOOZED, PatternState.BELOW_THRESHOLD, PatternState.RESOLVED)
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
    fun `state survives BoxStore restart`() {
        val seeded = seed()
        store.transitionState(seeded.patternId, PatternState.SNOOZED, snoozedUntilMs = now.toEpochMilli() + 1_000)
        boxStore.close()
        boxStore = VestigeBoxStore.openAt(dataDir)
        store = PatternStore(boxStore, clock)
        val readBack = store.findByPatternId(seeded.patternId)!!
        assertEquals(PatternState.SNOOZED, readBack.state)
    }
}
