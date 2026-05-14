package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore
import dev.anchildress1.vestige.model.NetworkGate

/**
 * Onboarding-side projection of main-model status + the download trigger that drives `Partial`
 * state transitions. Screen 6 needs both: a snapshot read on entry/resume, and an active
 * download whose progress callback ticks the UI.
 */
interface ModelAvailability {
    suspend fun status(): ModelArtifactState

    /** Default: no-op trigger that returns current status. `Default` overrides to do real I/O. */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): ModelArtifactState = status()

    class Default(private val artifactStore: ModelArtifactStore, private val networkGate: NetworkGate) :
        ModelAvailability {
        override suspend fun status(): ModelArtifactState = artifactStore.currentState()

        /**
         * Opens the [NetworkGate] for the duration of the download — every outbound HTTP primitive
         * checks the gate before dialing, and `Sealed` is the default. The `finally` reseals even
         * if `download()` throws so the privacy posture matches `concept-locked.md` §"Local-only".
         */
        override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState {
            networkGate.openForDownload(reason = "Onboarding Screen 6 — Gemma 4 E4B artifact")
            return try {
                artifactStore.download(onProgress)
            } finally {
                networkGate.seal()
            }
        }
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
