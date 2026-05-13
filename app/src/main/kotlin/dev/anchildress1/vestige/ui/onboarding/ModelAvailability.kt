package dev.anchildress1.vestige.ui.onboarding

import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.ModelArtifactStore

/** Small seam around main-model readiness so onboarding can re-check on resume and tests can fake it. */
fun interface ModelAvailability {
    suspend fun isModelReady(): Boolean

    class Default(private val artifactStore: ModelArtifactStore) : ModelAvailability {
        override suspend fun isModelReady(): Boolean = artifactStore.currentState() is ModelArtifactState.Complete
    }
}
