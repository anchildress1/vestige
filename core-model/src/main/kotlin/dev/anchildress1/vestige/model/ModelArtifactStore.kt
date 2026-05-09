package dev.anchildress1.vestige.model

import java.io.File

/**
 * Storage + load contract for the Gemma 4 E4B model artifact per ADR-001 §Q6 and architecture-
 * brief.md §AppContainer. Process-scoped singleton; one instance owns the on-disk artifact for
 * the life of the process.
 *
 * Phase 1 commits to the contract here. Phase 4 wires the user-facing onboarding UX (Wi-Fi gate,
 * progress UI, retry buttons) on top.
 */
interface ModelArtifactStore {

    /** Pinned manifest the store enforces against. */
    val manifest: ModelManifest

    /** Absolute path to the artifact's expected location, regardless of current state. */
    val artifactFile: File

    /** Inspect what's currently on disk. Cheap — checks file presence + size + SHA-256 if size matches. */
    suspend fun currentState(): ModelArtifactState

    /**
     * Download the artifact, retrying transient errors with exponential backoff up to
     * [MAX_DOWNLOAD_ATTEMPTS]. Resumes via HTTP `Range` when the host supports it and the local
     * file is partial; restarts from byte 0 otherwise.
     *
     * On a `Corrupt` initial state the file is deleted before re-download.
     *
     * [onProgress] is called periodically with `(bytesSoFar, expectedBytes)`. Defaults to no-op.
     *
     * Returns the final post-download [ModelArtifactState] — `Complete` on success, `Corrupt`
     * if the SHA-256 verification fails on the freshly-downloaded file (callers then re-download).
     */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): ModelArtifactState

    /** Verify the on-disk file against [ModelManifest.sha256]. Side-effect-free. */
    suspend fun verifyChecksum(): Boolean

    /**
     * Convenience: assert the artifact is `Complete` and return its file. Throws if the state is
     * anything else — callers should check [currentState] first and run [download] when needed.
     */
    suspend fun requireComplete(): File

    companion object {
        const val MAX_DOWNLOAD_ATTEMPTS: Int = 3
    }
}
