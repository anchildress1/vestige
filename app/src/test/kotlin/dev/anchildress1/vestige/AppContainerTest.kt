package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.Embedder
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleState
import dev.anchildress1.vestige.model.EmbeddingArtifactManifest
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore
import dev.anchildress1.vestige.save.BackgroundExtractionSaveFlow
import dev.anchildress1.vestige.save.SaveOutcome
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.VectorBackfillWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.objectbox.BoxStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {

    @Test
    fun `cold-start recovery seeds recovered entries before service promotion`() = runTest {
        var serviceStarts = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { listOf(11L, 12L) },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = { serviceStarts += 1 },
            scope = backgroundScope,
        )

        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
        assertEquals(1, serviceStarts)
    }

    @Test
    fun `startForegroundService rejection schedules a retry for the active extraction`() = runTest {
        var serviceStarts = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {
                serviceStarts += 1
                if (serviceStarts == 1) error("background-start denied")
            },
            scope = backgroundScope,
        )

        container.reportExtractionStatus(entryId = 7L, status = ExtractionStatus.RUNNING)
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, container.lifecycleStateMachine.state.value)

        advanceTimeBy(5_001L)
        advanceUntilIdle()

        assertEquals(2, serviceStarts)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
    }

    @Test
    fun `work arriving during DEMOTING re-dispatches the foreground service after the platform ack`() = runTest {
        var serviceStarts = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = { serviceStarts += 1 },
            scope = backgroundScope,
        )

        container.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.RUNNING)
        container.lifecycleStateMachine.onForegroundStartConfirmed()
        container.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.COMPLETED)
        advanceTimeBy(30_001L)
        assertEquals(BackgroundExtractionLifecycleState.DEMOTING, container.lifecycleStateMachine.state.value)
        assertEquals(1, serviceStarts)

        // New capture lands while we're awaiting the platform stop ack.
        container.reportExtractionStatus(entryId = 2L, status = ExtractionStatus.RUNNING)
        container.lifecycleStateMachine.onForegroundStopConfirmed()
        advanceUntilIdle()

        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
        assertEquals(2, serviceStarts)
    }

    @Test
    fun `extractionStatusListener forwards worker updates into the lifecycle machine`() = runTest {
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        val listener = container.extractionStatusListener(entryId = 42L)
        listener.onUpdate(ExtractionStatus.RUNNING, entryAttemptCount = 0, lastError = null)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)

        container.lifecycleStateMachine.onForegroundStartConfirmed()
        listener.onUpdate(ExtractionStatus.COMPLETED, entryAttemptCount = 0, lastError = null)

        assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, container.lifecycleStateMachine.state.value)
    }

    @Test
    fun `backgroundExtractionSaveFlow is exposed from the production container wiring`() {
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        assertNotNull(container.backgroundExtractionSaveFlow)
    }

    @Test
    fun `requireEmbedder builds the process-scoped embedder from complete embedding artifacts once`() = runTest {
        val embedder = mockk<Embedder>(relaxed = true)
        val modelStore = mockk<ModelArtifactStore>()
        val tokenizerStore = mockk<ModelArtifactStore>()
        val modelFile = java.io.File("/tmp/embeddinggemma.tflite")
        val tokenizerFile = java.io.File("/tmp/sentencepiece.model")
        var embedderFactoryCalls = 0
        var capturedModelPath: String? = null
        var capturedTokenizerPath: String? = null
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns java.io.File("/tmp/app-files-stub")
        }
        coEvery { modelStore.requireComplete() } returns modelFile
        coEvery { tokenizerStore.requireComplete() } returns tokenizerFile

        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            embeddingArtifactManifestLoader = { EmbeddingArtifactManifest.loadDefault() },
            embeddingModelArtifactStoreFactory = { _, _, _ -> modelStore },
            embeddingTokenizerArtifactStoreFactory = { _, _, _ -> tokenizerStore },
            embedderFactory = { modelPath, tokenizerPath ->
                embedderFactoryCalls += 1
                capturedModelPath = modelPath
                capturedTokenizerPath = tokenizerPath
                embedder
            },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        val first = container.requireEmbedder()
        val second = container.requireEmbedder()

        assertEquals(embedder, first)
        assertEquals(embedder, second)
        assertEquals(1, embedderFactoryCalls)
        assertEquals(modelFile.absolutePath, capturedModelPath)
        assertEquals(tokenizerFile.absolutePath, capturedTokenizerPath)
        coVerify(exactly = 1) { modelStore.requireComplete() }
        coVerify(exactly = 1) { tokenizerStore.requireComplete() }
    }

    @Test
    fun `ensureBackgroundEngineInitialized only initializes the lazy engine once`() = runTest {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> engine },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        container.ensureBackgroundEngineInitialized()
        container.ensureBackgroundEngineInitialized()

        coVerify(exactly = 1) { engine.initialize() }
    }

    @Test
    fun `saveAndExtract initializes the engine before delegating to the save flow`() = runTest {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        val expected = SaveOutcome.Pending(entryId = 42L, extractionJob = kotlinx.coroutines.Job())
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 7, 21, 24, 0, ZoneId.of("America/New_York"))
        coEvery {
            saveFlow.saveAndExtract(
                entryText = "persist me",
                capturedAt = capturedAt,
                retrievedHistory = emptyList(),
                timeoutMs = null,
                persona = dev.anchildress1.vestige.model.Persona.WITNESS,
            )
        } returns expected
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> engine },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        val actual = container.saveAndExtract("persist me", capturedAt)

        assertEquals(expected, actual)
        coVerifyOrder {
            engine.initialize()
            saveFlow.saveAndExtract(
                entryText = "persist me",
                capturedAt = capturedAt,
                retrievedHistory = emptyList(),
                timeoutMs = null,
                persona = dev.anchildress1.vestige.model.Persona.WITNESS,
            )
        }
    }

    @Test
    fun `saveAndExtract reuses the initialized engine across repeated saves`() = runTest {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 7, 21, 24, 0, ZoneId.of("America/New_York"))
        coEvery {
            saveFlow.saveAndExtract(
                entryText = any(),
                capturedAt = capturedAt,
                retrievedHistory = any<List<HistoryChunk>>(),
                timeoutMs = any(),
                persona = any(),
            )
        } answers { SaveOutcome.Pending(entryId = 7L, extractionJob = kotlinx.coroutines.Job()) }
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> engine },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        container.saveAndExtract("first", capturedAt)
        container.saveAndExtract("second", capturedAt, timeoutMs = 90_000L)

        coVerify(exactly = 1) { engine.initialize() }
        coVerify(exactly = 2) { saveFlow.saveAndExtract(any(), capturedAt, any(), any(), any()) }
    }

    @Test
    fun `saveAndExtract schedules vector backfill after the entry is persisted`() = runTest {
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val capturedAt = ZonedDateTime.of(2026, 5, 12, 8, 15, 0, 0, ZoneId.of("America/New_York"))
        val expected = SaveOutcome.Pending(entryId = 42L, extractionJob = kotlinx.coroutines.Job())
        var scheduled = 0
        coEvery { saveFlow.saveAndExtract(any(), any(), any(), any(), any()) } returns expected

        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> engine },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            vectorBackfillScheduleListener = { scheduled += 1 },
            scope = backgroundScope,
        )

        val actual = container.saveAndExtract("persist me", capturedAt)

        assertEquals(expected, actual)
        assertEquals(1, scheduled)
    }

    @Test
    fun `saveAndExtract reports the new entry as PENDING before returning`() = runTest {
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val capturedAt = ZonedDateTime.of(2026, 5, 12, 8, 15, 0, 0, ZoneId.of("America/New_York"))
        coEvery {
            saveFlow.saveAndExtract(
                entryText = "persist me",
                capturedAt = capturedAt,
                retrievedHistory = emptyList(),
                timeoutMs = null,
                persona = dev.anchildress1.vestige.model.Persona.WITNESS,
            )
        } returns SaveOutcome.Pending(entryId = 42L, extractionJob = kotlinx.coroutines.Job())
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> engine },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        val actual = container.saveAndExtract("persist me", capturedAt)

        assertEquals(42L, actual.entryId)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
    }

    @Test
    fun `launchVectorBackfillIfReady retries the real pass after artifact states turn complete`() = runTest {
        val modelStore = mockk<ModelArtifactStore>()
        val tokenizerStore = mockk<ModelArtifactStore>()
        val worker = mockk<VectorBackfillWorker>()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns java.io.File("/tmp/app-files-stub")
        }
        coEvery { modelStore.currentState() } returnsMany listOf(
            ModelArtifactState.Partial(1L, 2L),
            ModelArtifactState.Complete,
        )
        coEvery { tokenizerStore.currentState() } returnsMany listOf(
            ModelArtifactState.Partial(1L, 2L),
            ModelArtifactState.Complete,
        )
        every { worker.hasPendingWork() } returnsMany listOf(true, true)
        coEvery { worker.backfill() } returns VectorBackfillWorker.BackfillStats(1, 1, 0, 1)
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            embeddingArtifactManifestLoader = { EmbeddingArtifactManifest.loadDefault() },
            embeddingModelArtifactStoreFactory = { _, _, _ -> modelStore },
            embeddingTokenizerArtifactStoreFactory = { _, _, _ -> tokenizerStore },
            vectorBackfillWorkerFactory = { _, _ -> worker },
            vectorBackfillRetryDelayMs = 1L,
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        container.launchVectorBackfillIfReady()
        advanceUntilIdle()

        coVerify(exactly = 1) { worker.backfill() }
        coVerify(exactly = 2) { modelStore.currentState() }
        coVerify(exactly = 2) { tokenizerStore.currentState() }
    }

    @Test
    fun `launchVectorBackfillIfReady drains a second trigger that lands during an active pass`() = runTest {
        val modelStore = mockk<ModelArtifactStore>()
        val tokenizerStore = mockk<ModelArtifactStore>()
        val worker = mockk<VectorBackfillWorker>()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns java.io.File("/tmp/app-files-stub")
        }
        var backfillCalls = 0
        lateinit var container: AppContainer
        coEvery { modelStore.currentState() } returns ModelArtifactState.Complete
        coEvery { tokenizerStore.currentState() } returns ModelArtifactState.Complete
        every { worker.hasPendingWork() } returnsMany listOf(true, true)
        coEvery { worker.backfill() } coAnswers {
            if (backfillCalls++ == 0) {
                container.launchVectorBackfillIfReady()
            }
            VectorBackfillWorker.BackfillStats(1, 1, 0, 1)
        }
        container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            embeddingArtifactManifestLoader = { EmbeddingArtifactManifest.loadDefault() },
            embeddingModelArtifactStoreFactory = { _, _, _ -> modelStore },
            embeddingTokenizerArtifactStoreFactory = { _, _, _ -> tokenizerStore },
            vectorBackfillWorkerFactory = { _, _ -> worker },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        container.launchVectorBackfillIfReady()
        advanceUntilIdle()

        coVerify(exactly = 2) { worker.backfill() }
    }

    @Test
    fun `launchVectorBackfillIfReady stops retrying after the configured budget`() = runTest {
        val modelStore = mockk<ModelArtifactStore>()
        val tokenizerStore = mockk<ModelArtifactStore>()
        val worker = mockk<VectorBackfillWorker>()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns java.io.File("/tmp/app-files-stub")
        }
        coEvery { modelStore.currentState() } returns ModelArtifactState.Partial(1L, 2L)
        coEvery { tokenizerStore.currentState() } returns ModelArtifactState.Partial(1L, 2L)
        every { worker.hasPendingWork() } returns true
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            embeddingArtifactManifestLoader = { EmbeddingArtifactManifest.loadDefault() },
            embeddingModelArtifactStoreFactory = { _, _, _ -> modelStore },
            embeddingTokenizerArtifactStoreFactory = { _, _, _ -> tokenizerStore },
            vectorBackfillWorkerFactory = { _, _ -> worker },
            vectorBackfillRetryDelayMs = 1L,
            vectorBackfillMaxRetries = 2,
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        container.launchVectorBackfillIfReady()
        advanceUntilIdle()

        coVerify(exactly = 3) { modelStore.currentState() }
        coVerify(exactly = 3) { tokenizerStore.currentState() }
        coVerify(exactly = 0) { worker.backfill() }
    }

    @Test
    fun `backgroundEngine is built from the injected factory exactly once`() {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        var modelPathCalls = 0
        var engineFactoryCalls = 0
        var capturedModelPath: String? = null
        var capturedCacheDir: String? = null
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns java.io.File("/tmp/cache-stub")
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = {
                modelPathCalls += 1
                "/tmp/fake-model.litertlm"
            },
            backgroundEngineFactory = { modelPath, cacheDir ->
                engineFactoryCalls += 1
                capturedModelPath = modelPath
                capturedCacheDir = cacheDir
                engine
            },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        // Force the lazy property; reading again must not re-invoke the factories.
        val first = container.backgroundEngine
        val second = container.backgroundEngine

        assertEquals(engine, first)
        assertEquals(engine, second)
        assertEquals(1, modelPathCalls)
        assertEquals(1, engineFactoryCalls)
        assertEquals("/tmp/fake-model.litertlm", capturedModelPath)
        assertEquals("/tmp/cache-stub", capturedCacheDir)
    }

    @Test
    fun `worker, observation generator, and save flow all share the lazy backgroundEngine`() {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        var engineFactoryCalls = 0
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns java.io.File("/tmp/cache-stub")
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ ->
                engineFactoryCalls += 1
                engine
            },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        // Touch all three properties — they must all reuse the same engine instance.
        assertNotNull(container.backgroundExtractionWorker)
        assertNotNull(container.observationGenerator)
        assertNotNull(container.backgroundExtractionSaveFlow)
        // backgroundEngine touched transitively by the three properties above.
        assertEquals(1, engineFactoryCalls)
    }

    @Test
    fun `close skips engine teardown when the lazy engine was never touched`() {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val boxStore = mockk<BoxStore>(relaxed = true)
        var engineFactoryCalls = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { boxStore },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ ->
                engineFactoryCalls += 1
                engine
            },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        // Never touch backgroundEngine — close() must not force its initialization.
        container.close()

        assertEquals(0, engineFactoryCalls)
        io.mockk.verify(exactly = 0) { engine.close() }
        io.mockk.verify(exactly = 1) { boxStore.close() }
    }

    @Test
    fun `close tears down the engine when it was lazily initialized`() {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val boxStore = mockk<BoxStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns java.io.File("/tmp/cache-stub")
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { boxStore },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> engine },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
        )

        // Force lazy init then close — engine.close() must fire.
        container.backgroundEngine
        container.close()

        io.mockk.verify(exactly = 1) { engine.close() }
        io.mockk.verify(exactly = 1) { boxStore.close() }
    }
}
