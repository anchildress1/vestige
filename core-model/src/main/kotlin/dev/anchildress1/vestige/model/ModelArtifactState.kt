package dev.anchildress1.vestige.model

/** Disposition of the on-disk model artifact. */
sealed interface ModelArtifactState {
    /** No file present. First-launch state. */
    object Absent : ModelArtifactState

    /** File exists below `expected_byte_size`. Resume from current size. */
    data class Partial(val currentBytes: Long, val expectedBytes: Long) : ModelArtifactState

    /** Size + SHA-256 both match. Ready to load. */
    object Complete : ModelArtifactState

    /** Size matches but SHA-256 failed — triggers a re-download, not a silent retry. */
    data class Corrupt(val expectedSha256: String, val actualSha256: String) : ModelArtifactState
}
