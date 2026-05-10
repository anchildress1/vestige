package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ExtractionStatus

/**
 * Observed by the caller wiring `BackgroundExtractionWorker` into entry persistence (Story 2.12).
 * The worker calls [onUpdate] at every transition listed in ADR-001 §Q3:
 *
 * - `RUNNING` once when the first lens call begins.
 * - `RUNNING` again on each lens-level retry, with the same entry-level retry count and
 *   `lastError` populated from the most recent failure.
 * - `COMPLETED` (or `FAILED`) exactly once at the terminal state.
 *
 * `:core-inference` does not depend on `:core-storage`, so this listener is the seam the entry-
 * persistence layer hooks into. Implementations are responsible for translating these signals
 * onto the `EntryEntity.extraction_status / attempt_count / last_error` triplet.
 */
fun interface ExtractionStatusListener {
    suspend fun onUpdate(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?)
}
