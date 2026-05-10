package dev.anchildress1.vestige

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleState
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionLifecycleStateMachine
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionService
import dev.anchildress1.vestige.lifecycle.BackgroundExtractionStatusBus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-singleton hub for Phase-2 cross-cutting concerns. */
class AppContainer(private val applicationContext: Context) {

    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "AppContainer scope coroutine failed", error)
    }
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    val statusBus: BackgroundExtractionStatusBus = BackgroundExtractionStatusBus()

    val lifecycleStateMachine: BackgroundExtractionLifecycleStateMachine =
        BackgroundExtractionLifecycleStateMachine(scope)

    init {
        scope.launch {
            statusBus.inFlightCount.collect { count ->
                lifecycleStateMachine.onInFlightCountChange(count)
            }
        }
        scope.launch {
            lifecycleStateMachine.state.collect { state ->
                if (state == BackgroundExtractionLifecycleState.PROMOTING) {
                    dispatchStartForegroundService()
                }
            }
        }
    }

    private fun dispatchStartForegroundService() {
        val intent = Intent(applicationContext, BackgroundExtractionService::class.java)
        try {
            applicationContext.startForegroundService(intent)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Background-launch restrictions (Android 12+), battery-saver, or app-standby buckets
            // can refuse the start. Reset the machine so the next capture can try again.
            Log.e(TAG, "startForegroundService rejected", error)
            lifecycleStateMachine.onForegroundStartFailed()
        }
    }

    private companion object {
        const val TAG = "VestigeAppContainer"
    }
}
