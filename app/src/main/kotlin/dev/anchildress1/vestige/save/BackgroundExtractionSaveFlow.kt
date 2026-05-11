package dev.anchildress1.vestige.save

import android.util.Log
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.ObservationGenerator
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.CancellationException
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
    private val observationGenerator: ObservationGenerator,
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
                val observations = runObservations(entryText, result, capturedAt)
                entryStore.completeEntry(entryId, result.resolved, result.templateLabel, observations)
                SaveOutcome.Completed(entryId, result, observations)
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

    private suspend fun runObservations(
        entryText: String,
        success: BackgroundExtractionResult.Success,
        capturedAt: ZonedDateTime,
    ): List<EntryObservation> = try {
        observationGenerator.generate(entryText, success.resolved, capturedAt)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // Generator failures must not block the save — the entry's resolved fields are the
        // load-bearing surface; observations are additive and may be regenerated later under
        // re-eval (Phase 4). Persist an empty list and move on.
        Log.w(TAG, "ObservationGenerator threw ${error.javaClass.simpleName}: ${error.message}")
        emptyList()
    }

    private companion object {
        private const val TAG = "VestigeSaveFlow"
    }
}

sealed interface SaveOutcome {
    val entryId: Long

    data class Completed(
        override val entryId: Long,
        val result: BackgroundExtractionResult.Success,
        val observations: List<EntryObservation>,
    ) : SaveOutcome

    data class Failed(override val entryId: Long, val result: BackgroundExtractionResult.Failed) : SaveOutcome
    data class TimedOut(override val entryId: Long, val result: BackgroundExtractionResult.TimedOut) : SaveOutcome
}
