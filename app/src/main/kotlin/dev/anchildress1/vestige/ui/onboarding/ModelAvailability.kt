package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore

/**
 * Onboarding-side projection of main-model status + the download trigger that drives `Partial`
 * state transitions. Screen 6 needs both: a snapshot read on entry/resume, and an active
 * download whose progress callback ticks the UI.
 */
interface ModelAvailability {
    suspend fun status(): ModelArtifactState

    /** Default: no-op trigger that returns current status. `Default` overrides to do real I/O. */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): ModelArtifactState = status()

    class Default(private val artifactStore: ModelArtifactStore) : ModelAvailability {
        override suspend fun status(): ModelArtifactState = artifactStore.currentState()

        override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState =
            artifactStore.download(onProgress)
    }
}

/** True only when the artifact has landed and SHA matches. */
internal val ModelArtifactState.isReady: Boolean
    get() = this is ModelArtifactState.Complete

/** 0..1 download progress, or null when state is indeterminate (no expected size yet). */
internal val ModelArtifactState.downloadFraction: Float?
    get() = when (this) {
        is ModelArtifactState.Partial -> {
            if (expectedBytes <= 0L) {
                null
            } else {
                (currentBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
            }
        }

        ModelArtifactState.Complete -> 1f

        ModelArtifactState.Absent -> null

        is ModelArtifactState.Corrupt -> null
    }
