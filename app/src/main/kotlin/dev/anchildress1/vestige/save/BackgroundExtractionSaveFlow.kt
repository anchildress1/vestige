package dev.anchildress1.vestige.save

import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryStore
import java.time.ZonedDateTime

/**
 * Glues [EntryStore] + [BackgroundExtractionWorker] + the per-entry status listener into one
 * call. Closes the persistence loop Story 2.6.5 staged: every entry's extraction status drives
 * the foreground-service lifecycle, and convergence outputs land in both ObjectBox and markdown.
 *
 * Sequence per [saveAndExtract]:
 *   1. `EntryStore.createPendingEntry` writes the transcription and assigns an `entryId`.
 *   2. The caller-supplied [listenerFactory] (typically `AppContainer.extractionStatusListener`)
 *      yields the listener routed at that `entryId`.
 *   3. `BackgroundExtractionWorker.extract` runs the 3-lens sequential pipeline.
 *   4. Terminal status: `Success` → `EntryStore.completeEntry`; `Failed` / `TimedOut` →
 *      `EntryStore.failEntry`.
 */
class BackgroundExtractionSaveFlow(
    private val entryStore: EntryStore,
    private val worker: BackgroundExtractionWorker,
    private val listenerFactory: (Long) -> ExtractionStatusListener,
) {

    suspend fun saveAndExtract(
        entryText: String,
        capturedAt: ZonedDateTime,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        timeoutMs: Long? = null,
    ): SaveOutcome {
        val entryId = entryStore.createPendingEntry(entryText, capturedAt.toInstant())
        val listener = listenerFactory(entryId)
        val request = BackgroundExtractionRequest(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = retrievedHistory,
            entryAttemptCount = 0,
            timeoutMs = timeoutMs,
        )

        return when (val result = worker.extract(request, listener)) {
            is BackgroundExtractionResult.Success -> {
                entryStore.completeEntry(entryId, result.resolved, result.templateLabel)
                SaveOutcome.Completed(entryId, result)
            }

            is BackgroundExtractionResult.Failed -> {
                entryStore.failEntry(entryId, ExtractionStatus.FAILED, result.lastError)
                SaveOutcome.Failed(entryId, result)
            }

            is BackgroundExtractionResult.TimedOut -> {
                entryStore.failEntry(entryId, ExtractionStatus.TIMED_OUT, "timeout-after-${result.timeoutMs}ms")
                SaveOutcome.TimedOut(entryId, result)
            }
        }
    }
}

sealed interface SaveOutcome {
    val entryId: Long

    data class Completed(override val entryId: Long, val result: BackgroundExtractionResult.Success) : SaveOutcome
    data class Failed(override val entryId: Long, val result: BackgroundExtractionResult.Failed) : SaveOutcome
    data class TimedOut(override val entryId: Long, val result: BackgroundExtractionResult.TimedOut) : SaveOutcome
}
