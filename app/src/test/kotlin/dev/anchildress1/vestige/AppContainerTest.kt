package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.Embedder
import dev.anchildress1.vestige.inference.ForegroundResult
import dev.anchildress1.vestige.inference.ForegroundStreamEvent
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleState
import dev.anchildress1.vestige.model.EmbeddingArtifactManifest
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore
import dev.anchildress1.vestige.model.ModelManifest
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.save.BackgroundExtractionSaveFlow
import dev.anchildress1.vestige.save.SaveOutcome
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.VectorBackfillWorker
import dev.anchildress1.vestige.testing.cleanupObjectBoxTempRoot
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.objectbox.BoxStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // Wiring + lifecycle + save + recovery scenarios share one container fixture.
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
        val boxDir = newInMemoryObjectBoxDirectory("retry")
        val box = openInMemoryBoxStore(boxDir)
        try {
            val entryId = box.boxFor(EntryEntity::class.java)
                .put(EntryEntity(entryText = "x", timestampEpochMs = 1))
            val container = AppContainer(
                applicationContext = mockk<Context>(relaxed = true),
                boxStoreFactory = { box },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {
                    serviceStarts += 1
                    if (serviceStarts == 1) error("background-start denied")
                },
                scope = backgroundScope,
            )

            container.reportExtractionStatus(entryId = entryId, status = ExtractionStatus.RUNNING)
            assertEquals(BackgroundExtractionLifecycleState.NORMAL, container.lifecycleStateMachine.state.value)

            advanceTimeBy(5_001L)
            advanceUntilIdle()

            assertEquals(2, serviceStarts)
            assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
        } finally {
            box.close()
        }
    }

    @Test
    fun `work arriving during DEMOTING re-dispatches the foreground service after the platform ack`() = runTest {
        var serviceStarts = 0
        val boxDir = newInMemoryObjectBoxDirectory("demoting")
        val box = openInMemoryBoxStore(boxDir)
        try {
            val firstEntryId = box.boxFor(EntryEntity::class.java)
                .put(EntryEntity(entryText = "first", timestampEpochMs = 1))
            val secondEntryId = box.boxFor(EntryEntity::class.java)
                .put(EntryEntity(entryText = "second", timestampEpochMs = 2))
            val container = AppContainer(
                applicationContext = mockk<Context>(relaxed = true),
                boxStoreFactory = { box },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = { serviceStarts += 1 },
                scope = backgroundScope,
            )

            container.reportExtractionStatus(entryId = firstEntryId, status = ExtractionStatus.RUNNING)
            container.lifecycleStateMachine.onForegroundStartConfirmed()
            container.reportExtractionStatus(entryId = firstEntryId, status = ExtractionStatus.COMPLETED)
            advanceTimeBy(30_001L)
            assertEquals(BackgroundExtractionLifecycleState.DEMOTING, container.lifecycleStateMachine.state.value)
            assertEquals(1, serviceStarts)

            // New capture lands while we're awaiting the platform stop ack.
            container.reportExtractionStatus(entryId = secondEntryId, status = ExtractionStatus.RUNNING)
            container.lifecycleStateMachine.onForegroundStopConfirmed()
            advanceUntilIdle()

            assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)
            assertEquals(2, serviceStarts)
        } finally {
            box.close()
        }
    }

    @Test
    fun `extractionStatusListener forwards worker updates into the lifecycle machine`() = runTest {
        val boxDir = newInMemoryObjectBoxDirectory("listener")
        val box = openInMemoryBoxStore(boxDir)
        try {
            val entryId = box.boxFor(EntryEntity::class.java)
                .put(EntryEntity(entryText = "x", timestampEpochMs = 1))
            val container = AppContainer(
                applicationContext = mockk<Context>(relaxed = true),
                boxStoreFactory = { box },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = backgroundScope,
            )

            val listener = container.extractionStatusListener(entryId = entryId)
            listener.onUpdate(ExtractionStatus.RUNNING, entryAttemptCount = 0, lastError = null)
            assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)

            container.lifecycleStateMachine.onForegroundStartConfirmed()
            listener.onUpdate(ExtractionStatus.COMPLETED, entryAttemptCount = 0, lastError = null)

            assertEquals(BackgroundExtractionLifecycleState.KEEP_ALIVE, container.lifecycleStateMachine.state.value)
        } finally {
            box.close()
        }
    }

    @Test
    fun `extractionStatusListener never schedules vector backfill directly`() = runTest {
        var scheduled = 0
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            vectorBackfillScheduleListener = { scheduled += 1 },
            scope = backgroundScope,
        )

        val listener = container.extractionStatusListener(entryId = 7L)

        listener.onUpdate(ExtractionStatus.COMPLETED, entryAttemptCount = 0, lastError = null)
        listener.onUpdate(ExtractionStatus.FAILED, entryAttemptCount = 1, lastError = "boom")
        listener.onUpdate(ExtractionStatus.TIMED_OUT, entryAttemptCount = 2, lastError = "slow")

        assertEquals(0, scheduled, "save-flow finalization owns backfill scheduling now")
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
    fun `backgroundExtractionSaveFlow finalization callback schedules vector backfill`() {
        val saveFlow = mockk<BackgroundExtractionSaveFlow>(relaxed = true)
        var scheduled = 0
        var lifecycleCallbacks: dev.anchildress1.vestige.save.BackgroundExtractionLifecycleCallbacks? = null
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true),
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { "/tmp/fake-model.litertlm" },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            backgroundExtractionSaveFlowFactory = { _, _, _, callbacks, _, _ ->
                lifecycleCallbacks = callbacks
                saveFlow
            },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            vectorBackfillScheduleListener = { scheduled += 1 },
        )

        container.backgroundExtractionSaveFlow
        assertNotNull(lifecycleCallbacks)

        lifecycleCallbacks!!.onEntryFinalized(7L)

        assertEquals(1, scheduled)
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
                durationMs = 0L,
                followUpText = null,
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
                durationMs = 0L,
                followUpText = null,
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
                durationMs = any(),
                followUpText = any(),
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
        coVerify(exactly = 2) { saveFlow.saveAndExtract(any(), capturedAt, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saveAndExtract threads caller-supplied durationMs through to the save flow`() = runTest {
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        val capturedAt = ZonedDateTime.of(2026, 5, 11, 7, 21, 24, 0, ZoneId.of("America/New_York"))
        coEvery { saveFlow.saveAndExtract(any(), any(), any(), any(), any(), any(), any()) } answers {
            SaveOutcome.Pending(entryId = 1L, extractionJob = kotlinx.coroutines.Job())
        }
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

        container.saveAndExtract("voice entry", capturedAt, durationMs = 90_000L)

        coVerify(exactly = 1) {
            saveFlow.saveAndExtract(
                entryText = "voice entry",
                capturedAt = capturedAt,
                retrievedHistory = emptyList(),
                timeoutMs = null,
                persona = dev.anchildress1.vestige.model.Persona.WITNESS,
                durationMs = 90_000L,
                followUpText = null,
            )
        }
    }

    @Test
    fun `saveAndExtract schedules vector backfill after the entry is persisted`() = runTest {
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val capturedAt = ZonedDateTime.of(2026, 5, 12, 8, 15, 0, 0, ZoneId.of("America/New_York"))
        val expected = SaveOutcome.Pending(entryId = 42L, extractionJob = kotlinx.coroutines.Job())
        var scheduled = 0
        coEvery { saveFlow.saveAndExtract(any(), any(), any(), any(), any(), any(), any()) } returns expected

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
    fun `runForegroundTextCall initializes the engine then delegates to the foreground text path`(
        @TempDir tempRoot: File,
    ) = runTest {
        // The engine streams "" so the terminal parse yields a ParseFailure — enough to prove
        // the wiring (engine init + delegation to the streaming text path). Response parsing
        // itself is covered at the core-inference tier (ForegroundInferenceTest), where the
        // litertlm Content type is on the classpath.
        val engine = mockk<LiteRtLmEngine>(relaxed = true) {
            every { streamMessageContents(any()) } returns flowOf("")
        }
        val modelFile = File(tempRoot, "ready-model.litertlm").apply { writeText("x") }
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engine },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        val events = container.runForegroundTextCall(
            text = "typed it",
            persona = dev.anchildress1.vestige.model.Persona.EDITOR,
        ).toList()

        val terminal = events.filterIsInstance<ForegroundStreamEvent.Terminal>().single()
        assertTrue(terminal.result is ForegroundResult.ParseFailure)
        coVerify { engine.initialize() }
    }

    @Test
    fun `deleteMainModel removes the artifact and drops readiness to Loading`(@TempDir tempRoot: File) = runTest {
        val modelFile = File(tempRoot, "main-model.litertlm").apply { writeText("xx") } // 2 bytes
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
        val extractionFinished = CompletableDeferred<Unit>()
        val extractionJob = kotlinx.coroutines.Job().also { job ->
            job.invokeOnCompletion {
                extractionFinished.complete(Unit)
            }
        }
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val engineMock = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        coEvery { saveFlow.saveAndExtract(any(), any(), any(), any(), any(), any(), any()) } returns
            SaveOutcome.Pending(entryId = 7L, extractionJob = extractionJob)
        var stopRequests = 0
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engineMock },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            foregroundServiceStopper = { stopRequests += 1 },
            scope = this,
        )
        container.refreshModelReadiness()
        advanceUntilIdle()
        assertEquals(ModelReadiness.Ready, container.modelReadinessFlow.value)
        container.ensureBackgroundEngineInitialized()
        container.saveAndExtract("still running", CAPTURED_AT)
        container.lifecycleStateMachine.onInFlightCountChange(1)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)

        container.deleteMainModel()
        advanceUntilIdle()

        assertTrue(!modelFile.exists(), "artifact file must be gone after deleteMainModel")
        assertEquals(ModelReadiness.Loading, container.modelReadinessFlow.value)
        assertTrue(extractionJob.isCancelled)
        assertTrue(extractionFinished.isCompleted)
        assertEquals(1, stopRequests)
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, container.lifecycleStateMachine.state.value)
        verify(exactly = 1) { engineMock.close() }
    }

    @Test
    fun `deleteMainModel closes the initialized engine so later work reinitializes it`(@TempDir tempRoot: File) =
        runTest {
            val modelFile = File(tempRoot, "main-model.litertlm").apply { writeText("xx") }
            val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
            val engine = mockk<LiteRtLmEngine>(relaxed = true)
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> engine },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = this,
            )

            container.ensureBackgroundEngineInitialized()
            container.deleteMainModel()
            advanceUntilIdle()
            container.ensureBackgroundEngineInitialized()

            coVerifyOrder {
                engine.initialize()
                engine.close()
                engine.initialize()
            }
        }

    @Test
    fun `redownloadMainModel pulls a fresh artifact and settles readiness Ready`(@TempDir tempRoot: File) = runTest {
        val modelFile = File(tempRoot, "main-model.litertlm") // absent until the re-pull writes it
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
        coEvery { artifactStore.download(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onProgress = firstArg<(Long, Long) -> Unit>()
            onProgress(1L, 2L)
            modelFile.writeText("xx")
            ModelArtifactState.Complete
        }
        val extractionFinished = CompletableDeferred<Unit>()
        val extractionJob = kotlinx.coroutines.Job().also { job ->
            job.invokeOnCompletion {
                extractionFinished.complete(Unit)
            }
        }
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val engineMock = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>()
        coEvery { saveFlow.saveAndExtract(any(), any(), any(), any(), any(), any(), any()) } returns
            SaveOutcome.Pending(entryId = 9L, extractionJob = extractionJob)
        var stopRequests = 0
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engineMock },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            foregroundServiceStopper = { stopRequests += 1 },
            scope = this,
        )
        container.ensureBackgroundEngineInitialized()
        container.saveAndExtract("still running", CAPTURED_AT)
        container.lifecycleStateMachine.onInFlightCountChange(1)
        assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)

        container.redownloadMainModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { artifactStore.download(any()) }
        assertTrue(extractionJob.isCancelled)
        assertTrue(extractionFinished.isCompleted)
        assertEquals(1, stopRequests)
        assertEquals(BackgroundExtractionLifecycleState.NORMAL, container.lifecycleStateMachine.state.value)
        verify(exactly = 1) { engineMock.close() }
        coVerify(exactly = 2) { engineMock.initialize() }
        assertTrue(modelFile.exists(), "re-pull must land the artifact on disk")
        assertEquals(ModelReadiness.Ready, container.modelReadinessFlow.value)
    }

    @Test
    fun `redownloadMainModel closes the initialized engine so the same wrapper can be reinitialized`(
        @TempDir tempRoot: File,
    ) = runTest {
        val modelFile = File(tempRoot, "main-model.litertlm").apply { writeText("xx") }
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
        coEvery { artifactStore.download(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onProgress = firstArg<(Long, Long) -> Unit>()
            onProgress(2L, 2L)
            modelFile.writeText("yy")
            ModelArtifactState.Complete
        }
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engine },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        container.ensureBackgroundEngineInitialized()
        container.redownloadMainModel()
        advanceUntilIdle()
        container.ensureBackgroundEngineInitialized()

        coVerifyOrder {
            engine.initialize()
            engine.close()
            artifactStore.download(any())
            engine.initialize()
        }
    }

    @Test
    fun `refreshModelReadiness maps a leftover partial artifact to Paused`(@TempDir tempRoot: File) = runTest {
        val modelFile = File(tempRoot, "main-model.litertlm").apply { writeText("x") }
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        container.refreshModelReadiness()
        advanceUntilIdle()

        assertEquals(ModelReadiness.Paused, container.modelReadinessFlow.value)
    }

    @Test
    fun `refreshModelReadiness leaves a checksum-corrupt full-size artifact Loading`(@TempDir tempRoot: File) =
        runTest {
            val modelFile = File(tempRoot, "main-model.litertlm").apply { writeText("xx") }
            val manifest = ModelManifest(
                schemaVersion = ModelManifest.SUPPORTED_SCHEMA_VERSION,
                artifactRepo = "test",
                filename = modelFile.name,
                downloadUrl = "https://example.invalid/${modelFile.name}",
                expectedByteSize = 2L,
                sha256 = "expected",
                allowedHosts = listOf("example.invalid"),
            )
            val artifactStore = mockk<ModelArtifactStore>(relaxed = true) {
                every { this@mockk.artifactFile } returns modelFile
                every { this@mockk.manifest } returns manifest
                coEvery { this@mockk.probe() } returns ModelArtifactState.Complete
                coEvery {
                    this@mockk.currentState()
                } returns ModelArtifactState.Corrupt(expectedSha256 = "expected", actualSha256 = "actual")
            }
            val engineMock = mockk<LiteRtLmEngine>(relaxed = true)
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> engineMock },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = this,
            )

            container.refreshModelReadiness()
            advanceUntilIdle()

            assertEquals(ModelReadiness.Loading, container.modelReadinessFlow.value)
            coVerify(exactly = 1) { artifactStore.currentState() }
            coVerify(exactly = 0) { engineMock.initialize() }
        }

    @Test
    fun `redownloadMainModel ignores a second request while the first download is active`(@TempDir tempRoot: File) =
        runTest {
            val modelFile = File(tempRoot, "main-model.litertlm")
            val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
            val releaseDownload = CompletableDeferred<Unit>()
            coEvery { artifactStore.download(any()) } coAnswers {
                releaseDownload.await()
                modelFile.writeText("xx")
                ModelArtifactState.Complete
            }
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = this,
            )

            container.redownloadMainModel()
            advanceUntilIdle()
            container.redownloadMainModel()
            advanceUntilIdle()
            releaseDownload.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { artifactStore.download(any()) }
            assertEquals(ModelReadiness.Ready, container.modelReadinessFlow.value)
        }

    @Test
    fun `redownloadMainModel discards a Corrupt download result and lands Loading`(@TempDir tempRoot: File) = runTest {
        val modelFile = File(tempRoot, "main-model.litertlm")
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
        coEvery { artifactStore.download(any()) } returns ModelArtifactState.Corrupt(
            expectedSha256 = "expected",
            actualSha256 = "actual",
        )
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )
        container.redownloadMainModel()
        advanceUntilIdle()
        assertFalse(modelFile.exists(), "corrupt artifact must be discarded")
        assertEquals(ModelReadiness.Loading, container.modelReadinessFlow.value)
    }

    @Test
    fun `wipeAllData clears every entity box and every markdown file`(@TempDir tempRoot: File) = runTest {
        val boxDir = newInMemoryObjectBoxDirectory("wipe")
        val box = openInMemoryBoxStore(boxDir)
        try {
            box.boxFor(EntryEntity::class.java)
                .put(EntryEntity(markdownFilename = "a.md", entryText = "x", timestampEpochMs = 1))
            File(tempRoot, "entries").mkdirs()
            File(tempRoot, "entries/a.md").writeText("x")
            File(tempRoot, "entries/b.md").writeText("y")
            var stopRequests = 0
            val container = AppContainer(
                applicationContext = mockk<Context>(relaxed = true) { every { filesDir } returns tempRoot },
                boxStoreFactory = { box },
                markdownStoreFactory = { MarkdownEntryStore(tempRoot) },
                modelPathLoader = { File(tempRoot, "m").absolutePath },
                backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                foregroundServiceStopper = { stopRequests += 1 },
                scope = this,
            )
            val revisionBefore = container.dataRevision.value
            container.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.RUNNING)
            assertEquals(BackgroundExtractionLifecycleState.PROMOTING, container.lifecycleStateMachine.state.value)

            container.wipeAllData()
            container.reportExtractionStatus(entryId = 1L, status = ExtractionStatus.RUNNING)

            assertEquals(0L, box.boxFor(EntryEntity::class.java).count())
            assertTrue((File(tempRoot, "entries").listFiles()?.none { it.name.endsWith(".md") } ?: true))
            assertEquals(1, stopRequests)
            assertEquals(BackgroundExtractionLifecycleState.NORMAL, container.lifecycleStateMachine.state.value)
            assertTrue(container.dataRevision.value > revisionBefore)
        } finally {
            box.close()
        }
    }

    @Test
    fun `wipeAllData cancels detached extraction jobs before returning`(@TempDir tempRoot: File) = runTest {
        val boxDir = newInMemoryObjectBoxDirectory("wipe-jobs")
        val box = openInMemoryBoxStore(boxDir)
        try {
            val extractionFinished = CompletableDeferred<Unit>()
            val extractionJob = kotlinx.coroutines.Job().also { job ->
                job.invokeOnCompletion {
                    extractionFinished.complete(Unit)
                }
            }
            val saveFlow = mockk<BackgroundExtractionSaveFlow>()
            coEvery { saveFlow.saveAndExtract(any(), any(), any(), any(), any(), any(), any()) } returns
                SaveOutcome.Pending(entryId = 7L, extractionJob = extractionJob)
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { box },
                markdownStoreFactory = { MarkdownEntryStore(tempRoot) },
                modelPathLoader = { File(tempRoot, "m").absolutePath },
                backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
                mainModelArtifactStoreFactory = { _, _, _ -> fakeArtifactStore(File(tempRoot, "m"), 1L) },
                backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                foregroundServiceStopper = {},
                scope = backgroundScope,
            )

            container.saveAndExtract("wipe me", CAPTURED_AT)
            container.wipeAllData()
            advanceUntilIdle()

            assertTrue(extractionJob.isCancelled)
            assertTrue(extractionFinished.isCompleted)
        } finally {
            box.close()
        }
    }

    @Test
    fun `zipAllEntriesTo streams every entry markdown file into the zip`(@TempDir tempRoot: File) = runTest {
        File(tempRoot, "entries").mkdirs()
        File(tempRoot, "entries/one.md").writeText("first")
        File(tempRoot, "entries/two.md").writeText("second")
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true) { every { filesDir } returns tempRoot },
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { MarkdownEntryStore(tempRoot) },
            modelPathLoader = { File(tempRoot, "m").absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )
        val out = ByteArrayOutputStream()

        container.zipAllEntriesTo(out)

        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertEquals(listOf("one.md", "two.md"), names.sorted())
    }

    @Test
    fun `zipAllEntriesTo surfaces a mid-archive read failure and leaves prior entries intact`(
        @TempDir tempRoot: File,
    ) = runTest {
        val good = File(tempRoot, "good.md").apply { writeText("first") }
        // Never created — file.inputStream() throws FileNotFoundException after putNextEntry,
        // exercising the try/finally{closeEntry()} path.
        val missing = File(tempRoot, "missing.md")
        val markdownStore = mockk<MarkdownEntryStore>(relaxed = true) {
            every { listAll() } returns listOf(good, missing)
        }
        val container = AppContainer(
            applicationContext = mockk<Context>(relaxed = true) { every { filesDir } returns tempRoot },
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { markdownStore },
            modelPathLoader = { File(tempRoot, "m").absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )
        val out = ByteArrayOutputStream()

        val raised = runCatching { container.zipAllEntriesTo(out) }

        // The export is honest about failure — a failed entry read propagates, never swallowed.
        assertTrue(raised.isFailure, "a mid-archive read failure must propagate, not be swallowed")
        // closeEntry() ran in finally, so the archive stays parseable and the entry written
        // before the failure survives instead of being orphaned by a dangling open entry.
        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertTrue(names.contains("good.md"), "the entry completed before the failure must remain in the archive")
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
        every { worker.hasPendingEmbeddings() } returnsMany listOf(true, true)
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

    private fun fakeArtifactStore(artifactFile: File, expectedByteSize: Long): ModelArtifactStore {
        val manifest = ModelManifest(
            schemaVersion = ModelManifest.SUPPORTED_SCHEMA_VERSION,
            artifactRepo = "test",
            filename = artifactFile.name,
            downloadUrl = "https://example.invalid/${artifactFile.name}",
            expectedByteSize = expectedByteSize,
            sha256 = "test",
            allowedHosts = listOf("example.invalid"),
        )
        return mockk<ModelArtifactStore>(relaxed = true) {
            every { this@mockk.artifactFile } returns artifactFile
            every { this@mockk.manifest } returns manifest
            coEvery { this@mockk.probe() } answers {
                when {
                    !artifactFile.exists() -> ModelArtifactState.Absent

                    artifactFile.length() < expectedByteSize ->
                        ModelArtifactState.Partial(artifactFile.length(), expectedByteSize)

                    artifactFile.length() > expectedByteSize ->
                        ModelArtifactState.Corrupt(
                            expectedSha256 = manifest.sha256,
                            actualSha256 = "size_mismatch",
                        )

                    else -> ModelArtifactState.Complete
                }
            }
            coEvery { this@mockk.currentState() } answers {
                when {
                    !artifactFile.exists() -> ModelArtifactState.Absent

                    artifactFile.length() < expectedByteSize ->
                        ModelArtifactState.Partial(artifactFile.length(), expectedByteSize)

                    artifactFile.length() > expectedByteSize ->
                        ModelArtifactState.Corrupt(
                            expectedSha256 = manifest.sha256,
                            actualSha256 = "size_mismatch",
                        )

                    else -> ModelArtifactState.Complete
                }
            }
        }
    }

    private companion object {
        val CAPTURED_AT: ZonedDateTime = ZonedDateTime.of(2026, 5, 11, 7, 21, 24, 0, ZoneId.of("America/New_York"))
    }

    @Test
    fun `launchVectorBackfillIfReady runs cleanup-only work without probing artifact state`() = runTest {
        val modelStore = mockk<ModelArtifactStore>()
        val tokenizerStore = mockk<ModelArtifactStore>()
        val worker = mockk<VectorBackfillWorker>()
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns java.io.File("/tmp/app-files-stub")
        }
        every { worker.hasPendingWork() } returns true
        every { worker.hasPendingEmbeddings() } returns false
        coEvery { worker.backfill() } returns VectorBackfillWorker.BackfillStats(1, 0, 0, 1, skipped = 1)
        val container = AppContainer(
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

        coVerify(exactly = 1) { worker.backfill() }
        coVerify(exactly = 0) { modelStore.currentState() }
        coVerify(exactly = 0) { tokenizerStore.currentState() }
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
        every { worker.hasPendingEmbeddings() } returnsMany listOf(true, true)
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
        every { worker.hasPendingEmbeddings() } returns true
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

    @Test
    fun `modelReadinessFlow starts Loading and flips to Ready after a refresh with a present artifact`(
        @TempDir tempRoot: File,
    ) = runTest {
        val modelFile = File(tempRoot, "ready-model.litertlm").apply { writeText("x") }
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        assertEquals(
            dev.anchildress1.vestige.ui.capture.ModelReadiness.Loading,
            container.modelReadinessFlow.value,
        )
        container.refreshModelReadiness()
        advanceUntilIdle()
        assertEquals(
            dev.anchildress1.vestige.ui.capture.ModelReadiness.Ready,
            container.modelReadinessFlow.value,
        )
    }

    @Test
    fun `pre-warm fires immediately on Ready transition without a delay`(@TempDir tempRoot: File) = runTest {
        val modelFile = File(tempRoot, "main-model.litertlm").apply { writeText("xx") } // 2 bytes
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 2L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val engineMock = mockk<LiteRtLmEngine>(relaxed = true)
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engineMock },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        container.refreshModelReadiness()
        advanceUntilIdle()

        assertEquals(ModelReadiness.Ready, container.modelReadinessFlow.value)
        coVerify(exactly = 1) { engineMock.initialize() }
        assertEquals(0L, testScheduler.currentTime, "pre-warm must fire without advancing virtual time")
    }

    @Test
    fun `refreshModelReadiness skips emitting when the readiness has not changed`(@TempDir tempRoot: File) = runTest {
        val modelFile = File(tempRoot, "absent.litertlm")
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = this,
        )

        // Initial Loading + probe-Loading == same value; flow stays at Loading.
        container.refreshModelReadiness()
        assertEquals(
            dev.anchildress1.vestige.ui.capture.ModelReadiness.Loading,
            container.modelReadinessFlow.value,
        )
    }

    @Test
    fun `modelReadinessFlow stays Loading when the artifact is present but the wrong size`(@TempDir tempRoot: File) =
        runTest {
            val modelFile = File(tempRoot, "wrong-size.litertlm").apply { writeText("x") } // 1 byte
            val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 99L)
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = this,
            )

            container.refreshModelReadiness()
            advanceUntilIdle()
            // A leftover wrong-size artifact is resumable state now, not generic loading.
            assertEquals(
                dev.anchildress1.vestige.ui.capture.ModelReadiness.Paused,
                container.modelReadinessFlow.value,
            )
        }

    @Test
    fun `refreshModelReadiness flips from Ready to Loading when the artifact disappears`(@TempDir tempRoot: File) =
        runTest {
            val modelFile = File(tempRoot, "ready-then-gone.litertlm").apply { writeText("x") }
            val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = this,
            )

            container.refreshModelReadiness()
            advanceUntilIdle()
            assertEquals(
                dev.anchildress1.vestige.ui.capture.ModelReadiness.Ready,
                container.modelReadinessFlow.value,
            )
            // Re-probe with the same state — no transition, no recover sweep.
            container.refreshModelReadiness()
            advanceUntilIdle()
            assertEquals(
                dev.anchildress1.vestige.ui.capture.ModelReadiness.Ready,
                container.modelReadinessFlow.value,
            )

            // Artifact removed mid-session — readiness flips back to Loading.
            modelFile.delete()
            container.refreshModelReadiness()
            advanceUntilIdle()
            assertEquals(
                dev.anchildress1.vestige.ui.capture.ModelReadiness.Loading,
                container.modelReadinessFlow.value,
            )
        }

    @Test
    fun `recoverPendingExtractions is a no-op when the model artifact is absent`(@TempDir tempRoot: File) = runTest {
        val saveFlow = mockk<BackgroundExtractionSaveFlow>(relaxed = true)
        val modelFile = File(tempRoot, "absent.litertlm")
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { mockk<BoxStore>(relaxed = true) },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        container.recoverPendingExtractions()

        coVerify(exactly = 0) { saveFlow.recoverEntry(any(), any(), any(), any()) }
    }

    @Test
    fun `recoverPendingExtractions skips non-PENDING entries when scanning`(@TempDir tempRoot: File) = runTest {
        val dataDir = newInMemoryObjectBoxDirectory("recover-pending-skip-")
        val markdownDir = File(tempRoot, "markdown").apply { mkdirs() }
        val boxStore = openInMemoryBoxStore(dataDir)
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>(relaxed = true)
        val modelFile = File(tempRoot, "ready-model.litertlm").apply { writeText("x") }
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { boxStore },
            markdownStoreFactory = { MarkdownEntryStore(markdownDir) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engine },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        try {
            val pendingId = container.entryStore.createPendingEntry("typed-pending", CAPTURED_AT.toInstant())
            // Flip a second row to FAILED in persisted storage so the scan sees it but skips it.
            // The non-terminal scan (`findNonTerminalEntryIds`) returns both PENDING and RUNNING;
            // a row with terminal status is filtered upstream and never reaches the recovery loop.
            // The PENDING-only filter inside `recoverPendingExtractions` is the second-line guard
            // tested here — flipping to FAILED at the store level isn't enough since FAILED is
            // already excluded from `findNonTerminalEntryIds`. Instead, force one row to RUNNING
            // by hand-writing the entity.
            val runningId = container.entryStore.createPendingEntry("typed-running", CAPTURED_AT.toInstant())
            val runningEntry = container.entryStore.readEntry(runningId)!!
            runningEntry.extractionStatus = ExtractionStatus.RUNNING
            boxStore.boxFor(dev.anchildress1.vestige.storage.EntryEntity::class.java).put(runningEntry)

            container.recoverPendingExtractions()

            coVerify(exactly = 1) { saveFlow.recoverEntry(pendingId, "typed-pending", any(), any()) }
            coVerify(exactly = 0) { saveFlow.recoverEntry(runningId, any(), any(), any()) }
        } finally {
            container.close()
            cleanupObjectBoxTempRoot(tempRoot, dataDir)
        }
    }

    @Test
    fun `recoverPendingExtractions short-circuits when the non-terminal scan throws`(@TempDir tempRoot: File) =
        runTest {
            val saveFlow = mockk<BackgroundExtractionSaveFlow>(relaxed = true)
            val modelFile = File(tempRoot, "ready-model.litertlm").apply { writeText("x") }
            val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
            val throwingBoxStore = mockk<BoxStore>(relaxed = true) {
                // findNonTerminalEntryIds calls boxFor<EntryEntity>(); failing there exercises the
                // runCatching onFailure branch in recoverPendingExtractions.
                every { boxFor(any<Class<*>>()) } throws RuntimeException("simulated scan failure")
            }
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { throwingBoxStore },
                markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> mockk<LiteRtLmEngine>(relaxed = true) },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = backgroundScope,
            )

            container.recoverPendingExtractions()

            coVerify(exactly = 0) { saveFlow.recoverEntry(any(), any(), any(), any()) }
        }

    @Test
    fun `recoverPendingExtractions logs and continues when one entry's recovery throws`(@TempDir tempRoot: File) =
        runTest {
            val dataDir = newInMemoryObjectBoxDirectory("recover-pending-throws-")
            val markdownDir = File(tempRoot, "markdown").apply { mkdirs() }
            val boxStore = openInMemoryBoxStore(dataDir)
            val engine = mockk<LiteRtLmEngine>(relaxed = true)
            val saveFlow = mockk<BackgroundExtractionSaveFlow>()
            // First call throws, second succeeds — recovery loop must not bail on the first failure.
            coEvery { saveFlow.recoverEntry(any(), any(), any(), any()) } throws
                RuntimeException("simulated recovery failure") andThen
                mockk<kotlinx.coroutines.Job>(relaxed = true)
            val modelFile = File(tempRoot, "ready-model.litertlm").apply { writeText("x") }
            val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
            val context = mockk<Context>(relaxed = true) {
                every { filesDir } returns tempRoot
                every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
            }
            val container = AppContainer(
                applicationContext = context,
                boxStoreFactory = { boxStore },
                markdownStoreFactory = { MarkdownEntryStore(markdownDir) },
                modelPathLoader = { modelFile.absolutePath },
                backgroundEngineFactory = { _, _ -> engine },
                mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
                backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
                recoveredEntryIdsLoader = { emptyList() },
                foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
                foregroundServiceStarter = {},
                scope = backgroundScope,
            )

            try {
                val firstId = container.entryStore.createPendingEntry("first", CAPTURED_AT.toInstant())
                val secondId = container.entryStore.createPendingEntry("second", CAPTURED_AT.toInstant())

                container.recoverPendingExtractions()

                coVerify(exactly = 1) { saveFlow.recoverEntry(firstId, "first", any(), any()) }
                coVerify(exactly = 1) { saveFlow.recoverEntry(secondId, "second", any(), any()) }
            } finally {
                container.close()
                cleanupObjectBoxTempRoot(tempRoot, dataDir)
            }
        }

    @Test
    fun `recoverPendingExtractions re-runs the save flow for each PENDING entry`(@TempDir tempRoot: File) = runTest {
        val dataDir = newInMemoryObjectBoxDirectory("recover-pending-")
        val markdownDir = File(tempRoot, "markdown").apply { mkdirs() }
        val boxStore = openInMemoryBoxStore(dataDir)
        val engine = mockk<LiteRtLmEngine>(relaxed = true)
        val saveFlow = mockk<BackgroundExtractionSaveFlow>(relaxed = true)
        val modelFile = File(tempRoot, "ready-model.litertlm").apply { writeText("x") }
        val artifactStore = fakeArtifactStore(artifactFile = modelFile, expectedByteSize = 1L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { boxStore },
            markdownStoreFactory = { MarkdownEntryStore(markdownDir) },
            modelPathLoader = { modelFile.absolutePath },
            backgroundEngineFactory = { _, _ -> engine },
            mainModelArtifactStoreFactory = { _, _, _ -> artifactStore },
            backgroundExtractionSaveFlowFactory = { _, _, _, _, _, _ -> saveFlow },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        try {
            // Seed two PENDING entries directly through the live entry store.
            val firstId = container.entryStore.createPendingEntry("typed-1", CAPTURED_AT.toInstant())
            val secondId = container.entryStore.createPendingEntry("typed-2", CAPTURED_AT.toInstant())

            container.recoverPendingExtractions()

            coVerify(exactly = 1) { saveFlow.recoverEntry(firstId, "typed-1", any(), any()) }
            coVerify(exactly = 1) { saveFlow.recoverEntry(secondId, "typed-2", any(), any()) }
            assertEquals(0L, container.dataRevision.value)
        } finally {
            container.close()
            cleanupObjectBoxTempRoot(tempRoot, dataDir)
        }
    }

    @Test
    fun `cold start promotes an expired SNOOZED pattern back to ACTIVE`(@TempDir tempRoot: File) = runTest {
        val dataDir = newInMemoryObjectBoxDirectory("sweep-expired-skip-")
        val boxStore = openInMemoryBoxStore(dataDir)
        // Seed an expired SNOOZED row BEFORE container init — snoozedUntil far in the past so the
        // container's default system clock sees the skip window as elapsed. The init{} block
        // runs sweepExpiredSkips() during construction.
        val expiredId = seedSnoozedPattern(boxStore, "p-expired", snoozedUntil = 1_000L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { boxStore },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        try {
            val swept = container.patternStore.findByPatternId(expiredId)
            assertEquals(PatternState.ACTIVE, swept?.state)
            assertEquals(null, swept?.snoozedUntil)
        } finally {
            container.close()
            cleanupObjectBoxTempRoot(tempRoot, dataDir)
        }
    }

    @Test
    fun `sweepExpiredSkips is idempotent and safe to call repeatedly`(@TempDir tempRoot: File) = runTest {
        // Fault injection into the live PatternStore.promoteExpiredSkips() isn't reachable through
        // the AppContainer harness without a fake store factory (none exists; patternStore is a
        // private lazy over the real boxStore). Idempotency + no-throw is the feasible coverage:
        // the second sweep must not re-promote (the row is already ACTIVE) and must not throw.
        val dataDir = newInMemoryObjectBoxDirectory("sweep-idempotent-")
        val boxStore = openInMemoryBoxStore(dataDir)
        val expiredId = seedSnoozedPattern(boxStore, "p-idempotent", snoozedUntil = 1_000L)
        val context = mockk<Context>(relaxed = true) {
            every { filesDir } returns tempRoot
            every { cacheDir } returns File(tempRoot, "cache").apply { mkdirs() }
        }
        val container = AppContainer(
            applicationContext = context,
            boxStoreFactory = { boxStore },
            markdownStoreFactory = { mockk<MarkdownEntryStore>(relaxed = true) },
            recoveredEntryIdsLoader = { emptyList() },
            foregroundServiceIntentFactory = { Intent("dev.anchildress1.vestige.TEST_START") },
            foregroundServiceStarter = {},
            scope = backgroundScope,
        )

        try {
            // init{} already swept once → ACTIVE. Two more explicit sweeps must be no-op + no-throw.
            container.sweepExpiredSkips()
            container.sweepExpiredSkips()
            val row = container.patternStore.findByPatternId(expiredId)
            assertEquals(PatternState.ACTIVE, row?.state)
            assertEquals(null, row?.snoozedUntil)
        } finally {
            container.close()
            cleanupObjectBoxTempRoot(tempRoot, dataDir)
        }
    }

    private fun seedSnoozedPattern(boxStore: BoxStore, patternId: String, snoozedUntil: Long): String {
        val entity = PatternEntity(
            patternId = patternId,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = "{}",
            title = "Title $patternId",
            firstSeenTimestamp = 1L,
            lastSeenTimestamp = 1L,
            state = PatternState.SNOOZED,
            snoozedUntil = snoozedUntil,
            stateChangedTimestamp = 1L,
        )
        boxStore.boxFor(PatternEntity::class.java).put(entity)
        return patternId
    }
}
