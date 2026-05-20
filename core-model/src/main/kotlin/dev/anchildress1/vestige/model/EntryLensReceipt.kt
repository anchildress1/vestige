package dev.anchildress1.vestige.model

/**
 * Persisted proof of what one extraction lens emitted for an entry.
 *
 * Raw model responses are intentionally excluded; downstream UI and pattern surfaces need the
 * parsed fields, flags, and failure metadata, not a private debug transcript.
 *
 * [fields] mirrors the persisted JSON object shape (`Any?` = String / List / nested Map / null);
 * numeric value types are not guaranteed stable across the JSON round-trip.
 */
data class EntryLensReceipt(
    val lens: Lens,
    val extracted: Boolean,
    val fields: Map<String, Any?> = emptyMap(),
    val flags: List<String> = emptyList(),
    val attemptCount: Int = 0,
    val elapsedMs: Long = 0L,
    val lastError: String? = null,
) {
    init {
        require(attemptCount >= 0) { "attemptCount must be >= 0, got $attemptCount" }
        require(elapsedMs >= 0L) { "elapsedMs must be >= 0, got $elapsedMs" }
    }
}
