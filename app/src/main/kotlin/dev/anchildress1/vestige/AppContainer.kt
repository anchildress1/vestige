package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.inference.AudioChunk
import dev.anchildress1.vestige.inference.BackendChoice
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.DefaultConvergenceResolver
import dev.anchildress1.vestige.inference.Embedder
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.ForegroundInference
import dev.anchildress1.vestige.inference.ForegroundResult
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
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.CalloutCooldownStore
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternDetector
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternRepo
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.storage.VectorBackfillWorker
import dev.anchildress1.vestige.storage.VestigeBoxStore
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    // `audioBackend = Cpu` is non-negotiable for the foreground voice path — without it the
    // engine accepts a `Content.AudioFile` handoff and immediately SIGSEGVs in `mel_filterbank.cc`
    // because no audio backend was attached at EngineConfig time. The reference STT-A test
    // (`SttAAudioPlumbingTest`) enables the same backend; production must match.
    private val backgroundEngineFactory: (String, String) -> LiteRtLmEngine = { modelPath, cacheDir ->
        LiteRtLmEngine(
            modelPath = modelPath,
            backend = BackendChoice.Gpu,
            // GPU audio path SIGSEGVs in mel_filterbank.cc — see LiteRT-LM/issues/2056
            audioBackend = BackendChoice.Cpu,
            cacheDir = cacheDir,
        )
    },
    private val networkGateFactory: () -> NetworkGate = { DefaultNetworkGate() },
    private val mainModelArtifactStoreFactory: (
        ModelManifest,
        File,
        NetworkGate,
    ) -> ModelArtifactStore =
        { manifest, baseDir, networkGate ->
            DefaultModelArtifactStore(
                manifest = manifest,
                baseDir = baseDir,
                httpClient = ArtifactHttpClient(manifest.allowedHosts, networkGate),
            )
        },
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
        {
                entryStore,
                worker,
                observationGenerator,
                listenerFactory,
                extractionScope,
                orchestrator,
            ->
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
    private val foregroundServiceStopper: (Intent) -> Unit = { intent ->
        applicationContext.stopService(intent)
    },
    private val vectorBackfillRetryDelayMs: Long = VECTOR_BACKFILL_RETRY_DELAY_MS,
    private val vectorBackfillMaxRetries: Int = VECTOR_BACKFILL_MAX_RETRIES,
    private val vectorBackfillScheduleListener: (() -> Unit)? = null,
    private val scope: CoroutineScope = defaultScope(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Shared ObjectBox handle. Closed when the process dies; tests use [close]. */
    val boxStore: BoxStore = boxStoreFactory(applicationContext)

    private val markdownStore: MarkdownEntryStore = markdownStoreFactory(applicationContext)

    val entryStore: EntryStore = EntryStore(boxStore, markdownStore)
    private val backgroundEngineInitMutex = Mutex()
    private val embedderInitMutex = Mutex()
    private val modelMutationMutex = Mutex()
    private val readinessRefreshMutex = Mutex()
    private val trackedExtractionJobsMutex = Mutex()
    private val vectorBackfillMutex = Mutex()
    private val vectorBackfillRunning = AtomicBoolean(false)
    private val vectorBackfillRequested = AtomicBoolean(false)
    private val trackedExtractionJobs: MutableSet<Job> = linkedSetOf()

    @Volatile
    private var backgroundEngineInitialized = false

    @Volatile
    private var embedderInstance: Embedder? = null

    val networkGate: NetworkGate = networkGateFactory()
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
        // Derive baseDir from the same `modelPathLoader` the engine uses so tests that override
        // the loader to point at a fixture file get the artifact store reading the same path —
        // otherwise the readiness gate can disagree with the engine about where the model lives.
        val modelFile = File(modelPathLoader(applicationContext))
        val baseDir = modelFile.parentFile ?: File(applicationContext.filesDir, MODEL_ARTIFACTS_SUBDIR)
        mainModelArtifactStoreFactory(mainModelManifest, baseDir, networkGate)
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

    /**
     * Single-turn foreground inference path consumed by the capture screen. Shares the engine
     * handle with background extraction — LiteRT-LM is single-threaded, so v1 sequences the
     * foreground call ahead of any background pass against the same engine. A second recording
     * launched while a prior `saveAndExtract` background job is still running will block until
     * the engine handle frees up; that's the documented v1 trade-off per ADR-002.
     */
    val foregroundInference: ForegroundInference by lazy {
        ForegroundInference(
            engine = backgroundEngine,
            cacheDir = applicationContext.cacheDir,
        )
    }

    /**
     * Two-tier-aware adapter for the capture screen's voice path. Ensures the engine is
     * initialized before the call so the screen doesn't have to thread an init step into its
     * recording lifecycle.
     */
    suspend fun runForegroundCall(audio: AudioChunk, persona: Persona): ForegroundResult {
        ensureBackgroundEngineInitialized()
        return foregroundInference.runForegroundCall(audio, persona)
    }

    /**
     * Typed-entry foreground call — same engine + parser as the voice path so a typed entry
     * reviews identically. The model is required; the capture screen gates on readiness.
     */
    suspend fun runForegroundTextCall(text: String, persona: Persona): ForegroundResult {
        ensureBackgroundEngineInitialized()
        return foregroundInference.runForegroundTextCall(text, persona)
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

    // Observable host state — host collects these so MainActivity / CaptureRoute / IdleLayout
    // re-derive scoreboard stats and model-readiness chrome whenever the underlying state
    // changes, instead of snapshotting once at composition. Initial value is `Loading`; the
    // host calls `refreshModelReadiness()` from `LifecycleEventEffect(ON_RESUME)` to seed the
    // first real probe — avoids eagerly triggering the `mainModelArtifactStore` lazy delegate
    // during AppContainer construction (the lazy-init isn't safe to fire from arbitrary call
    // sites; it's keyed on `modelPathLoader(applicationContext)` which production callers
    // gate to a real `filesDir`).
    private val _modelReadinessFlow: MutableStateFlow<ModelReadiness> =
        MutableStateFlow(ModelReadiness.Loading)
    val modelReadinessFlow: StateFlow<ModelReadiness> = _modelReadinessFlow.asStateFlow()

    private val _dataRevision: MutableStateFlow<Long> = MutableStateFlow(0L)
    val dataRevision: StateFlow<Long> = _dataRevision.asStateFlow()

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
        // Cold-start skip wake-up runs before any Pattern surface composes, so an expired
        // skip never flashes under SKIPPED on the first frame (spec §"Open Questions" Q2).
        sweepExpiredSkips()
    }

    /**
     * Promote every `SNOOZED` pattern whose skip window has elapsed back to `ACTIVE` per
     * `spec-pattern-action-buttons.md` §P0.5. Cheap indexed query + a few puts — a simple date
     * check on load, not a WorkManager job. Also re-run on `ON_RESUME` for windows that elapse
     * while the app is backgrounded.
     */
    fun sweepExpiredSkips() {
        runCatching { patternStore.promoteExpiredSkips() }
            .onSuccess { promoted ->
                if (promoted.isNotEmpty()) {
                    _dataRevision.value += 1
                    Log.i(TAG, "Skip wake-up promoted ${promoted.size} pattern(s)")
                }
            }
            // Self-healing: the next ON_RESUME re-runs the sweep, so a transient failure here
            // is a warning, not an error — keeping it error-tier would devalue real alerts.
            .onFailure { Log.w(TAG, "Skip wake-up sweep failed", it) }
    }

    fun reportExtractionStatus(entryId: Long, status: ExtractionStatus) {
        if (!status.isTerminal() && entryStore.readEntry(entryId) == null) {
            Log.w(TAG, "Ignoring stale in-flight extraction status for missing entryId=$entryId")
            return
        }
        statusBus.report(entryId, status)
        lifecycleStateMachine.onInFlightCountChange(statusBus.inFlightCount.value)
    }

    fun extractionStatusListener(entryId: Long): ExtractionStatusListener = ExtractionStatusListener { status, _, _ ->
        reportExtractionStatus(entryId, status)
        if (status.isTerminal()) {
            _dataRevision.value += 1
        }
        if (status == ExtractionStatus.COMPLETED) {
            // The save-time sweep skipped this row while it was still PENDING. Its distilled
            // fields (tags / observations / commitment) now exist, so re-trigger backfill to
            // embed it — otherwise its vector wouldn't land until the next save or cold start.
            launchVectorBackfillIfReady()
        }
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
        durationMs: Long = 0L,
        followUpText: String? = null,
    ): SaveOutcome.Pending {
        ensureBackgroundEngineInitialized()
        val outcome = backgroundExtractionSaveFlow.saveAndExtract(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = retrievedHistory,
            timeoutMs = timeoutMs,
            persona = persona,
            durationMs = durationMs,
            followUpText = followUpText,
        )
        trackExtractionJob(outcome.extractionJob)
        launchVectorBackfillIfReady()
        return outcome
    }

    /**
     * Re-probes the model artifact and emits the latest [ModelReadiness] to [modelReadinessFlow].
     * Host calls on lifecycle ON_RESUME (or when an action might have changed model state — e.g.
     * Settings → Delete model, or a Phase-4 download-complete event). If the new readiness is
     * `Ready`, also kicks the PENDING-extraction recovery sweep so typed entries persisted while
     * the model was absent get extracted now.
     */
    fun refreshModelReadiness() {
        scope.launch {
            // Serialize probe→compare→set so concurrent callers (lifecycle resume + a model
            // action) can't interleave and let an older probe overwrite a newer readiness.
            // Each caller queues and re-probes after the prior completes (Codex review #4).
            readinessRefreshMutex.withLock {
                val previous = _modelReadinessFlow.value
                val current = probeModelReadiness(previous)
                if (previous == current) return@withLock
                _modelReadinessFlow.value = current
                if (current is ModelReadiness.Ready && previous !is ModelReadiness.Ready) {
                    scope.launch { recoverPendingExtractions() }
                    scope.launch { ensureBackgroundEngineInitialized() }
                }
            }
        }
    }

    /** Expected on-disk size of the Gemma artifact — drives the Model Status detail line. */
    val mainModelExpectedByteSize: Long get() = mainModelManifest.expectedByteSize

    /**
     * Delete the on-disk Gemma artifact (and any resumable `.part`), then re-probe readiness.
     * Entries are untouched — only the model file goes; the app falls back to `Loading` until a
     * re-download lands. Per `ux-copy.md` §"Destructive Confirmations / Delete model".
     */
    fun deleteMainModel() {
        scope.launch {
            runMainModelMutation(name = "delete model") {
                cancelTrackedExtractionsAndResetLifecycle()
                resetBackgroundEngine()
                val artifact = mainModelArtifactStore.artifactFile
                if (!artifact.delete()) Log.w(TAG, "Failed to delete model artifact")
                if (!File(
                        artifact.parentFile,
                        "${artifact.name}.part",
                    ).delete()
                ) {
                    Log.w(TAG, "Failed to delete model part file")
                }
                Log.i(TAG, "Main model artifact deleted on user request")
                refreshModelReadiness()
            }
        }
    }

    /**
     * Replace the on-disk artifact with a fresh pull: wipe the current file so this is a true
     * re-download (not a `.part` resume), open the [NetworkGate] for the transfer only, and tick
     * [modelReadinessFlow] `Downloading(percent)` so the AppTop pill + Model Status screen track
     * it from one source. Per `ux-copy.md` §"Destructive Confirmations / Re-download model".
     */
    fun redownloadMainModel() {
        scope.launch {
            runMainModelMutation(name = "re-download model") {
                cancelTrackedExtractionsAndResetLifecycle()
                resetBackgroundEngine()
                val store = mainModelArtifactStore
                deleteArtifactFiles(store.artifactFile)
                _modelReadinessFlow.value = ModelReadiness.Downloading(0)
                networkGate.openForDownload(reason = "Model Status — user-requested re-download")
                val result = runDownload(store)
                // Honor the terminal result (Codex review #1/#3). The size-only probe would read
                // a checksum-corrupt full-size file as Complete → false Ready, so discard it.
                // Anything other than Complete must not stay Downloading — Model Status actions
                // are disabled in that state — so drop to a non-Downloading readiness and let the
                // probe resolve to Paused (.part remains) or Loading (discarded), keeping the
                // retry/delete affordances live.
                handleCorruptDownloadResult(store, result)
                if (result != ModelArtifactState.Complete) {
                    _modelReadinessFlow.value = ModelReadiness.Paused
                }
                refreshModelReadiness()
            }
        }
    }

    private fun deleteArtifactFiles(artifact: File) {
        if (!File(artifact.parentFile, "${artifact.name}.part").delete()) {
            Log.w(TAG, "Failed to delete model part file")
        }
        if (!artifact.delete()) Log.w(TAG, "Failed to delete model artifact")
    }

    private fun computeDownloadPercent(current: Long, expected: Long): Int =
        if (expected > 0L) ((current * PCT_MAX) / expected).toInt().coerceIn(0, PCT_MAX) else 0

    private suspend fun runDownload(store: ModelArtifactStore): ModelArtifactState? = try {
        store.download { current, expected ->
            _modelReadinessFlow.value = ModelReadiness.Downloading(computeDownloadPercent(current, expected))
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.e(TAG, "Model re-download failed", error)
        null
    } finally {
        networkGate.seal()
    }

    private fun handleCorruptDownloadResult(store: ModelArtifactStore, result: ModelArtifactState?) {
        if (result is ModelArtifactState.Corrupt) {
            Log.e(TAG, "Re-download produced a checksum-corrupt artifact; discarding")
            deleteArtifactFiles(store.artifactFile)
        }
    }

    /**
     * Wipe every entry, pattern, tag, and callout-cooldown row plus every markdown file.
     * Nothing is sent anywhere; nothing is recoverable. The model artifact is left alone.
     * Per `ux-copy.md` §"Destructive Confirmations / Delete all data".
     */
    suspend fun wipeAllData() {
        cancelTrackedExtractionsAndResetLifecycle()
        withContext(ioDispatcher) {
            boxStore.boxFor(EntryEntity::class.java).removeAll()
            boxStore.boxFor(PatternEntity::class.java).removeAll()
            boxStore.boxFor(TagEntity::class.java).removeAll()
            boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()
            markdownStore.listAll().forEach { if (!it.delete()) Log.w(TAG, "Failed to delete markdown file") }
            Log.i(TAG, "All user data wiped on explicit request")
        }
        _dataRevision.value += 1
    }

    /** Stream a zip of every entry markdown file into [out] (caller owns/closes the stream). */
    suspend fun zipAllEntriesTo(out: OutputStream) {
        withContext(ioDispatcher) {
            ZipOutputStream(out).use { zip ->
                markdownStore.listAll().forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    // closeEntry must run for every opened entry, else `use` closing the stream
                    // over a dangling entry yields a malformed archive. Contain a secondary
                    // close failure so it can't be thrown out of finally and mask the primary
                    // read exception (the read is the true root cause).
                    try {
                        file.inputStream().use { it.copyTo(zip) }
                    } finally {
                        runCatching { zip.closeEntry() }
                    }
                }
            }
        }
    }

    /**
     * Scans entries with non-terminal extraction status and re-runs the foreground extraction
     * pipeline. Called on transition to `Ready` so typed entries that landed during model
     * Loading don't orphan as PENDING. Idempotent — already-running entries are no-ops.
     */
    internal suspend fun recoverPendingExtractions() {
        if (!mainModelArtifactLooksPresent()) return
        val ids = runCatching { VestigeBoxStore.findNonTerminalEntryIds(boxStore) }
            .onFailure { Log.e(TAG, "Failed to scan non-terminal entries for recovery", it) }
            .getOrDefault(emptyList())
        if (ids.isEmpty()) return
        ensureBackgroundEngineInitialized()
        ids.forEach { entryId ->
            val entry = entryStore.readEntry(entryId)
            if (entry != null && entry.extractionStatus == ExtractionStatus.PENDING) {
                recoverOneEntry(
                    entryId = entry.id,
                    entryText = entry.entryText,
                    capturedAt = ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(entry.timestampEpochMs),
                        ZoneId.systemDefault(),
                    ),
                )
            }
        }
    }

    private suspend fun recoverOneEntry(entryId: Long, entryText: String, capturedAt: ZonedDateTime) {
        runCatching {
            val job = backgroundExtractionSaveFlow.recoverEntry(
                entryId = entryId,
                entryText = entryText,
                capturedAt = capturedAt,
                persona = Persona.WITNESS,
            )
            trackExtractionJob(job)
        }.onFailure { Log.e(TAG, "Recovery extraction failed for entry $entryId", it) }
    }

    private suspend fun mainModelArtifactLooksPresent(): Boolean =
        verifiedMainModelState() is ModelArtifactState.Complete

    private suspend fun probeModelReadiness(previous: ModelReadiness): ModelReadiness = runCatching {
        when (val state = verifiedMainModelState()) {
            ModelArtifactState.Absent -> ModelReadiness.Loading

            ModelArtifactState.Complete -> ModelReadiness.Ready

            is ModelArtifactState.Corrupt -> ModelReadiness.Loading

            is ModelArtifactState.Partial ->
                if (previous is ModelReadiness.Downloading) {
                    ModelReadiness.Downloading(
                        percent = ((state.currentBytes * PCT_MAX) / state.expectedBytes)
                            .toInt()
                            .coerceIn(0, PCT_MAX),
                    )
                } else {
                    ModelReadiness.Paused
                }
        }
    }.onFailure { Log.e(TAG, "Failed to probe model readiness", it) }
        .getOrDefault(ModelReadiness.Loading)

    private suspend fun verifiedMainModelState(): ModelArtifactState {
        val probed = mainModelArtifactStore.probe()
        return if (probed == ModelArtifactState.Complete) {
            mainModelArtifactStore.currentState()
        } else {
            probed
        }
    }

    private suspend fun trackExtractionJob(job: Job) {
        trackedExtractionJobsMutex.withLock { trackedExtractionJobs.add(job) }
        job.invokeOnCompletion {
            scope.launch {
                trackedExtractionJobsMutex.withLock { trackedExtractionJobs.remove(job) }
            }
        }
    }

    private suspend fun cancelTrackedExtractionJobs() {
        val jobs = trackedExtractionJobsMutex.withLock { trackedExtractionJobs.toList() }
        jobs.forEach(Job::cancel)
        jobs.joinAll()
    }

    private suspend fun cancelTrackedExtractionsAndResetLifecycle() {
        foregroundServiceStopper(foregroundServiceIntentFactory())
        cancelTrackedExtractionJobs()
        statusBus.clear()
        lifecycleStateMachine.onInFlightCountChange(0)
        lifecycleStateMachine.onServiceKilled()
    }

    private suspend fun runMainModelMutation(name: String, block: suspend () -> Unit) {
        if (!modelMutationMutex.tryLock()) {
            Log.w(TAG, "Ignoring $name while another model mutation is in flight")
            return
        }
        try {
            block()
        } finally {
            modelMutationMutex.unlock()
        }
    }

    internal suspend fun ensureBackgroundEngineInitialized() {
        if (backgroundEngineInitialized) return
        backgroundEngineInitMutex.withLock {
            if (backgroundEngineInitialized) return
            backgroundEngine.initialize()
            backgroundEngineInitialized = true
        }
    }

    private suspend fun resetBackgroundEngine() {
        backgroundEngineInitMutex.withLock {
            if (!backgroundEngineInitialized) return
            // Keep the wrapper instance so every collaborator holding `backgroundEngine` sees the
            // same object; `LiteRtLmEngine.close()` clears the native handle and supports re-init.
            backgroundEngine.close()
            backgroundEngineInitialized = false
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
        const val PCT_MAX = 100

        fun defaultScope(): CoroutineScope {
            val exceptionHandler = CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "AppContainer scope coroutine failed", error)
            }
            return CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        }

        fun ExtractionStatus.isTerminal(): Boolean = when (this) {
            ExtractionStatus.COMPLETED, ExtractionStatus.TIMED_OUT, ExtractionStatus.FAILED -> true
            ExtractionStatus.PENDING, ExtractionStatus.RUNNING -> false
        }
    }

    private enum class VectorBackfillOutcome {
        IDLE,
        COMPLETE,
        RETRY_LATER,
    }
}
