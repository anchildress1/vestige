package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleStateMachine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionService
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionStatusBus
import dev.anchildress1.vestige.model.ExtractionStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-singleton hub for Phase-2 cross-cutting concerns. */
class AppContainer(
    private val applicationContext: Context,
    // Default no-ops the cold-start sweep until EntryStore (architecture-brief §"AppContainer
    // Ownership") owns the BoxStore — see `VestigeBoxStore.findNonTerminalEntryIds`.
    private val recoveredEntryIdsLoader: () -> Collection<Long> = { emptyList() },
    private val foregroundServiceIntentFactory: () -> Intent = {
        Intent(applicationContext, BackgroundExtractionService::class.java)
    },
    private val foregroundServiceStarter: (Intent) -> Unit = { intent ->
        applicationContext.startForegroundService(intent)
    },
    private val scope: CoroutineScope = defaultScope(),
) {

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
        statusBus.seedFromColdStart(recoveredEntryIdsLoader().toList())
        lifecycleStateMachine.onInFlightCountChange(statusBus.inFlightCount.value)
    }

    private fun dispatchStartForegroundService() {
        val intent = foregroundServiceIntentFactory()
        try {
            foregroundServiceStarter(intent)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Background-launch restrictions (Android 12+), battery-saver, or app-standby buckets
            // can refuse the start. Reset the machine so the next capture can try again.
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
