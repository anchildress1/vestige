package dev.anchildress1.vestige.lifecycle

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Pure-Kotlin transition table for the conditional foreground service. */
@Suppress("TooManyFunctions")
class BackgroundExtractionLifecycleStateMachine(
    private val scope: CoroutineScope,
    private val keepAlive: Duration = DEFAULT_KEEP_ALIVE,
    private val foregroundStartRetryDelay: Duration = DEFAULT_FOREGROUND_START_RETRY_DELAY,
    private val backgroundStartRetryDelay: Duration = DEFAULT_BACKGROUND_START_RETRY_DELAY,
    private val onPromoteRequested: () -> Unit = {},
) {

    private val mutableState: MutableStateFlow<BackgroundExtractionLifecycleState> =
        MutableStateFlow(BackgroundExtractionLifecycleState.NORMAL)
    val state: StateFlow<BackgroundExtractionLifecycleState> = mutableState.asStateFlow()

    private var inFlightCount: Int = 0
    private var keepAliveJob: Job? = null
    private var foregroundStartRetryJob: Job? = null

    // Set when Android 12+ refuses a foreground start because the app is backgrounded; an
    // immediate retry would just re-fail. Cleared on idle, on app resume (via
    // allowSuppressedPromotion), on a successful confirm, on service-kill recovery, or after the
    // backgroundStartRetryDelay backoff elapses with work still in flight.
    private var foregroundStartSuppressedUntilIdle: Boolean = false

    @Synchronized
    fun onInFlightCountChange(count: Int, allowSuppressedPromotion: Boolean = false) {
        require(count >= 0) { "inFlightCount must be ≥ 0 (got $count)" }
        inFlightCount = count
        if (count == 0) {
            foregroundStartSuppressedUntilIdle = false
            foregroundStartRetryJob?.cancel()
            foregroundStartRetryJob = null
        }
        when (mutableState.value) {
            BackgroundExtractionLifecycleState.NORMAL -> {
                if (count > 0 && (!foregroundStartSuppressedUntilIdle || allowSuppressedPromotion)) {
                    foregroundStartSuppressedUntilIdle = false
                    foregroundStartRetryJob?.cancel()
                    foregroundStartRetryJob = null
                    transition(BackgroundExtractionLifecycleState.PROMOTING)
                } else if (count > 0 && foregroundStartSuppressedUntilIdle) {
                    // Backgrounded extraction is queued and FGS is denied; without a self-heal a
                    // user who never returns to the app strands the work until process death.
                    Log.w(TAG, "FGS promotion suppressed but inFlightCount=$count — scheduling delayed retry")
                    scheduleBackgroundStartRetry()
                }
            }

            BackgroundExtractionLifecycleState.PROMOTING,
            BackgroundExtractionLifecycleState.FOREGROUND,
            -> if (count == 0) startKeepAlive()

            BackgroundExtractionLifecycleState.KEEP_ALIVE ->
                if (count > 0) cancelKeepAliveAndResume()

            BackgroundExtractionLifecycleState.DEMOTING -> Unit
        }
    }

    @Synchronized
    fun onForegroundStartConfirmed() {
        if (mutableState.value == BackgroundExtractionLifecycleState.PROMOTING) {
            foregroundStartRetryJob?.cancel()
            foregroundStartRetryJob = null
            transition(BackgroundExtractionLifecycleState.FOREGROUND)
            if (inFlightCount == 0) startKeepAlive()
        }
    }

    @Synchronized
    fun onForegroundStopConfirmed() {
        if (mutableState.value == BackgroundExtractionLifecycleState.DEMOTING) {
            transition(BackgroundExtractionLifecycleState.NORMAL)
            if (inFlightCount > 0) transition(BackgroundExtractionLifecycleState.PROMOTING)
        }
    }

    /** Reset on platform start failure so the machine doesn't wedge in PROMOTING. */
    @Synchronized
    fun onForegroundStartFailed(retry: Boolean = true) {
        if (mutableState.value == BackgroundExtractionLifecycleState.PROMOTING) {
            transition(BackgroundExtractionLifecycleState.NORMAL)
            if (retry && inFlightCount > 0) {
                scheduleForegroundStartRetry()
            } else if (inFlightCount > 0) {
                foregroundStartSuppressedUntilIdle = true
            }
        }
    }

    /** Recovery hook for OS-kill cases the cold-start sweep can't catch (service-only kill). */
    @Synchronized
    fun onServiceKilled() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        foregroundStartRetryJob?.cancel()
        foregroundStartRetryJob = null
        foregroundStartSuppressedUntilIdle = false
        transition(BackgroundExtractionLifecycleState.NORMAL)
        if (inFlightCount > 0) transition(BackgroundExtractionLifecycleState.PROMOTING)
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        transition(BackgroundExtractionLifecycleState.KEEP_ALIVE)
        keepAliveJob = scope.launch {
            delay(keepAlive)
            onKeepAliveExpired()
        }
    }

    @Synchronized
    private fun onKeepAliveExpired() {
        if (mutableState.value == BackgroundExtractionLifecycleState.KEEP_ALIVE && inFlightCount == 0) {
            keepAliveJob = null
            transition(BackgroundExtractionLifecycleState.DEMOTING)
        }
    }

    private fun cancelKeepAliveAndResume() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        transition(BackgroundExtractionLifecycleState.FOREGROUND)
    }

    private fun scheduleForegroundStartRetry() {
        if (foregroundStartRetryJob?.isActive == true) return
        foregroundStartRetryJob = scope.launch {
            delay(foregroundStartRetryDelay)
            synchronized(this@BackgroundExtractionLifecycleStateMachine) {
                foregroundStartRetryJob = null
                if (mutableState.value == BackgroundExtractionLifecycleState.NORMAL && inFlightCount > 0) {
                    transition(BackgroundExtractionLifecycleState.PROMOTING)
                }
            }
        }
    }

    private fun scheduleBackgroundStartRetry() {
        if (foregroundStartRetryJob?.isActive == true) return
        foregroundStartRetryJob = scope.launch {
            delay(backgroundStartRetryDelay)
            synchronized(this@BackgroundExtractionLifecycleStateMachine) {
                foregroundStartRetryJob = null
                if (mutableState.value == BackgroundExtractionLifecycleState.NORMAL && inFlightCount > 0) {
                    // Clear suppression and attempt once — Android may permit the start now that
                    // backoff has elapsed. If still denied, the catch path re-suppresses.
                    foregroundStartSuppressedUntilIdle = false
                    transition(BackgroundExtractionLifecycleState.PROMOTING)
                }
            }
        }
    }

    private fun transition(next: BackgroundExtractionLifecycleState) {
        mutableState.value = next
        if (next == BackgroundExtractionLifecycleState.PROMOTING) {
            onPromoteRequested()
        }
    }

    companion object {
        val DEFAULT_KEEP_ALIVE: Duration = 30.seconds
        val DEFAULT_FOREGROUND_START_RETRY_DELAY: Duration = 5.seconds

        // Android 12+ relaxes some FGS-start restrictions on a delay (e.g. exit-from-doze grace
        // windows). 60s is a conservative ceiling: long enough to skip the immediate-fail window,
        // short enough that a backgrounded extraction is not stranded for the rest of the run.
        val DEFAULT_BACKGROUND_START_RETRY_DELAY: Duration = 60.seconds

        private const val TAG = "VestigeBackgroundExtractionLifecycle"
    }
}
