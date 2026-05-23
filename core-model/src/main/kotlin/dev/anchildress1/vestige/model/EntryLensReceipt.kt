package dev.anchildress1.vestige.model

/**
 * Persisted proof of what one extraction lens emitted for an entry.
 *
 * [rawResponse] is the verbatim per-lens model output, surfaced on the Entry Detail raw block so
 * prompt tuning can read exactly what the model returned. Null when the lens produced no text
 * (engine error / empty stream) or for receipts stored before this field existed.
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
    val rawResponse: String? = null,
) {
    init {
        require(attemptCount >= 0) { "attemptCount must be >= 0, got $attemptCount" }
        require(elapsedMs >= 0L) { "elapsedMs must be >= 0, got $elapsedMs" }
    }
}
