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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The orchestrator's routing logic. The real EntryStore + BoxStore + markdown round-trip is
 * exercised in `:core-storage`'s `EntryStoreTest`; here we mock the store boundary so the JVM
 * unit test stays free of ObjectBox JNI.
 */
@Suppress("LargeClass") // Shared fixture (entryStore, worker, observationGenerator, flow) requires co-location.
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

    // Unconfined scope makes `scope.launch { ... }` run the detached extraction body on the
    // caller's thread up to the first real suspension point. Mocked dependencies don't suspend,
    // so the entire pipeline completes synchronously before `saveAndExtract` returns —
    // assertions on entryStore / listener side effects see the terminal state immediately.
    // `flowScope` is a fresh Job per test class so a CancellationException in one test doesn't
    // poison the next.
    private val flowScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val flow = BackgroundExtractionSaveFlow(
        entryStore = entryStore,
        worker = worker,
        observationGenerator = observationGenerator,
        listenerFactory = listenerFactory,
        scope = flowScope,
    )

    @Test
    fun `saveAndExtract emits PENDING to the listener before invoking the worker`() = runTest {
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        val listenerSnapshotAtWorkerCall = mutableListOf<ExtractionStatus>()
        coEvery { worker.extract(any(), any()) } coAnswers {
            // Snapshot the listener history at the moment the worker is invoked. If PENDING
            // hadn't fired pre-launch, this list would be empty (or the worker's own RUNNING
            // would arrive first).
            listenerSnapshotAtWorkerCall += listenerEvents
            BackgroundExtractionResult.Success(
                totalElapsedMs = 1L,
                lensResults = emptyList(),
                modelCallCount = 0,
                resolved = resolved,
                templateLabel = TemplateLabel.AFTERMATH,
            )
        }
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()

        flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(listOf(ExtractionStatus.PENDING), listenerSnapshotAtWorkerCall)
        assertEquals(ExtractionStatus.PENDING, listenerEvents.first())
    }

    @Test
    fun `pattern orchestrator callout is appended to the persisted observations`() = runTest {
        val orchestrator = mockk<dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator>()
        val flowWithOrch = BackgroundExtractionSaveFlow(
            entryStore = entryStore,
            worker = worker,
            observationGenerator = observationGenerator,
            listenerFactory = listenerFactory,
            scope = flowScope,
            patternOrchestrator = orchestrator,
        )
        val storedEntry = dev.anchildress1.vestige.storage.EntryEntity(
            id = ENTRY_ID,
            extractionStatus = ExtractionStatus.COMPLETED,
        )
        val callout = dev.anchildress1.vestige.model.EntryObservation(
            text = "Worth noting.",
            evidence = dev.anchildress1.vestige.model.ObservationEvidence.PATTERN_CALLOUT,
            fields = emptyList(),
        )
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        every { entryStore.readEntry(ENTRY_ID) } returns storedEntry
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()
        coEvery {
            orchestrator.onEntryCommitted(storedEntry, dev.anchildress1.vestige.model.Persona.WITNESS)
        } returns callout
        every { orchestrator.settleReservedCallout(storedEntry, fired = true) } returns Unit
        every { entryStore.appendObservation(ENTRY_ID, callout, any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[2] as () -> Unit).invoke()
        }

        val outcome = flowWithOrch.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(ENTRY_ID, outcome.entryId)
        // Order is the load-bearing fix: appendObservation MUST invoke the settlement callback
        // inside the same persistence transaction. With the two-tier refactor, the callout flows
        // through `appendObservation` instead of being returned on the save outcome.
        coVerifyOrder {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
            orchestrator.onEntryCommitted(storedEntry, dev.anchildress1.vestige.model.Persona.WITNESS)
            entryStore.appendObservation(ENTRY_ID, callout, any())
            orchestrator.settleReservedCallout(storedEntry, fired = true)
        }
    }

    @Test
    fun `pattern orchestration completion bumps visible data after the append path settles`() = runTest {
        val orchestrator = mockk<dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator>()
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithOrch = BackgroundExtractionSaveFlow(
            entryStore = entryStore,
            worker = worker,
            observationGenerator = observationGenerator,
            listenerFactory = { downstream },
            scope = flowScope,
            patternOrchestrator = orchestrator,
        )
        val storedEntry = dev.anchildress1.vestige.storage.EntryEntity(
            id = ENTRY_ID,
            extractionStatus = ExtractionStatus.COMPLETED,
        )
        val callout = dev.anchildress1.vestige.model.EntryObservation(
            text = "Worth noting.",
            evidence = dev.anchildress1.vestige.model.ObservationEvidence.PATTERN_CALLOUT,
            fields = emptyList(),
        )
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        every { entryStore.readEntry(ENTRY_ID) } returns storedEntry
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()
        coEvery {
            orchestrator.onEntryCommitted(storedEntry, dev.anchildress1.vestige.model.Persona.WITNESS)
        } returns callout
        every { orchestrator.settleReservedCallout(storedEntry, fired = true) } returns Unit
        every { entryStore.appendObservation(ENTRY_ID, callout, any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[2] as () -> Unit).invoke()
        }

        flowWithOrch.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        coVerifyOrder {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
            downstream.onUpdate(ExtractionStatus.COMPLETED, 0, null)
            orchestrator.onEntryCommitted(storedEntry, dev.anchildress1.vestige.model.Persona.WITNESS)
            entryStore.appendObservation(ENTRY_ID, callout, any())
            orchestrator.settleReservedCallout(storedEntry, fired = true)
            downstream.onUpdate(ExtractionStatus.COMPLETED, 0, null)
        }
    }

    @Test
    fun `appendObservation failure releases the reservation and skips confirm`() = runTest {
        val orchestrator = mockk<dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator>()
        val flowWithOrch = BackgroundExtractionSaveFlow(
            entryStore = entryStore,
            worker = worker,
            observationGenerator = observationGenerator,
            listenerFactory = listenerFactory,
            scope = flowScope,
            patternOrchestrator = orchestrator,
        )
        val storedEntry = dev.anchildress1.vestige.storage.EntryEntity(
            id = ENTRY_ID,
            extractionStatus = ExtractionStatus.COMPLETED,
        )
        val callout = dev.anchildress1.vestige.model.EntryObservation(
            text = "Worth noting.",
            evidence = dev.anchildress1.vestige.model.ObservationEvidence.PATTERN_CALLOUT,
            fields = emptyList(),
        )
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        every { entryStore.readEntry(ENTRY_ID) } returns storedEntry
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()
        coEvery {
            orchestrator.onEntryCommitted(storedEntry, dev.anchildress1.vestige.model.Persona.WITNESS)
        } returns callout
        every { entryStore.appendObservation(ENTRY_ID, callout, any()) } throws RuntimeException("markdown disk full")

        val outcome = flowWithOrch.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        // Save still completes; orchestrator failure swallowed.
        assertEquals(ENTRY_ID, outcome.entryId, "append failure must not abort the save")
        // CRITICAL: confirm must not run when append throws — the reservation gets released
        // instead because the user never saw the callout.
        verify(exactly = 0) { orchestrator.settleReservedCallout(any(), fired = true) }
        verify(exactly = 1) { orchestrator.settleReservedCallout(storedEntry, fired = false) }
    }

    @Test
    fun `pattern orchestrator throwing is swallowed and does not block save`() = runTest {
        val orchestrator = mockk<dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator>()
        val flowWithOrch = BackgroundExtractionSaveFlow(
            entryStore = entryStore,
            worker = worker,
            observationGenerator = observationGenerator,
            listenerFactory = listenerFactory,
            scope = flowScope,
            patternOrchestrator = orchestrator,
        )
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        every { entryStore.readEntry(ENTRY_ID) } returns dev.anchildress1.vestige.storage.EntryEntity(id = ENTRY_ID)
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()
        coEvery { orchestrator.onEntryCommitted(any(), any()) } throws RuntimeException("native crash")

        val outcome = flowWithOrch.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(ENTRY_ID, outcome.entryId, "orchestrator failure must not abort the save")
        verify(exactly = 0) { entryStore.appendObservation(any(), any(), any()) }
    }

    @Test
    fun `success routes to completeEntry with resolver output, template label, and observations`() = runTest {
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
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

        assertEquals(ENTRY_ID, outcome.entryId)
        coVerifyOrder {
            entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_TIMESTAMP.toInstant(), 0L)
            worker.extract(any(), any())
            observationGenerator.generate(SAMPLE_TEXT, resolved, SAMPLE_TIMESTAMP)
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, observations)
        }
        coVerify(exactly = 0) { entryStore.failEntry(any(), any(), any()) }
    }

    @Test
    fun `observation generator throwing does not block the save and persists empty list`() = runTest {
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
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

        assertEquals(ENTRY_ID, outcome.entryId)
        // Generator threw → empty observation list persisted instead of aborting the save.
        coVerify(exactly = 1) {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
        }
    }

    @Test
    fun `failure routes to failEntry without running the observation generator`() = runTest {
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Failed(
            totalElapsedMs = 12_000L,
            lensResults = emptyList(),
            modelCallCount = 6,
            lastError = "all-lenses-parse-fail",
        )

        val outcome = flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(ENTRY_ID, outcome.entryId)
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
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.TimedOut(
            totalElapsedMs = 90_000L,
            lensResults = emptyList(),
            modelCallCount = 2,
            timeoutMs = 90_000L,
        )

        val outcome = flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(ENTRY_ID, outcome.entryId)
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
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, capturingFactory, flowScope)
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
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
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
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
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
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

        // Persistence failure is caught inside the detached extraction, routed through
        // compensatePersistenceFailure, and surfaced as FAILED. saveAndExtract itself never throws.
        flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        coVerifyOrder {
            downstream.onUpdate(ExtractionStatus.RUNNING, 0, null)
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
        coVerify(exactly = 0) { downstream.onUpdate(ExtractionStatus.COMPLETED, 0, null) }
        coVerify(exactly = 1) {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
    }

    @Test
    fun `failed result reaches lifecycle listener only after failEntry succeeds`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        val failEntryReturned = CompletableDeferred<Unit>()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery {
            worker.extract(any(), capture(capturedListener))
        } coAnswers {
            capturedListener.captured.onUpdate(ExtractionStatus.RUNNING, 0, null)
            capturedListener.captured.onUpdate(ExtractionStatus.FAILED, 0, "all-lenses-parse-fail")
            BackgroundExtractionResult.Failed(
                totalElapsedMs = 12_000L,
                lensResults = emptyList(),
                modelCallCount = 6,
                lastError = "all-lenses-parse-fail",
            )
        }
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "all-lenses-parse-fail")
        } answers {
            failEntryReturned.complete(Unit)
            Unit
        }
        coEvery {
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "all-lenses-parse-fail")
        } coAnswers {
            assertTrue(failEntryReturned.isCompleted)
            Unit
        }

        val outcome = flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(ENTRY_ID, outcome.entryId)
        coVerifyOrder {
            downstream.onUpdate(ExtractionStatus.RUNNING, 0, null)
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "all-lenses-parse-fail")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "all-lenses-parse-fail")
        }
    }

    @Test
    fun `timed out result reaches lifecycle listener only after failEntry succeeds`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        val failEntryReturned = CompletableDeferred<Unit>()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery {
            worker.extract(any(), capture(capturedListener))
        } coAnswers {
            capturedListener.captured.onUpdate(ExtractionStatus.RUNNING, 0, null)
            capturedListener.captured.onUpdate(ExtractionStatus.TIMED_OUT, 0, "timeout-after-90000ms")
            BackgroundExtractionResult.TimedOut(
                totalElapsedMs = 90_000L,
                lensResults = emptyList(),
                modelCallCount = 2,
                timeoutMs = 90_000L,
            )
        }
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.TIMED_OUT, "timeout-after-90000ms")
        } answers {
            failEntryReturned.complete(Unit)
            Unit
        }
        coEvery {
            downstream.onUpdate(ExtractionStatus.TIMED_OUT, 0, "timeout-after-90000ms")
        } coAnswers {
            assertTrue(failEntryReturned.isCompleted)
            Unit
        }

        val outcome = flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        assertEquals(ENTRY_ID, outcome.entryId)
        coVerifyOrder {
            downstream.onUpdate(ExtractionStatus.RUNNING, 0, null)
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.TIMED_OUT, "timeout-after-90000ms")
            downstream.onUpdate(ExtractionStatus.TIMED_OUT, 0, "timeout-after-90000ms")
        }
    }

    @Test
    fun `persistence failure on Failed path routes through compensation`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Failed(
            totalElapsedMs = 12_000L,
            lensResults = emptyList(),
            modelCallCount = 6,
            lastError = "all-lenses-parse-fail",
        )
        // First failEntry — the handleFailure persist — throws.
        // Second failEntry — the compensation call — succeeds.
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "all-lenses-parse-fail")
        } throws IllegalStateException("disk blew up")
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
        } answers { Unit }

        // First failEntry throws; compensatePersistenceFailure catches it and re-writes the
        // terminal FAILED row. Caller never sees either exception.
        flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        coVerifyOrder {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "all-lenses-parse-fail")
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
        coVerify(exactly = 1) {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
    }

    @Test
    fun `persistence failure on TimedOut path routes through compensation`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.TimedOut(
            totalElapsedMs = 90_000L,
            lensResults = emptyList(),
            modelCallCount = 2,
            timeoutMs = 90_000L,
        )
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.TIMED_OUT, "timeout-after-90000ms")
        } throws IllegalStateException("fs read-only")
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
        } answers { Unit }

        // Compensation handles the timeout's secondary persistence failure; caller only sees Pending.
        flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        coVerifyOrder {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.TIMED_OUT, "timeout-after-90000ms")
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
        coVerify(exactly = 1) {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
    }

    @Test
    fun `compensation that itself throws is swallowed so the original error escapes`() = runTest {
        val downstream: ExtractionStatusListener = mockk(relaxed = true)
        val flowWithMockListener =
            BackgroundExtractionSaveFlow(entryStore, worker, observationGenerator, listenerFactory = {
                downstream
            }, scope = flowScope)
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 25_000L,
            lensResults = emptyList(),
            modelCallCount = 3,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()
        every {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
        } throws IllegalStateException("primary disk error")
        // Compensation also throws — the failure must not mask the original IllegalStateException.
        every {
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
        } throws RuntimeException("secondary fs error")

        // Both the primary IllegalStateException and the secondary compensation
        // RuntimeException are caught inside the detached extraction; saveAndExtract returns
        // Pending. Both write attempts still fire and the lifecycle listener still gets FAILED.
        flowWithMockListener.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP)

        coVerifyOrder {
            entryStore.completeEntry(ENTRY_ID, resolved, TemplateLabel.AFTERMATH, emptyList())
            entryStore.failEntry(ENTRY_ID, ExtractionStatus.FAILED, "persistence-error:IllegalStateException")
            downstream.onUpdate(ExtractionStatus.FAILED, 0, "persistence-error:IllegalStateException")
        }
    }

    @Test
    fun `worker listener events flow through the AppContainer-provided listener`() = runTest {
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
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

        assertEquals(
            listOf(ExtractionStatus.PENDING, ExtractionStatus.RUNNING, ExtractionStatus.COMPLETED),
            listenerEvents,
        )
        verify(exactly = 0) { entryStore.failEntry(any(), any(), any()) }
    }

    @Test
    fun `saveAndExtract threads durationMs through to createPendingEntry`() = runTest {
        val resolved = canonicalSample()
        every { entryStore.createPendingEntry(any(), any(), any()) } returns ENTRY_ID
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 1L,
            lensResults = emptyList(),
            modelCallCount = 0,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()

        flow.saveAndExtract(SAMPLE_TEXT, SAMPLE_TIMESTAMP, durationMs = 180_000L)

        verify(exactly = 1) {
            entryStore.createPendingEntry(SAMPLE_TEXT, SAMPLE_TIMESTAMP.toInstant(), 180_000L)
        }
    }

    private fun canonicalSample() = ResolvedExtraction(
        mapOf(
            "tags" to ResolvedField(listOf("standup", "flattened"), ConfidenceVerdict.CANONICAL),
            "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
        ),
    )

    @Test
    fun `recoverEntry runs the detached pipeline against the existing entry id without creating a new row`() = runTest {
        val resolved = canonicalSample()
        coEvery { worker.extract(any(), any()) } returns BackgroundExtractionResult.Success(
            totalElapsedMs = 12L,
            lensResults = emptyList(),
            modelCallCount = 0,
            resolved = resolved,
            templateLabel = TemplateLabel.AFTERMATH,
        )
        coEvery { observationGenerator.generate(any(), any(), any()) } returns emptyList()

        flow.recoverEntry(ENTRY_ID, "recovered text", SAMPLE_TIMESTAMP).join()

        // No new pending entry: recoverEntry attaches to an existing row.
        verify(exactly = 0) { entryStore.createPendingEntry(any(), any(), any()) }
        // Listener relays PENDING then COMPLETED for the recovery run.
        assertEquals(ExtractionStatus.PENDING, listenerEvents.first())
        assertTrue(listenerEvents.contains(ExtractionStatus.COMPLETED))
    }

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
