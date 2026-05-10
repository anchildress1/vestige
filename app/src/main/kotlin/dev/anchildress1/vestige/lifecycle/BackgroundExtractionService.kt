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
        // The OS demands startForeground within ~5s of startForegroundService for every
        // non-NORMAL state. NORMAL is the only legitimate "stale dispatch": the machine was
        // reset (start failure / OS-kill recovery completing without queued work) before
        // Android scheduled onStartCommand. PROMOTING is the only state that expects an
        // ack — FOREGROUND / KEEP_ALIVE / DEMOTING reached us via paths that do not, and
        // re-acking would reset the keep-alive timer. See ADR-007 §"Service / state-machine
        // handshake" for the full table.
        handleStartCommand(machine, machine.state.value, startId)
        return START_NOT_STICKY
    }

    private fun handleStartCommand(
        machine: BackgroundExtractionLifecycleStateMachine,
        initialState: BackgroundExtractionLifecycleState,
        startId: Int,
    ) {
        if (initialState == BackgroundExtractionLifecycleState.NORMAL) {
            shutdownHandled = true
            stopSelf(startId)
            return
        }
        try {
            startForegroundCompat()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.e(TAG, "startForeground rejected", error)
            if (initialState == BackgroundExtractionLifecycleState.PROMOTING) {
                machine.onForegroundStartFailed()
            }
            shutdownHandled = true
            stopSelf(startId)
            return
        }
        if (initialState == BackgroundExtractionLifecycleState.PROMOTING) {
            machine.onForegroundStartConfirmed()
        }
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
