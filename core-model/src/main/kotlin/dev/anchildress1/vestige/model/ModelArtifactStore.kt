package dev.anchildress1.vestige.model

import java.io.File

/** Process-scoped owner of the on-disk Gemma artifact: presence, integrity, retry-aware download. */
interface ModelArtifactStore {

    val manifest: ModelManifest

    /** Where the artifact lives, regardless of current state. */
    val artifactFile: File

    /** Presence + size + SHA-256 check. Hashes the whole file when the size matches. */
    suspend fun currentState(): ModelArtifactState

    /**
     * Cheap disposition: presence + size + in-flight `.part`. Never hashes — a size match
     * resolves to `Complete` on trust. SHA-256 integrity is deferred to the load path
     * ([requireComplete]) / [verifyChecksum], so UI gating never blocks on hashing the
     * multi-GB artifact. Reports `Partial` from a resumable `.part` when no final file exists.
     */
    suspend fun probe(): ModelArtifactState

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
