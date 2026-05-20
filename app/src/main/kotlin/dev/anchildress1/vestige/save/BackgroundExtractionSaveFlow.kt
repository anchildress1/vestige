package dev.anchildress1.vestige.save

import android.util.Log
import dev.anchildress1.vestige.inference.BackgroundExtractionRequest
import dev.anchildress1.vestige.inference.BackgroundExtractionResult
import dev.anchildress1.vestige.inference.BackgroundExtractionWorker
import dev.anchildress1.vestige.inference.ExtractionStatusListener
import dev.anchildress1.vestige.inference.HistoryChunk
import dev.anchildress1.vestige.inference.LensResult
import dev.anchildress1.vestige.inference.ObservationGenerator
import dev.anchildress1.vestige.model.EntryLensReceipt
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

data class BackgroundExtractionLifecycleCallbacks(
    val listenerFactory: (Long) -> ExtractionStatusListener,
    val onEntryFinalized: (Long) -> Unit = {},
    val onPatternCalloutAppended: (Long) -> Unit = {},
)

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
 *   8. Detached: success-only derived-work scheduling fires, then [onEntryFinalized] fires for
 *      follow-ons like vector backfill. Pattern callout work continues on its own coroutine and
 *      reports [onPatternCalloutAppended] when it changes entry-visible state.
 */
@Suppress(
    "TooManyFunctions", // Pipeline + handlers + helpers; splitting hides the linear flow.
    "LongParameterList", // Wiring object: seams are explicit and cheaper than a fake config carrier.
)
class BackgroundExtractionSaveFlow(
    private val entryStore: EntryStore,
    private val worker: BackgroundExtractionWorker,
    private val observationGenerator: ObservationGenerator,
    private val lifecycleCallbacks: BackgroundExtractionLifecycleCallbacks,
    private val scope: CoroutineScope,
    private val retrieveHistory: suspend (String) -> List<HistoryChunk> = { emptyList() },
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
        followUpText: String? = null,
    ): SaveOutcome.Pending {
        val entryId = entryStore.createPendingEntry(
            entryText = entryText,
            timestamp = capturedAt.toInstant(),
            durationMs = durationMs,
            followUpText = followUpText,
            persona = persona,
        )
        val terminalRelay = DeferredTerminalRelay(lifecycleCallbacks.listenerFactory(entryId))
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
        timeoutMs: Long? = null,
    ): Job {
        val terminalRelay = DeferredTerminalRelay(lifecycleCallbacks.listenerFactory(entryId))
        terminalRelay.workerListener.onUpdate(ExtractionStatus.PENDING, 0, null)
        val request = BackgroundExtractionRequest(
            entryText = entryText,
            capturedAt = capturedAt,
            retrievedHistory = emptyList(),
            entryAttemptCount = 0,
            timeoutMs = timeoutMs,
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
            val requestWithHistory = request.copy(
                retrievedHistory = resolveRetrievedHistory(entryId, entryText, request.retrievedHistory),
            )
            when (val result = worker.extract(requestWithHistory, terminalRelay.workerListener)) {
                is BackgroundExtractionResult.Success -> handleSuccess(
                    entryId = entryId,
                    entryText = entryText,
                    capturedAt = capturedAt,
                    entryAttemptCount = requestWithHistory.entryAttemptCount,
                    result = result,
                    terminalRelay = terminalRelay,
                    persona = persona,
                )

                is BackgroundExtractionResult.Failed -> handleFailure(
                    entryId = entryId,
                    entryAttemptCount = requestWithHistory.entryAttemptCount,
                    result = result,
                    terminalRelay = terminalRelay,
                )

                is BackgroundExtractionResult.TimedOut -> handleTimeout(
                    entryId = entryId,
                    entryAttemptCount = requestWithHistory.entryAttemptCount,
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

    private suspend fun resolveRetrievedHistory(
        entryId: Long,
        entryText: String,
        seededHistory: List<HistoryChunk>,
    ): List<HistoryChunk> {
        if (seededHistory.isNotEmpty()) return seededHistory
        return try {
            retrieveHistory(entryText)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Detached retrieval degraded for entryId=$entryId (${error.javaClass.simpleName})")
            emptyList()
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
        val observations = runObservations(entryId, entryText, result, capturedAt)
        persistTerminalState(
            entryId = entryId,
            entryAttemptCount = entryAttemptCount,
            status = ExtractionStatus.COMPLETED,
            lastError = null,
            terminalRelay = terminalRelay,
        ) {
            entryStore.completeEntry(
                entryId,
                result.resolved,
                result.templateLabel,
                observations,
                result.lensResults.toReceipts(),
            )
        }
        schedulePatternOrchestration(entryId, persona)
        runEntryFinalization(entryId)
    }

    private fun schedulePatternOrchestration(entryId: Long, persona: Persona) {
        val orchestrator = patternOrchestrator ?: return
        scope.launch {
            runPatternOrchestration(orchestrator, entryId, persona)
        }
    }

    private suspend fun runPatternOrchestration(
        orchestrator: PatternDetectionOrchestrator,
        entryId: Long,
        persona: Persona,
    ) {
        try {
            val callout = persistOrchestratorCallout(orchestrator, entryId, persona)
            if (callout != null) {
                reportPatternCalloutAppended(entryId)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Best-effort layer; swallow so a pattern-detection failure doesn't fail the save.
            // Log.e + throwable so the stacktrace survives — a swallowed pattern bug should be
            // recoverable from logs, not require reproducing the failure on-device.
            Log.e(TAG, "Pattern orchestration failed for entryId=$entryId: ${error.javaClass.simpleName}", error)
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
        val entry = entryStore.readEntry(entryId) ?: run {
            Log.w(TAG, "persistOrchestratorCallout: entry $entryId not found — skipping callout")
            return null
        }
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

    private fun runEntryFinalization(entryId: Long) {
        try {
            lifecycleCallbacks.onEntryFinalized(entryId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Post-save follow-ons must never rewrite a persisted COMPLETED entry into a
            // failure. Log and move on; the next save / cold start can retrigger downstream work.
            Log.w(TAG, "onEntryFinalized failed for entryId=$entryId: ${error.javaClass.simpleName}", error)
        }
    }

    private fun reportPatternCalloutAppended(entryId: Long) {
        try {
            lifecycleCallbacks.onPatternCalloutAppended(entryId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "onPatternCalloutAppended failed for entryId=$entryId: ${error.javaClass.simpleName}", error)
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
        entryId: Long,
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
        Log.w(TAG, "ObservationGenerator threw ${error.javaClass.simpleName} for entryId=$entryId", error)
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
                "Persistence compensation failed for entryId=$entryId",
                compensationError,
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

private fun List<LensResult>.toReceipts(): List<EntryLensReceipt> = map { result ->
    EntryLensReceipt(
        lens = result.lens,
        extracted = result.extraction != null,
        fields = result.extraction?.fields.orEmpty(),
        flags = result.extraction?.flags.orEmpty(),
        attemptCount = result.attemptCount,
        elapsedMs = result.elapsedMs,
        lastError = result.lastError,
    )
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
