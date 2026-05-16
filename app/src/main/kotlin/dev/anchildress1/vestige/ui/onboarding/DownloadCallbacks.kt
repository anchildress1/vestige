package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DownloadCallbacks(
    val onState: (ModelArtifactState) -> Unit,
    val onSpeed: (Float?) -> Unit,
    val onStatus: (DownloadStatus) -> Unit,
)

internal suspend fun runDownloadIfNeededSingleFlight(
    downloadMutex: Mutex,
    step: OnboardingStep,
    wifiConnected: Boolean,
    modelAvailability: ModelAvailability,
    callbacks: DownloadCallbacks,
): ModelDownloadEntryResult = downloadMutex.withLock {
    // Retry re-keys the effect, but the previous blocking HTTP read can outlive coroutine
    // cancellation briefly. Queueing behind one mutex keeps retries from overlapping and
    // trampling the same `.part` file if the transport is slow to unwind.
    runDownloadIfNeeded(
        step = step,
        wifiConnected = wifiConnected,
        modelAvailability = modelAvailability,
        onState = callbacks.onState,
        onSpeed = callbacks.onSpeed,
        onStatus = callbacks.onStatus,
    )
}
