package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleStateMachine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionService
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionStatusBus
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.VestigeBoxStore
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-singleton hub for Phase-2 cross-cutting concerns. */
@Suppress("LongParameterList") // Constructor-injection seams: factories + lifecycle + scope.
class AppContainer(
    private val applicationContext: Context,
    boxStoreFactory: (Context) -> BoxStore = { ctx -> VestigeBoxStore.open(ctx) },
    markdownStoreFactory: (Context) -> MarkdownEntryStore = { ctx -> MarkdownEntryStore(ctx.filesDir) },
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

    private fun seedRecoveredExtractions() {
        val ids = recoveredEntryIdsLoader?.invoke()
            ?: VestigeBoxStore.findNonTerminalEntryIds(boxStore)
        statusBus.seedFromColdStart(ids.toList())
        lifecycleStateMachine.onInFlightCountChange(statusBus.inFlightCount.value)
    }

    /** Tests own the lifecycle and close the container when done. Production rides the process. */
    fun close() {
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

        fun defaultScope(): CoroutineScope {
            val exceptionHandler = CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "AppContainer scope coroutine failed", error)
            }
            return CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        }
    }
}
