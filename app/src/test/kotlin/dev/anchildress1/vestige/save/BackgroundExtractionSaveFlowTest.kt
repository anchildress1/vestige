package dev.anchildress1.vestige.save

import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.ObservationGenerator
import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import dev.anchildress1.vestige.storage.EntryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The orchestrator's routing logic. The real EntryStore + BoxStore + markdown round-trip is
 * exercised in `:core-storage`'s `EntryStoreTest`; here we mock the store boundary so the JVM
 * unit test stays free of ObjectBox JNI.
 */
class BackgroundExtractionSaveFlowTest {

    private val entryStore: EntryStore = mockk(relaxed = true)
    private val worker: BackgroundExtractionWorker = mockk()
    private val observationGenerator: ObservationGenerator = mockk()
    private val listenerEvents = mutableListOf<ExtractionStatus>()
    private val capturedListener = slot<ExtractionStatusListener>()
    private val capturedRequest = slot<BackgroundExtractionRequest>()
    private val listenerFactory: (Long) -> ExtractionStatusListener = {
        ExtractionStatusListener { status, _, _ -> listenerEvents += status }
    }
    private val flow = BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory)

    @Test
    fun `success routes to completeEntry with resolver output, template label, and observations`() = runTest {
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        val resolved = canonicalSample()
        val observations = listOf(SAMPLE_OBSERVATION)
        coEvery {
            worker.extract(capture(capturedRequest), capture(capturedListener))
        } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(SAMPLE_TEXT, resolved, SAMPLE_TIMESTAMP) } returns observations

        val outcome = flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(SAMPLE_TEXT, capturedRequest.captured.entryText)
        assertEquals(SAMPLE_TIMESTAMP, capturedRequest.captured.capturedAt)
        assertEquals(0, capturedRequest.captured.entryAttemptCount)

        val completed = outcome as SaveOutcome.Completed
        assertEquals(ENTRY_ID, completed.entryId)
        assertEquals(observations, completed.observations)
        coVerifyOrder {
            entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_TIMESTAMP.toInstant())
            worker.extract(any(), any())
            observationGenerator.generate(SAMPLE_TEXT, resolved, SAMPLE_TIMESTAMP)
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, observations)
        }
        coVerify(exactly = 0) { entryStore.failEntry(any(), any(), any()) }
    }

    @Test
    fun `observation generator throwing does not block the save and persists empty list`() = runTest {
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        val resolved = canonicalSample()
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } throws RuntimeException("native crash")

        val outcome = flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        val completed = outcome as SaveOutcome.Completed
        assertEquals(emptyList<EntryObservation>(), completed.observations)
        coVerify(exactly = 1) {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
        }
    }

    @Test
    fun `failure routes to failEntry without running the observation generator`() = runTest {
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Failed(
            totalElapsedMs = 12_000L,
            lensResults = emptyList(),
            modelCallCount = 6,
            lastError = "all-lenses-parse-fail",
        )

        val outcome = flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        val failed = outcome as SaveOutcome.Failed
        assertEquals(ENTRY_ID, failed.entryId)
        coVerify(exactly = 1) {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "all-lenses-parse-fail")
        }
        coVerify(exactly = 0) {
            entryStore.completeEntry(any(), any(), any(), any())
            observationGenerator.generate(any(), any(), any())
        }
    }

    @Test
    fun `timeout routes to failEntry with TIMED_OUT and the wall-clock cap, no observation call`() = runTest {
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.TimedOut(
            totalElapsedMs = 90_000L,
            lensResults = emptyList(),
            modelCallCount = 2,
            timeoutMs = 90_000L,
        )

        val outcome = flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        val timedOut = outcome as SaveOutcome.TimedOut
        assertEquals(ENTRY_ID, timedOut.entryId)
        coVerify(exactly = 1) {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.TIMED_OUT, "timeout-after-90000ms")
        }
        coVerify(exactly = 0) { observationGenerator.generate(any(), any(), any()) }
    }

    @Test
    fun `worker receives the listener routed for the created entry id`() = runTest {
        val capturedIds = mutableListOf<Long>()
        val capturingFactory: (Long) -> ExtractionStatusListener = { id ->
            capturedIds += id
            ExtractionStatusListener { _, _, _ -> }
        }
        val flowWithCapture =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, capturingFactory)
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 10L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = ResolvedExtraction(emptyMap()),
            templateLabel = TemplateLabel.AUDIT,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()

        val outcome = flowWithCapture.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(listOf(ENTRY_ID), capturedIds)
        assertEquals(ENTRY_ID, outcome.entryId)
    }

    @Test
    fun `terminal completion reaches the lifecycle listener only after completeEntry succeeds`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator) { downstream }
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        coEvery {
            worker.extract(any(), capture(capturedListener))
        } coAnswers {
            capturedListener.captured.onUpdate(ExtractionStatus.RUNNING, 0, null)
            capturedListener.captured.onUpdate(ExtractionStatus.COMPLETED, 0, null)
            BackgroundExtractionResult.Success(
                totalElapsedMs = 25_000L,
                lensResults = emptyList(),
                modelCallCount = 3,
                resolved = canonicalSample(),
                templateLabel = TemplateLabel.AFTERMATH,
            )
        }
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()

        flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        coVerifyOrder {
            downstream.onUpdate(ExtractionStatus.RUNNING, 0, null)
            observationGenerator.generate(SAMPLE_TEXT, canonicalSample(), SAMPLE_TIMESTAMP)
            entryStore.completeEntry(ENTRY_ID, canonicalSample(), TemplateLabel.AFTERMATH, emptyList())
            downstream.onUpdate(ExtractionStatus.COMPLETED, 0, null)
        }
        verify(exactly = 0) { entryStore.failEntry(any(), any(), any()) }
    }

    @Test
    fun `persistence failure after worker success emits FAILED instead of leaking worker COMPLETED`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator) { downstream }
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        coEvery {
            worker.extract(any(), capture(capturedListener))
        } coAnswers {
            capturedListener.captured.onUpdate(ExtractionStatus.RUNNING, 0, null)
            capturedListener.captured.onUpdate(ExtractionStatus.COMPLETED, 0, null)
            BackgroundExtractionResult.Success(
                totalElapsedMs = 25_000L,
                lensResults = emptyList(),
                modelCallCount = 3,
                resolved = resolved,
                templateLabel = TemplateLabel.AFTERMATH,
            )
        }
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()
        every {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
        } throws IllegalStateException("disk blew up")

        try {
            flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)
            fail("expected persistence failure to escape")
        } catch (expected: IllegalStateException) {
            assertEquals("disk blew up", expected.message)
        }

        coVerifyOrder {
            downstream.onUpdate(ExtractionStatus.RUNNING, 0, null)
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
        coVerify(exactly = 0) { downstream.onUpdate(ExtractionStatus.COMPLETED, 0, null) }
    }

    @Test
    fun `worker listener events flow through the AppContainer-provided listener`() = runTest {
        every { entryStore.createPendingEntry(any(), any()) } returns ENTRY_ID
        coEvery {
            worker.extract(any(), capture(capturedListener))
        } coAnswers {
            capturedListener.captured.onUpdate(ExtractionStatus.RUNNING, 0, null)
            capturedListener.captured.onUpdate(ExtractionStatus.COMPLETED, 0, null)
            BackgroundExtractionResult.Success(
                totalElapsedMs = 25_000L,
                lensResults = emptyList(),
                modelCallCount = 3,
                resolved = canonicalSample(),
                templateLabel = TemplateLabel.AFTERMATH,
            )
        }
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()

        flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(listOf(ExtractionStatus.RUNNING, ExtractionStatus.COMPLETED), listenerEvents)
        verify(exactly = 0) { entryStore.failEntry(any(), any(), any()) }
    }

    private fun canonicalSample() = ResolvedExtraction(
        mapOf(
            "tags" to ResolvedField(listOf("standup", "flattened"), ConfidenceVerdict.CANONICAL),
            "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
        ),
    )

    private companion object {
        private const val ENTRY_ID: Long = 42L
        private val SAMPLE_TIMESTAMP: ZonedDateTime = ZonedDateTime.of(
            2026,
            5,
            11,
            7,
            21,
            24,
            0,
            ZoneId.of("America/New_York"),
        )
        private const val SAMPLE_TEXT = "Standup ran long again, then completely flattened."
        private val SAMPLE_OBSERVATION = EntryObservation(
            text = "You said \"fine\" and \"flattened\" in the same entry.",
            evidence = ObservationEvidence.VOCABULARY_CONTRADICTION,
            fields = listOf("vocabulary_contradictions"),
        )
    }
}
