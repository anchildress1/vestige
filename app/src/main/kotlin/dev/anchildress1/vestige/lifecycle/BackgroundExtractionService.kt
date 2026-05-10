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

    private var collectorStarted: Boolean = false
    private var killAcknowledged: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val machine = requireStateMachine()
        if (collectorStarted) return
        collectorStarted = true
        lifecycleScope.launch {
            machine.state.collect { state ->
                if (state == BackgroundExtractionLifecycleState.DEMOTING) {
                    stopForegroundCompat()
                    machine.onForegroundStopConfirmed()
                    killAcknowledged = true
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val machine = requireStateMachine()
        try {
            startForegroundCompat()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // FGS start can throw ForegroundServiceStartNotAllowedException, SecurityException,
            // or MissingForegroundServiceTypeException. Wedging the machine in PROMOTING blocks
            // every future capture for the process lifetime — surface the failure and reset.
            Log.e(TAG, "startForeground rejected", error)
            machine.onForegroundStartFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        machine.onForegroundStartConfirmed()
        return START_STICKY
    }

    override fun onDestroy() {
        if (!killAcknowledged) {
            // OS killed us mid-extraction; recovery follows ADR-001 §Q3 cold-start sweep.
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
