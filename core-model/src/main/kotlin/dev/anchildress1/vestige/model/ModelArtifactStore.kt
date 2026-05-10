package dev.anchildress1.vestige.model

import java.io.File

/** Process-scoped owner of the on-disk Gemma artifact: presence, integrity, retry-aware download. */
interface ModelArtifactStore {

    val manifest: ModelManifest

    /** Where the artifact lives, regardless of current state. */
    val artifactFile: File

    /** Cheap presence + size + SHA-256 check. */
    suspend fun currentState(): ModelArtifactState

    /**
     * Downloads with exponential backoff up to [MAX_DOWNLOAD_ATTEMPTS]. Resumes via HTTP `Range`
     * when supported; deletes a `Corrupt` file before re-downloading. Returns `Corrupt` on
     * post-download SHA-256 failure so callers can retry.
     */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): ModelArtifactState

    /** Side-effect-free SHA-256 verification against [ModelManifest.sha256]. */
    suspend fun verifyChecksum(): Boolean

    /** Returns the file iff the artifact is `Complete`; throws otherwise. */
    suspend fun requireComplete(): File

    companion object {
        const val MAX_DOWNLOAD_ATTEMPTS: Int = 3
    }
}
