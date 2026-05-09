package dev.anchildress1.vestige.model

/**
 * Disposition of the on-disk model artifact per ADR-001 §Q6. Phase 4 onboarding UX maps
 * each state to a copy line from `ux-copy.md` §Error States; Phase 1 only commits to the
 * state machine.
 */
sealed interface ModelArtifactState {
    /** No file at the expected location. First-launch state. */
    data object Absent : ModelArtifactState

    /**
     * A file exists at the expected location but its size is below the manifest's
     * expected_byte_size. Resume from current size.
     */
    data class Partial(val currentBytes: Long, val expectedBytes: Long) : ModelArtifactState

    /** File exists, size matches the manifest, SHA-256 matches the manifest. Ready to load. */
    data object Complete : ModelArtifactState

    /**
     * File exists at the expected size, but the SHA-256 verification failed. Triggers
     * re-download (not a silent retry).
     */
    data class Corrupt(val expectedSha256: String, val actualSha256: String) : ModelArtifactState
}
