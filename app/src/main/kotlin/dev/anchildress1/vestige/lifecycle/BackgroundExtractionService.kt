package dev.anchildress1.vestige.lifecycle

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.anchildress1.vestige.VestigeApplication
import kotlinx.coroutines.launch

/** Foreground-service wrapper for [BackgroundExtractionLifecycleStateMachine] (ADR-004). */
class BackgroundExtractionService : LifecycleService() {

    private var shutdownHandled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val machine = requireStateMachine()
        lifecycleScope.launch {
            machine.state.collect { state ->
                if (state == BackgroundExtractionLifecycleState.DEMOTING) {
                    shutdownHandled = true
                    stopForegroundCompat()
                    machine.onForegroundStopConfirmed()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val machine = requireStateMachine()
        if (machine.state.value == BackgroundExtractionLifecycleState.PROMOTING) {
            try {
                startForegroundCompat()
                machine.onForegroundStartConfirmed()
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                // FGS start can throw ForegroundServiceStartNotAllowedException, SecurityException,
                // or MissingForegroundServiceTypeException. Wedging the machine in PROMOTING blocks
                // every future capture for the process lifetime — surface the failure and let the
                // machine re-arm a bounded retry while work is still in flight.
                Log.e(TAG, "startForeground rejected", error)
                machine.onForegroundStartFailed()
                shutdownHandled = true
                stopSelf(startId)
            }
        } else {
            shutdownHandled = true
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!shutdownHandled) {
            // OS killed us mid-extraction (service-only kill, process survives) — the cold-start
            // sweep can't catch this because the process didn't die. Reset the machine so a
            // queued extraction re-promotes via the dispatch callback.
            stateMachine()?.onServiceKilled()
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = LocalProcessingNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                LocalProcessingNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(LocalProcessingNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun requireStateMachine(): BackgroundExtractionLifecycleStateMachine = stateMachine()
        ?: error("VestigeApplication.appContainer not initialized — service started before Application.onCreate")

    private fun stateMachine(): BackgroundExtractionLifecycleStateMachine? =
        (application as? VestigeApplication)?.appContainer?.lifecycleStateMachine

    private companion object {
        const val TAG = "VestigeBackgroundExtractionService"
    }
}
