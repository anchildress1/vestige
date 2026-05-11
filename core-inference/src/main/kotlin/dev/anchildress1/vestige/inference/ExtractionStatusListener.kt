package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ExtractionStatus

/**
 * Hook the persistence/lifecycle layer registers to observe worker progress. Fires `RUNNING` at
 * start and on every lens-level retry, then exactly one terminal `COMPLETED`, `TIMED_OUT`, or
 * `FAILED`. Transient `lastError` values during `RUNNING` are diagnostic — callers may defer the
 * terminal callback until persistence succeeds.
 */
fun interface ExtractionStatusListener {
    suspend fun onUpdate(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?)
}
