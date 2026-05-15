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
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.patterns.PatternDetectionOrchestrator
import dev.anchildress1.vestige.storage.EntryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Glues [EntryStore] + [BackgroundExtractionWorker] + the per-entry status listener into one
 * call. Two-tier per ADR-002 §"Two-Tier Processing Contract": the caller-facing [saveAndExtract]
 * commits the pending entry and returns immediately with [SaveOutcome.Pending]. The 3-lens
 * extraction + resolver + observation generation + pattern callout pipeline runs detached on
 * the injected [scope] — terminal status flows through the `ExtractionStatusListener` from
 * [listenerFactory] (typically `AppContainer.extractionStatusListener` → `BackgroundExtractionStatusBus`).
 *
 * Sequence per [saveAndExtract]:
 *   1. `EntryStore.createPendingEntry` writes the transcription and assigns an `entryId`.
 *      Synchronous — the caller awaits the row commit only.
 *   2. The caller-supplied [listenerFactory] yields the listener routed at that `entryId`.
 *   3. Caller receives [SaveOutcome.Pending] and resumes.
 *   4. Detached: `BackgroundExtractionWorker.extract` runs the 3-lens sequential pipeline.
 *   5. Detached: terminal worker states are buffered until persistence succeeds.
 *   6. Detached: `Success` → `EntryStore.completeEntry`; `Failed` / `TimedOut` → `failEntry`.
 *   7. Detached: once storage succeeds, the buffered terminal state is forwarded to the listener.
 */
