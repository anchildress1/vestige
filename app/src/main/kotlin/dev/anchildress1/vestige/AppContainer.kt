package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.LiteRtLmEngine
import dev.anchildress1.vestige.inference.ObservationGenerator
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleStateMachine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionService
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionStatusBus
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ModelManifest
import dev.anchildress1.vestige.save.BackgroundExtractionSaveFlow
import dev.anchildress1.vestige.save.SaveOutcome
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val backgroundEngineFactory: (String, String) -> LiteRtLmEngine = { modelPath, cacheDir ->
        LiteRtLmEngine(modelPath = modelPath, cacheDir = cacheDir)
    },
    private val backgroundExtractionSaveFlowFactory: (
        EntryStore,
        BackgroundExtractionWorker,
        ObservationGenerator,
        (Long) -> ExtractionStatusListener,
    ) -> BackgroundExtractionSaveFlow =
        { entryStore, worker, observationGenerator, listenerFactory ->
            BackgroundExtractionSaveFlow(
                entryStore = entryStore,
                worker = worker,
                observationGenerator = observationGenerator,
                listenerFactory = listenerFactory,
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
    private val scope: CoroutineScope = defaultScope(),
) {

    /** Shared ObjectBox handle. Closed when the process dies; tests use [close]. */
    val boxStore: BoxStore = boxStoreFactory(applicationContext)

    val entryStore: EntryStore = EntryStore(boxStore, markdownStoreFactory(applicationContext))
    private val backgroundEngineInitMutex = Mutex()

    @Volatile
    private var backgroundEngineInitialized = false

    // Story 2.12's production DI surface. `saveAndExtract(...)` below is the app-owned entrypoint
    // that closes the loop; capture/UI code calls AppContainer, not the save flow directly.
    private val backgroundEngineDelegate = lazy {
        backgroundEngineFactory(
            modelPathLoader(applicationContext),
            applicationContext.cacheDir.absolutePath,
        )
    }
    val backgroundEngine: LiteRtLmEngine by backgroundEngineDelegate

    val backgroundExtractionWorker: BackgroundExtractionWorker by lazy {
        BackgroundExtractionWorker(
            engine = backgroundEngine,
            resolver = DefaultConvergenceResolver(),
        )
    }

    val observationGenerator: ObservationGenerator by lazy {
        ObservationGenerator(engine = backgroundEngine)
    }

    val backgroundExtractionSaveFlow: BackgroundExtractionSaveFlow by lazy {
        backgroundExtractionSaveFlowFactory(
            entryStore,
            backgroundExtractionWorker,
            observationGenerator,
            ::extractionStatusListener,
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
    ): SaveOutcome {
        ensureBackgroundEngineInitialized()
        return backgroundExtractionSaveFlow.saveAndExtract(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = retrievedHistory,
            timeoutMs = timeoutMs,
        )
    }

    internal suspend fun ensureBackgroundEngineInitialized() {
        if (backgroundEngineInitialized) return
        backgroundEngineInitMutex.withLock {
            if (backgroundEngineInitialized) return
            backgroundEngine.initialize()
            backgroundEngineInitialized = true
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
