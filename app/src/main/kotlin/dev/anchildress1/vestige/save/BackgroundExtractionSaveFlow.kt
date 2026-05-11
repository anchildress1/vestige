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
import dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator
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
 *   4. Terminal worker states are buffered until persistence succeeds.
 *   5. `Success` → `EntryStore.completeEntry`; `Failed` / `TimedOut` → `EntryStore.failEntry`.
 *   6. Once storage succeeds, the buffered terminal state is forwarded to the lifecycle layer.
 */
class BackgroundExtractionSaveFlow(
    private val entryStore: EntryStore,
    private val worker: BackgroundExtractionWorker,
    private val observationGenerator: ObservationGenerator,
    private val listenerFactory: (Long) -> ExtractionStatusListener,
    private val patternOrchestrator: PatternDetectionOrchestrator? = null,
) {

    suspend fun saveAndExtract(
        entryText: String,
        capturedAt: ZonedDateTime,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        timeoutMs: Long? = null,
    ): SaveOutcome {
        val entryId = entryStore.createPendingEntry(entryText, capturedAt.toInstant())
        val terminalRelay = DeferredTerminalRelay(listenerFactory(entryId))
        val request = BackgroundExtractionRequest(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = retrievedHistory,
            entryAttemptCount = 0,
            timeoutMs = timeoutMs,
        )

        return when (val result = worker.extract(request, terminalRelay.workerListener)) {
            is BackgroundExtractionResult.Success -> handleSuccess(
                entryId = entryId,
                entryText = entryText,
                capturedAt = capturedAt,
                entryAttemptCount = request.entryAttemptCount,
                result = result,
                terminalRelay = terminalRelay,
            )

            is BackgroundExtractionResult.Failed -> handleFailure(
                entryId = entryId,
                entryAttemptCount = request.entryAttemptCount,
                result = result,
                terminalRelay = terminalRelay,
            )

            is BackgroundExtractionResult.TimedOut -> handleTimeout(
                entryId = entryId,
                entryAttemptCount = request.entryAttemptCount,
                result = result,
                terminalRelay = terminalRelay,
            )
        }
    }

    @Suppress("LongParameterList") // Context bundle is clearer than inventing a throwaway carrier type.
    private suspend fun handleSuccess(
        entryId: Long,
        entryText: String,
        capturedAt: ZonedDateTime,
        entryAttemptCount: Int,
        result: BackgroundExtractionResult.Success,
        terminalRelay: DeferredTerminalRelay,
    ): SaveOutcome.Completed {
        val observations = runObservations(entryText, result, capturedAt)
        persistTerminalState(
            entryId = entryId,
            entryAttemptCount = entryAttemptCount,
            status = ExtractionStatus.COMPLETED,
            lastError = null,
            terminalRelay = terminalRelay,
        ) {
            entryStore.completeEntry(entryId, result.resolved, result.templateLabel, observations)
        }
        val calloutObservation = runPatternOrchestration(entryId)
        val finalObservations = if (calloutObservation != null) observations + calloutObservation else observations
        return SaveOutcome.Completed(entryId, result, finalObservations)
    }

    private suspend fun runPatternOrchestration(entryId: Long): EntryObservation? {
        val orchestrator = patternOrchestrator ?: return null
        return try {
            val entry = entryStore.readEntry(entryId)
            val callout = entry?.let { orchestrator.onEntryCommitted(it) }
            callout?.also { entryStore.appendObservation(entryId, it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // ADR-003: pattern detection is a best-effort layer, not blocking. Swallow + log.
            Log.w(
                TAG,
                "Pattern orchestration failed for entryId=$entryId: " +
                    "${error.javaClass.simpleName} ${error.message}",
            )
            null
        }
    }

    private suspend fun handleFailure(
        entryId: Long,
        entryAttemptCount: Int,
        result: BackgroundExtractionResult.Failed,
        terminalRelay: DeferredTerminalRelay,
    ): SaveOutcome.Failed {
        persistTerminalState(
            entryId = entryId,
            entryAttemptCount = entryAttemptCount,
            status = ExtractionStatus.FAILED,
            lastError = result.lastError,
            terminalRelay = terminalRelay,
        ) {
            entryStore.failEntry(entryId, ExtractionStatus.FAILED, result.lastError)
        }
        return SaveOutcome.Failed(entryId, result)
    }

    private suspend fun handleTimeout(
        entryId: Long,
        entryAttemptCount: Int,
        result: BackgroundExtractionResult.TimedOut,
        terminalRelay: DeferredTerminalRelay,
    ): SaveOutcome.TimedOut {
        val timeoutReason = "timeout-after-${result.timeoutMs}ms"
        persistTerminalState(
            entryId = entryId,
            entryAttemptCount = entryAttemptCount,
            status = ExtractionStatus.TIMED_OUT,
            lastError = timeoutReason,
            terminalRelay = terminalRelay,
        ) {
            entryStore.failEntry(entryId, ExtractionStatus.TIMED_OUT, timeoutReason)
        }
        return SaveOutcome.TimedOut(entryId, result)
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

    private suspend fun compensatePersistenceFailure(
        entryId: Long,
        entryAttemptCount: Int,
        terminalRelay: DeferredTerminalRelay,
        error: Exception,
    ) {
        val failureReason = "persistence-error:${error.javaClass.simpleName}"
        try {
            entryStore.failEntry(entryId, ExtractionStatus.FAILED, failureReason)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") compensationError: Exception) {
            Log.e(
                TAG,
                "Persistence compensation failed for entryId=$entryId " +
                    "(${compensationError.javaClass.simpleName}: ${compensationError.message})",
            )
        }
        terminalRelay.emitTerminal(
            status = ExtractionStatus.FAILED,
            entryAttemptCount = entryAttemptCount,
            lastError = failureReason,
        )
    }

    private class DeferredTerminalRelay(private val downstream: ExtractionStatusListener) {
        val workerListener: ExtractionStatusListener =
            ExtractionStatusListener { status, entryAttemptCount, lastError ->
                if (!isTerminal(status)) {
                    downstream.onUpdate(status, entryAttemptCount, lastError)
                }
            }

        suspend fun emitTerminal(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?) {
            downstream.onUpdate(status, entryAttemptCount, lastError)
        }

        private companion object {
            fun isTerminal(status: ExtractionStatus): Boolean = when (status) {
                ExtractionStatus.COMPLETED, ExtractionStatus.TIMED_OUT, ExtractionStatus.FAILED -> true
                ExtractionStatus.PENDING, ExtractionStatus.RUNNING -> false
            }
        }
    }

    @Suppress("LongParameterList") // Call-site clarity beats a one-off params holder for one helper.
    private suspend fun persistTerminalState(
        entryId: Long,
        entryAttemptCount: Int,
        status: ExtractionStatus,
        lastError: String?,
        terminalRelay: DeferredTerminalRelay,
        persist: () -> Unit,
    ) {
        try {
            persist()
            terminalRelay.emitTerminal(status, entryAttemptCount, lastError)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            compensatePersistenceFailure(entryId, entryAttemptCount, terminalRelay, error)
            throw error
        }
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