@Suppress("TooManyFunctions") // Pipeline + handlers + helpers; splitting hides the linear flow.
class BackgroundExtractionSaveFlow(
    private val entryStore: EntryStore,
    private val worker: BackgroundExtractionWorker,
    private val observationGenerator: ObservationGenerator,
    private val listenerFactory: (Long) -> ExtractionStatusListener,
    private val scope: CoroutineScope,
    private val patternOrchestrator: PatternDetectionOrchestrator? = null,
) {

    /**
     * Persist [entryText] as a `PENDING` row, return immediately, and launch the detached 3-lens
     * extraction pipeline on the injected scope. Caller awaits only the entry commit; terminal
     * extraction status is delivered to the listener registered against the returned entry id.
     * Returns the detached [Job] on the outcome so tests + tooling can await completion.
     */
    @Suppress("LongParameterList") // 6-param orchestration contract; no grouping improves it.
    suspend fun saveAndExtract(
        entryText: String,
        capturedAt: ZonedDateTime,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        timeoutMs: Long? = null,
        persona: Persona = Persona.WITNESS,
        durationMs: Long = 0L,
    ): SaveOutcome.Pending {
        val entryId = entryStore.createPendingEntry(entryText, capturedAt.toInstant(), durationMs)
        val terminalRelay = DeferredTerminalRelay(listenerFactory(entryId))
        // Emit PENDING before launching the detached coroutine — otherwise a fast-failing
        // extraction can emit RUNNING/FAILED first and this report would overwrite the
        // terminal state, leaving the entry stuck in-flight until process restart.
        terminalRelay.workerListener.onUpdate(ExtractionStatus.PENDING, 0, null)
        val request = BackgroundExtractionRequest(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = retrievedHistory,
            entryAttemptCount = 0,
            timeoutMs = timeoutMs,
        )

        val extractionJob = scope.launch {
            runDetachedExtraction(entryId, entryText, capturedAt, request, terminalRelay, persona)
        }
        return SaveOutcome.Pending(entryId, extractionJob)
    }

    /**
     * Re-run the detached extraction pipeline for an existing PENDING entry. Used by
     * `AppContainer.recoverPendingExtractions` so typed entries persisted while the model was
     * absent get extracted once it becomes Ready, without duplicating the entry row.
     */
    suspend fun recoverEntry(
        entryId: Long,
        entryText: String,
        capturedAt: ZonedDateTime,
        persona: Persona = Persona.WITNESS,
    ): Job {
        val terminalRelay = DeferredTerminalRelay(listenerFactory(entryId))
        terminalRelay.workerListener.onUpdate(ExtractionStatus.PENDING, 0, null)
        val request = BackgroundExtractionRequest(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = emptyList(),
            entryAttemptCount = 0,
            timeoutMs = null,
        )
        return scope.launch {
            runDetachedExtraction(entryId, entryText, capturedAt, request, terminalRelay, persona)
        }
    }

    @Suppress("LongParameterList") // Carries the saveAndExtract call's full context.
    private suspend fun runDetachedExtraction(
        entryId: Long,
        entryText: String,
        capturedAt: ZonedDateTime,
        request: BackgroundExtractionRequest,
        terminalRelay: DeferredTerminalRelay,
        persona: Persona,
    ) {
        try {
            when (val result = worker.extract(request, terminalRelay.workerListener)) {
                is BackgroundExtractionResult.Success -> handleSuccess(
                    entryId = entryId,
                    entryText = entryText,
                    capturedAt = capturedAt,
                    entryAttemptCount = request.entryAttemptCount,
                    result = result,
                    terminalRelay = terminalRelay,
                    persona = persona,
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
        } catch (cancellation: CancellationException) {
            // Leave the entry in PENDING/RUNNING for the cold-start sweep; rethrow so
            // structured concurrency propagates the cancellation upward.
            throw cancellation
        } catch (compensated: PersistenceCompensatedException) {
            Log.w(
                TAG,
                "Detached extraction persistence already compensated for entryId=$entryId",
                compensated.cause,
            )
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Detached path has no caller to throw to — log + persist a terminal failure.
            Log.e(TAG, "Detached extraction failed for entryId=$entryId", error)
            compensatePersistenceFailure(entryId, request.entryAttemptCount, terminalRelay, error)
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
        persona: Persona,
    ) {
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
        // Runs after the terminal commit so a callout failure can't unwind the resolved
        // entry. Best-effort — failures are swallowed in runPatternOrchestration.
        runPatternOrchestration(entryId, persona)
        if (patternOrchestrator != null) {
            terminalRelay.emitTerminal(ExtractionStatus.COMPLETED, entryAttemptCount, null)
        }
    }

    private suspend fun runPatternOrchestration(entryId: Long, persona: Persona): EntryObservation? {
        val orchestrator = patternOrchestrator ?: return null
        return try {
            persistOrchestratorCallout(orchestrator, entryId, persona)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Best-effort layer; swallow so a pattern-detection failure doesn't fail the save.
            Log.w(
                TAG,
                "Pattern orchestration failed for entryId=$entryId: " +
                    "${error.javaClass.simpleName} ${error.message}",
            )
            null
        }
    }

    private suspend fun persistOrchestratorCallout(
        orchestrator: PatternDetectionOrchestrator,
        entryId: Long,
        persona: Persona,
    ): EntryObservation? {
        // Elvis-return locks `entry` as non-null without relying on `val`-flow inference. A
        // future refactor that splits the method or hoists `entry` to a `var` would otherwise
        // silently surface NPE risk through the settle calls below.
        val entry = entryStore.readEntry(entryId) ?: return null
        val callout = orchestrator.onEntryCommitted(entry, persona)
        if (callout != null) {
            appendAndConfirmCallout(orchestrator, entry, entryId, callout)
        }
        return callout
    }

    private suspend fun appendAndConfirmCallout(
        orchestrator: PatternDetectionOrchestrator,
        entry: dev.anchildress1.vestige.storage.EntryEntity,
        entryId: Long,
        callout: EntryObservation,
    ) {
        try {
            // Confirm inside the same write transaction as the markdown/ObjectBox append so
            // either both land or neither does.
            entryStore.appendObservation(entryId, callout) {
                orchestrator.settleReservedCallout(entry, fired = true)
            }
        } catch (cancellation: CancellationException) {
            orchestrator.settleReservedCallout(entry, fired = false)
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            orchestrator.settleReservedCallout(entry, fired = false)
            throw error
        }
    }

    private suspend fun handleFailure(
        entryId: Long,
        entryAttemptCount: Int,
        result: BackgroundExtractionResult.Failed,
        terminalRelay: DeferredTerminalRelay,
    ) {
        persistTerminalState(
            entryId = entryId,
            entryAttemptCount = entryAttemptCount,
            status = ExtractionStatus.FAILED,
            lastError = result.lastError,
            terminalRelay = terminalRelay,
        ) {
            entryStore.failEntry(entryId, ExtractionStatus.FAILED, result.lastError)
        }
    }

    private suspend fun handleTimeout(
        entryId: Long,
        entryAttemptCount: Int,
        result: BackgroundExtractionResult.TimedOut,
        terminalRelay: DeferredTerminalRelay,
    ) {
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
            throw PersistenceCompensatedException(error)
        }
    }

    private class PersistenceCompensatedException(cause: Exception) : Exception(cause)

    private companion object {
        private const val TAG = "VestigeSaveFlow"
    }
}

/**
 * Result of the two-tier save flow. [Pending] is the only post-refactor variant — the entry is
 * committed; extraction is in flight on the detached scope; callers observe terminal status via
 * the per-entry `ExtractionStatusListener` (typically `BackgroundExtractionStatusBus`). Tests
 * + tooling can await the embedded [extractionJob] to drain the detached work.
 */
sealed interface SaveOutcome {
    val entryId: Long

    data class Pending(override val entryId: Long, val extractionJob: Job) : SaveOutcome
}
