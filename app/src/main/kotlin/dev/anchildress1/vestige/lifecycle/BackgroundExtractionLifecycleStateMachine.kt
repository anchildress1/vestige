package dev.anchildress1.vestige.lifecycle

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
class BackgroundExtractionLifecycleStateMachine(
    private val scope: CoroutineScope,
    private val keepAlive: Duration = DEFAULT_KEEP_ALIVE,
    private val foregroundStartRetryDelay: Duration = DEFAULT_FOREGROUND_START_RETRY_DELAY,
    private val onPromoteRequested: () -> Unit = {},
) {

    private val mutableState: MutableStateFlow<BackgroundExtractionLifecycleState> =
        MutableStateFlow(BackgroundExtractionLifecycleState.NORMAL)
    val state: StateFlow<BackgroundExtractionLifecycleState> = mutableState.asStateFlow()

    private var inFlightCount: Int = 0
    private var keepAliveJob: Job? = null
    private var foregroundStartRetryJob: Job? = null

    @Synchronized
    fun onInFlightCountChange(count: Int) {
        require(count >= 0) { "inFlightCount must be ≥ 0 (got $count)" }
        inFlightCount = count
        if (count == 0) {
            foregroundStartRetryJob?.cancel()
            foregroundStartRetryJob = null
        }
        when (mutableState.value) {
            BackgroundExtractionLifecycleState.NORMAL -> {
                if (count > 0) {
                    foregroundStartRetryJob?.cancel()
                    foregroundStartRetryJob = null
                    transition(BackgroundExtractionLifecycleState.PROMOTING)
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
    fun onForegroundStartFailed() {
        if (mutableState.value == BackgroundExtractionLifecycleState.PROMOTING) {
            transition(BackgroundExtractionLifecycleState.NORMAL)
            if (inFlightCount > 0) scheduleForegroundStartRetry()
        }
    }

    /** Recovery hook for OS-kill cases the cold-start sweep can't catch (service-only kill). */
    @Synchronized
    fun onServiceKilled() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        foregroundStartRetryJob?.cancel()
        foregroundStartRetryJob = null
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

    private fun transition(next: BackgroundExtractionLifecycleState) {
        mutableState.value = next
        if (next == BackgroundExtractionLifecycleState.PROMOTING) {
            onPromoteRequested()
        }
    }

    companion object {
        val DEFAULT_KEEP_ALIVE: Duration = 30.seconds
        val DEFAULT_FOREGROUND_START_RETRY_DELAY: Duration = 5.seconds
    }
}
