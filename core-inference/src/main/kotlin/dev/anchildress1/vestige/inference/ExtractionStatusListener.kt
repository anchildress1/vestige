package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ExtractionStatus

/**
 * Hook the persistence layer registers to mirror worker progress onto the entry row. Fires
 * `RUNNING` at start and on every lens-level retry, then exactly one terminal `COMPLETED` or
 * `FAILED`. Transient `lastError` values during `RUNNING` are diagnostic — only persist the
 * terminal value.
 */
fun interface ExtractionStatusListener {
    suspend fun onUpdate(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?)
}
