package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.PatternTitleGenerator
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * ADR-016 contract — on device, end-to-end.
 *
 * Two ACTIVE patterns both match the AFTERMATH template_label. Entry 1 fires one of them
 * (whichever wins the tiebreak); that pattern enters its 3-entry suppression window. Entry 2
 * fires the OTHER pattern — per-pattern cooldown means the first-fired pattern's window
 * doesn't muzzle anyone else. Entry 3 fires nothing (both patterns are in cooldown). Entry 4
 * fires nothing. Entry 5 — first-fired pattern's window has counted down to 0 — fires again.
 *
 * Detection itself is gated off via `patternSurfaceMinEntries = Long.MAX_VALUE` so the
 * pre-seeded patterns are the only ones the orchestrator considers. Title generation is
 * mocked because no new pattern inserts during the test.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.PatternCooldownSmokeTest
 *
 * No model, no manifest — the contract is deterministic. Runs in seconds on the reference device.
 */
@RunWith(AndroidJUnit4::class)
class PatternCooldownSmokeTest {

    @Test
    fun perPatternCooldownEnforcesPhase3ContractOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val boxStoreDir = File(context.cacheDir, "pattern-cooldown-smoke-${System.currentTimeMillis()}")
        require(boxStoreDir.mkdirs()) { "Could not create $boxStoreDir" }
        val boxStore = VestigeBoxStore.openAt(boxStoreDir)
        val markdownDir = File(context.cacheDir, "pattern-cooldown-md-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val clock = Clock.systemUTC()
            val zone = ZoneId.systemDefault()
            val entryStore = EntryStore(boxStore, MarkdownEntryStore(markdownDir))
            val patternStore = PatternStore(boxStore, clock)
            val cooldownStore = CalloutCooldownStore(boxStore)
            val detector = PatternDetector(boxStore, clock, zone)
            val titleGenerator = mockk<PatternTitleGenerator>(relaxed = true)
            val orchestrator = PatternDetectionOrchestrator(
                boxStore = boxStore,
                detector = detector,
                patternStore = patternStore,
                titleGenerator = titleGenerator,
                cooldownStore = cooldownStore,
                clock = clock,
                zoneId = zone,
                // Detection disabled — the test verifies cooldown behavior on pre-seeded patterns.
                patternSurfaceMinEntries = Long.MAX_VALUE,
            )

            patternStore.put(seedPattern(PATTERN_A_ID, "A loud.", lastSeen = 9L))
            patternStore.put(seedPattern(PATTERN_B_ID, "B quiet.", lastSeen = 2L))

            val fire1 = commitAftermathEntry(entryStore, orchestrator)
            assertNotNull("entry 1 must fire one of the two patterns", fire1)
            val firstFired = identifyByText(fire1!!.text)

            val fire2 = commitAftermathEntry(entryStore, orchestrator)
            assertNotNull("entry 2 must fire the other pattern (per-pattern cooldown lets it through)", fire2)
            val secondFired = identifyByText(fire2!!.text)
            assertNotEquals("entry 2 must fire a different pattern than entry 1", firstFired, secondFired)

            // Entries 3 and 4 — both patterns are in their suppression windows.
            assertEquals(null, commitAftermathEntry(entryStore, orchestrator))
            assertEquals(null, commitAftermathEntry(entryStore, orchestrator))

            // Entry 5 — first-fired pattern's window has decremented to 0 and refires.
            val fire5 = commitAftermathEntry(entryStore, orchestrator)
            assertNotNull("entry 5 must refire the first-fired pattern (window cleared)", fire5)
            assertEquals(firstFired, identifyByText(fire5!!.text))
        } finally {
            boxStore.close()
            boxStoreDir.deleteRecursively()
            markdownDir.deleteRecursively()
        }
    }

    private suspend fun commitAftermathEntry(
        entryStore: EntryStore,
        orchestrator: PatternDetectionOrchestrator,
    ): dev.anchildress1.vestige.model.EntryObservation? {
        val id = entryStore.createPendingEntry(
            entryText = "smoke entry ${System.nanoTime()}",
            timestamp = Instant.now(),
            durationMs = 0L,
            followUpText = null,
            persona = Persona.WITNESS,
        )
        entryStore.completeEntry(
            entryId = id,
            resolved = ResolvedExtraction(emptyMap()),
            templateLabel = TemplateLabel.AFTERMATH,
            observations = emptyList(),
            lensReceipts = emptyList(),
        )
        val entry = entryStore.readEntry(id)!!
        val callout = orchestrator.onEntryCommitted(entry, Persona.WITNESS)
        orchestrator.settleReservedCallout(entry, fired = callout != null)
        return callout
    }

    private fun identifyByText(text: String): String = when (text) {
        "A loud." -> PATTERN_A_ID
        "B quiet." -> PATTERN_B_ID
        else -> error("Unknown callout text: $text")
    }

    private fun seedPattern(patternId: String, calloutText: String, lastSeen: Long) = PatternEntity(
        patternId = patternId,
        kind = PatternKind.TEMPLATE_RECURRENCE,
        signatureJson = "{\"label\":\"aftermath\",\"id\":\"$patternId\"}",
        title = "Aftermath",
        templateLabel = TemplateLabel.AFTERMATH.serial,
        firstSeenTimestamp = 1L,
        lastSeenTimestamp = lastSeen,
        state = PatternState.ACTIVE,
        latestCalloutText = calloutText,
    )

    private companion object {
        const val PATTERN_A_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PATTERN_B_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
