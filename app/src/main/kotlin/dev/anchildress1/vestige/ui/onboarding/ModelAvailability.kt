package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore

/** Onboarding-side projection of main-model status. Tests fake it; screens read percentage off it. */
fun interface ModelAvailability {
    suspend fun status(): ModelArtifactState

    class Default(private val artifactStore: ModelArtifactStore) : ModelAvailability {
        override suspend fun status(): ModelArtifactState = artifactStore.currentState()
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
