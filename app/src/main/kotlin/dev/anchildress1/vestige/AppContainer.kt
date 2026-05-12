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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.ZonedDateTime

/** Process-singleton hub for Phase-2 cross-cutting concerns. */
@Suppress("LongParameterList") // Constructor-injection seams: factories + lifecycle + scope.
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
    private val backgroundExtractionSaveFlowFactory: (
        EntryStore,
        BackgroundExtractionWorker,
        ObservationGenerator,
        (Long) -> ExtractionStatusListener,
        PatternDetectionOrchestrator?,
    ) -> BackgroundExtractionSaveFlow =
        { entryStore, worker, observationGenerator, listenerFactory, orchestrator ->
            BackgroundExtractionSaveFlow(
                entryStore = entryStore,
                worker = worker,
                observationGenerator = observationGenerator,
                listenerFactory = listenerFactory,
                patternOrchestrator = orchestrator,
            )
        },
    // Cold-start sweep — `null` means the live `VestigeBoxStore.findNonTerminalEntryIds(boxStore)`
    // query (production default per ADR-006 §"Action Item #4"). Tests inject a fixed seed to keep
    // them BoxStore-free.
    private val recoveredEntryIdsLoader: (() -> Collection<Long>)? = null,
    private val foregroundServiceIntentFactory: () -> Intent = {
        Intent(applicationContext, BackgroundExtractionService::class.java)
    },
    private val foregroundServiceStarter: (Intent) -> Unit = { intent ->
        applicationContext.startForegroundService(intent)
    },
    private val vectorBackfillScheduleListener: (() -> Unit)? = null,
    private val scope: CoroutineScope = defaultScope(),
) {

    /** Shared ObjectBox handle. Closed when the process dies; tests use [close]. */
    val boxStore: BoxStore = boxStoreFactory(applicationContext)

    val entryStore: EntryStore = EntryStore(boxStore, markdownStoreFactory(applicationContext))
    private val backgroundEngineInitMutex = Mutex()
    private val embedderInitMutex = Mutex()
    private val vectorBackfillMutex = Mutex()
    private val vectorBackfillInflight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var backgroundEngineInitialized = false

    @Volatile
    private var embedderInstance: Embedder? = null

    private val networkGate: NetworkGate = networkGateFactory()
    private val embeddingArtifactsDir: File by lazy { File(applicationContext.filesDir, MODEL_ARTIFACTS_SUBDIR) }
    private val embeddingArtifactManifest: EmbeddingArtifactManifest by lazy(embeddingArtifactManifestLoader)

    // Story 2.12's production DI surface. `saveAndExtract(...)` below is the app-owned entrypoint
    // that closes the loop; capture/UI code calls AppContainer, not the save flow directly.
    private val backgroundEngineDelegate = lazy {
        backgroundEngineFactory(
            modelPathLoader(applicationContext),
            applicationContext.cacheDir.absolutePath,
        )
    }
    val backgroundEngine: LiteRtLmEngine by backgroundEngineDelegate

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

    suspend fun saveAndExtract(
        entryText: String,
        capturedAt: ZonedDateTime,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        timeoutMs: Long? = null,
        persona: Persona = Persona.WITNESS,
    ): SaveOutcome {
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
     * Launch the one-time vector backfill on the container scope. No-op if either embedding
     * artifact is still pending download — the next cold start retries. Caller doesn't await;
     * results land in logcat under tag `VectorBackfill`.
     */
    fun launchVectorBackfillIfReady() {
        // CAS guard: if another launch already scheduled / running, drop this one. Prevents the
        // save-time trigger from queueing N coroutines on the mutex when N saves arrive fast.
        if (!vectorBackfillInflight.compareAndSet(false, true)) return
        vectorBackfillScheduleListener?.invoke()
        scope.launch {
            try {
                vectorBackfillMutex.withLock {
                    val worker = VectorBackfillWorker(boxStore) { text -> requireEmbedder().embed(text) }
                    // Cheap presence-only check before paying for SHA-256 artifact verification —
                    // most cold starts and save completions have nothing to backfill.
                    if (!worker.hasPendingWork()) return@withLock

                    val modelState = embeddingModelArtifactStore.currentState()
                    val tokenizerState = embeddingTokenizerArtifactStore.currentState()
                    if (modelState !is ModelArtifactState.Complete ||
                        tokenizerState !is ModelArtifactState.Complete
                    ) {
                        Log.i(TAG, "Vector backfill skipped — embedding artifacts not yet complete")
                        return@withLock
                    }
                    worker.backfill()
                }
            } finally {
                vectorBackfillInflight.set(false)
            }
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

        fun defaultScope(): CoroutineScope {
            val exceptionHandler = CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "AppContainer scope coroutine failed", error)
            }
            return CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        }
    }
}
