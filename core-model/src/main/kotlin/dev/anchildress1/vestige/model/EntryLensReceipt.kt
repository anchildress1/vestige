package dev.anchildress1.vestige.model

/**
 * Persisted proof of what one extraction lens emitted for an entry.
 *
 * Raw model responses are intentionally excluded; downstream UI and pattern surfaces need the
 * parsed fields, flags, and failure metadata, not a private debug transcript.
 */
data class EntryLensReceipt(
    val lens: Lens,
    val extracted: Boolean,
    val fields: Map<String, Any?> = emptyMap(),
    val flags: List<String> = emptyList(),
    val attemptCount: Int = 0,
    val elapsedMs: Long = 0L,
    val lastError: String? = null,
)
