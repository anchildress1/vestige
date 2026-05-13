package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.Embedder
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.GemmaTextEmbedder
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.ObservationGenerator
import dev.anchildress1.vestige.inference.PatternTitleGenerator
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleStateMachine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionService
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionStatusBus
import dev.anchildress1.vestige.model.ArtifactHttpClient
import dev.anchildress1.vestige.model.DefaultModelArtifactStore
import dev.anchildress1.vestige.model.DefaultNetworkGate
import dev.anchildress1.vestige.model.EmbeddingArtifactManifest
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore
import dev.anchildress1.vestige.model.ModelManifest
import dev.anchildress1.vestige.model.NetworkGate
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator
import dev.anchildress1.vestige.save.BackgroundExtractionSaveFlow
import dev.anchildress1.vestige.save.SaveOutcome
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.VectorBackfillWorker
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Process-singleton hub for Phase-2 cross-cutting concerns. */
@Suppress(
    "LongParameterList", // Constructor-injection seams: factories + lifecycle + scope.
    "TooManyFunctions", // DI hub aggregates capture, save, lifecycle, and backfill orchestration.
)
class AppContainer(
    private val applicationContext: Context,
    boxStoreFactory: (Context) -> BoxStore = { ctx -> VestigeBoxStore.open(ctx) },
    markdownStoreFactory: (Context) -> MarkdownEntryStore = { ctx -> MarkdownEntryStore(ctx.filesDir) },
    private val modelPathLoader: (Context) -> String = { ctx ->
        val manifest = ModelManifest.loadDefault()
        File(File(ctx.filesDir, MODEL_ARTIFACTS_SUBDIR), manifest.filename).absolutePath
    },
    private val embeddingArtifactManifestLoader: () -> EmbeddingArtifactManifest =
        EmbeddingArtifactManifest::loadDefault,
    private val backgroundEngineFactory: (String, String) -> LiteRtLmEngine = { modelPath, cacheDir ->
        LiteRtLmEngine(modelPath = modelPath, cacheDir = cacheDir)
    },
    private val networkGateFactory: () -> NetworkGate = { DefaultNetworkGate() },
    private val embeddingModelArtifactStoreFactory: (
        EmbeddingArtifactManifest,
        File,
        NetworkGate,
    ) -> ModelArtifactStore =
        { manifest, baseDir, networkGate ->
            DefaultModelArtifactStore(
                manifest = manifest.modelArtifactManifest(),
                baseDir = baseDir,
                httpClient = ArtifactHttpClient(manifest.allowedHosts, networkGate),
            )
        },
    private val embeddingTokenizerArtifactStoreFactory: (
        EmbeddingArtifactManifest,
        File,
        NetworkGate,
    ) -> ModelArtifactStore =
        { manifest, baseDir, networkGate ->
            DefaultModelArtifactStore(
                manifest = manifest.tokenizerArtifactManifest(),
                baseDir = baseDir,
                httpClient = ArtifactHttpClient(manifest.allowedHosts, networkGate),
            )
        },
    private val embedderFactory: (String, String) -> Embedder = { modelPath, tokenizerPath ->
        GemmaTextEmbedder(modelPath = modelPath, tokenizerPath = tokenizerPath)
    },
    private val vectorBackfillWorkerFactory: (
        BoxStore,
        suspend (String) -> FloatArray,
    ) -> VectorBackfillWorker = { store, embedEntryText ->
        VectorBackfillWorker(store, embedEntryText)
    },
    private val backgroundExtractionSaveFlowFactory: (
        EntryStore,
        BackgroundExtractionWorker,
        ObservationGenerator,
        (Long) -> ExtractionStatusListener,
        CoroutineScope,
        PatternDetectionOrchestrator?,
    ) -> BackgroundExtractionSaveFlow =
        { entryStore, worker, observationGenerator, listenerFactory, extractionScope, orchestrator ->
            BackgroundExtractionSaveFlow(
                entryStore = entryStore,
                worker = worker,
                observationGenerator = observationGenerator,
                listenerFactory = listenerFactory,
                scope = extractionScope,
                patternOrchestrator = orchestrator,
            )
        },
    // `null` triggers the live `VestigeBoxStore.findNonTerminalEntryIds(boxStore)` cold-start
    // sweep; tests inject a fixed seed to stay BoxStore-free.
    private val recoveredEntryIdsLoader: (() -> Collection<Long>)? = null,
    private val foregroundServiceIntentFactory: () -> Intent = {
        Intent(applicationContext, BackgroundExtractionService::class.java)
    },
    private val foregroundServiceStarter: (Intent) -> Unit = { intent ->
        applicationContext.startForegroundService(intent)
    },
    private val vectorBackfillRetryDelayMs: Long = VECTOR_BACKFILL_RETRY_DELAY_MS,
    private val vectorBackfillMaxRetries: Int = VECTOR_BACKFILL_MAX_RETRIES,
    private val vectorBackfillScheduleListener: (() -> Unit)? = null,
    private val scope: CoroutineScope = defaultScope(),
) {

    /** Shared ObjectBox handle. Closed when the process dies; tests use [close]. */
    val boxStore: BoxStore = boxStoreFactory(applicationContext)

    val entryStore: EntryStore = EntryStore(boxStore, markdownStoreFactory(applicationContext))
    private val backgroundEngineInitMutex = Mutex()
    private val embedderInitMutex = Mutex()
    private val vectorBackfillMutex = Mutex()
    private val vectorBackfillRunning = AtomicBoolean(false)
    private val vectorBackfillRequested = AtomicBoolean(false)

    @Volatile
    private var backgroundEngineInitialized = false

    @Volatile
    private var embedderInstance: Embedder? = null

    private val networkGate: NetworkGate = networkGateFactory()
    private val mainModelManifest: ModelManifest by lazy(ModelManifest::loadDefault)
    private val embeddingArtifactsDir: File by lazy { File(applicationContext.filesDir, MODEL_ARTIFACTS_SUBDIR) }
    private val embeddingArtifactManifest: EmbeddingArtifactManifest by lazy(embeddingArtifactManifestLoader)

    private val backgroundEngineDelegate = lazy {
        backgroundEngineFactory(
            modelPathLoader(applicationContext),
            applicationContext.cacheDir.absolutePath,
        )
    }
    val backgroundEngine: LiteRtLmEngine by backgroundEngineDelegate

    val mainModelArtifactStore: ModelArtifactStore by lazy {
        DefaultModelArtifactStore(
            manifest = mainModelManifest,
            baseDir = File(applicationContext.filesDir, MODEL_ARTIFACTS_SUBDIR),
            httpClient = ArtifactHttpClient(mainModelManifest.allowedHosts, networkGate),
        )
    }

    val embeddingModelArtifactStore: ModelArtifactStore by lazy {
        embeddingModelArtifactStoreFactory(
            embeddingArtifactManifest,
            embeddingArtifactsDir,
            networkGate,
        )
    }

    val embeddingTokenizerArtifactStore: ModelArtifactStore by lazy {
        embeddingTokenizerArtifactStoreFactory(
            embeddingArtifactManifest,
            embeddingArtifactsDir,
            networkGate,
        )
    }

    val backgroundExtractionWorker: BackgroundExtractionWorker by lazy {
        BackgroundExtractionWorker(
            engine = backgroundEngine,
            resolver = DefaultConvergenceResolver(),
        )
    }

    val observationGenerator: ObservationGenerator by lazy {
        ObservationGenerator(engine = backgroundEngine)
    }

    val patternStore: PatternStore by lazy { PatternStore(boxStore) }
    val patternRepo: PatternRepo by lazy { PatternRepo(patternStore) }
    val calloutCooldownStore: CalloutCooldownStore by lazy { CalloutCooldownStore(boxStore) }

    private val patternDetector: PatternDetector by lazy { PatternDetector(boxStore) }
    private val patternTitleGenerator: PatternTitleGenerator by lazy {
        PatternTitleGenerator(engine = backgroundEngine)
    }

    val patternDetectionOrchestrator: PatternDetectionOrchestrator by lazy {
        PatternDetectionOrchestrator(
            boxStore = boxStore,
            detector = patternDetector,
            patternStore = patternStore,
            titleGenerator = patternTitleGenerator,
            cooldownStore = calloutCooldownStore,
        )
    }

    val backgroundExtractionSaveFlow: BackgroundExtractionSaveFlow by lazy {
        backgroundExtractionSaveFlowFactory(
            entryStore,
            backgroundExtractionWorker,
            observationGenerator,
            ::extractionStatusListener,
            scope,
            patternDetectionOrchestrator,
        )
    }

    private val statusBus: BackgroundExtractionStatusBus = BackgroundExtractionStatusBus()

    val lifecycleStateMachine: BackgroundExtractionLifecycleStateMachine =
        BackgroundExtractionLifecycleStateMachine(
            scope = scope,
            onPromoteRequested = ::dispatchStartForegroundService,
        )

    init {
        seedRecoveredExtractions()
        // A pending callout reservation is in-flight by definition. Process death between
        // `tryReserveCallout` and `settleReservedCallout` would otherwise survive into the next
        // launch and wedge every future save with `BLOCKED_BY_PENDING_RESERVATION`.
        calloutCooldownStore.clearStalePendingReservation()
    }

    fun reportExtractionStatus(entryId: Long, status: ExtractionStatus) {
        statusBus.report(entryId, status)
        lifecycleStateMachine.onInFlightCountChange(statusBus.inFlightCount.value)
    }

    fun extractionStatusListener(entryId: Long): ExtractionStatusListener = ExtractionStatusListener { status, _, _ ->
        reportExtractionStatus(entryId, status)
    }

    /**
     * Two-tier per ADR-002: persists the pending entry, returns [SaveOutcome.Pending] immediately,
     * and dispatches the detached 3-lens extraction on the container scope. UI callers must not
     * await the embedded `extractionJob` on the main thread — subscribe to
     * `BackgroundExtractionStatusBus` for terminal status instead.
     */
    suspend fun saveAndExtract(
        entryText: String,
        capturedAt: ZonedDateTime,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        timeoutMs: Long? = null,
        persona: Persona = Persona.WITNESS,
    ): SaveOutcome.Pending {
        ensureBackgroundEngineInitialized()
        val outcome = backgroundExtractionSaveFlow.saveAndExtract(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = retrievedHistory,
            timeoutMs = timeoutMs,
            persona = persona,
        )
        launchVectorBackfillIfReady()
        return outcome
    }

    internal suspend fun ensureBackgroundEngineInitialized() {
        if (backgroundEngineInitialized) return
        backgroundEngineInitMutex.withLock {
            if (backgroundEngineInitialized) return
            backgroundEngine.initialize()
            backgroundEngineInitialized = true
        }
    }

    suspend fun requireEmbedder(): Embedder {
        embedderInstance?.let { return it }
        return embedderInitMutex.withLock {
            val cached = embedderInstance
            if (cached != null) {
                cached
            } else {
                val modelFile = embeddingModelArtifactStore.requireComplete()
                val tokenizerFile = embeddingTokenizerArtifactStore.requireComplete()
                val embedder = embedderFactory(modelFile.absolutePath, tokenizerFile.absolutePath)
                embedderInstance = embedder
                embedder
            }
        }
    }

    /**
     * Launch or re-request vector backfill on the container scope. If backlog exists before the
     * embedding artifacts finish downloading, the runner sleeps briefly and retries in-process
     * instead of waiting for a cold restart. Caller doesn't await; results land in logcat under
     * tag `VectorBackfill`.
     */
    fun launchVectorBackfillIfReady() {
        vectorBackfillRequested.set(true)
        if (!vectorBackfillRunning.compareAndSet(false, true)) return
        vectorBackfillScheduleListener?.invoke()
        launchVectorBackfillRunner()
    }

    private fun launchVectorBackfillRunner() {
        require(vectorBackfillMaxRetries >= 0) {
            "vectorBackfillMaxRetries must be >= 0 (got $vectorBackfillMaxRetries)"
        }
        scope.launch {
            try {
                drainVectorBackfillCycles()
            } finally {
                relaunchVectorBackfillIfRequested()
            }
        }
    }

    private suspend fun drainVectorBackfillCycles() {
        var retryCount = 0
        while (true) {
            when (runVectorBackfillCycle()) {
                VectorBackfillOutcome.IDLE, VectorBackfillOutcome.COMPLETE -> retryCount = 0

                VectorBackfillOutcome.RETRY_LATER -> {
                    if (retryCount >= vectorBackfillMaxRetries) {
                        Log.e(
                            TAG,
                            "Vector backfill abandoned after $vectorBackfillMaxRetries retries; " +
                                "a future save or cold start will retrigger it.",
                        )
                        return
                    }
                    retryCount += 1
                    delay(vectorBackfillRetryDelayMs)
                    vectorBackfillRequested.set(true)
                }
            }
            if (!vectorBackfillRequested.get()) return
        }
    }

    private suspend fun runVectorBackfillCycle(): VectorBackfillOutcome {
        vectorBackfillRequested.set(false)
        return vectorBackfillMutex.withLock {
            val worker = vectorBackfillWorkerFactory(boxStore) { text -> requireEmbedder().embed(text) }
            // Cheap presence-only check before paying for SHA-256 artifact verification —
            // most cold starts and steady-state save completions have nothing to backfill.
            if (!worker.hasPendingWork()) return@withLock VectorBackfillOutcome.IDLE

            val modelState = embeddingModelArtifactStore.currentState()
            val tokenizerState = embeddingTokenizerArtifactStore.currentState()
            if (modelState !is ModelArtifactState.Complete ||
                tokenizerState !is ModelArtifactState.Complete
            ) {
                Log.i(TAG, "Vector backfill delayed — embedding artifacts not yet complete")
                return@withLock VectorBackfillOutcome.RETRY_LATER
            }
            worker.backfill()
            VectorBackfillOutcome.COMPLETE
        }
    }

    // A trigger can land after `drain` decides it's done but before `running` flips false.
    // Re-check + CAS-relaunch here so the wakeup is not lost.
    private fun relaunchVectorBackfillIfRequested() {
        vectorBackfillRunning.set(false)
        if (vectorBackfillRequested.get() && vectorBackfillRunning.compareAndSet(false, true)) {
            launchVectorBackfillRunner()
        }
    }

    private fun seedRecoveredExtractions() {
        val ids = recoveredEntryIdsLoader?.invoke()
            ?: VestigeBoxStore.findNonTerminalEntryIds(boxStore)
        statusBus.seedFromColdStart(ids.toList())
        lifecycleStateMachine.onInFlightCountChange(statusBus.inFlightCount.value)
    }

    /** Tests own the lifecycle and close the container when done. Production rides the process. */
    fun close() {
        if (backgroundEngineDelegate.isInitialized()) {
            backgroundEngine.close()
        }
        boxStore.close()
    }

    private fun dispatchStartForegroundService() {
        val intent = foregroundServiceIntentFactory()
        try {
            foregroundServiceStarter(intent)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Background-launch restrictions (Android 12+), battery-saver, or app-standby buckets
            // can refuse the start. Reset the machine so its bounded retry path can re-attempt
            // promotion while the current extraction is still in flight.
            Log.e(TAG, "startForegroundService rejected", error)
            lifecycleStateMachine.onForegroundStartFailed()
        }
    }

    private companion object {
        const val TAG = "VestigeAppContainer"
        const val MODEL_ARTIFACTS_SUBDIR = "models"
        const val VECTOR_BACKFILL_RETRY_DELAY_MS = 5_000L
        const val VECTOR_BACKFILL_MAX_RETRIES = 12

        fun defaultScope(): CoroutineScope {
            val exceptionHandler = CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "AppContainer scope coroutine failed", error)
            }
            return CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        }
    }

    private enum class VectorBackfillOutcome {
        IDLE,
        COMPLETE,
        RETRY_LATER,
    }
}
