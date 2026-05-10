package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the three lenses sequentially against an already-persisted entry, retries each lens up to
 * [maxAttemptsPerLens] times, and reduces the parsed lens outputs through [resolver]. A lens
 * that exhausts its budget contributes a null extraction (convergence treats that as "no opinion").
 *
 * The caller threads the entry's persisted retry count in via `entryAttemptCount`; the worker
 * echoes it on every [ExtractionStatusListener] event. Lens-call volume is reported separately
 * on [BackgroundExtractionResult.modelCallCount] so the persisted `attempt_count` stays
 * sweep-level. Terminal `lastError` is `null` on `COMPLETED`, populated on `FAILED`.
 */
class BackgroundExtractionWorker(
    private val engine: LiteRtLmEngine,
    private val resolver: ConvergenceResolver,
    private val parser: (Lens, String) -> LensExtraction? = LensResponseParser::parse,
    private val composer: (Lens, String, List<HistoryChunk>) -> ComposedPrompt = PromptComposer::compose,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val maxAttemptsPerLens: Int = DEFAULT_MAX_ATTEMPTS_PER_LENS,
) {

    init {
        require(maxAttemptsPerLens >= 1) {
            "maxAttemptsPerLens must be ≥1 (got $maxAttemptsPerLens) — every lens needs at least one attempt."
        }
    }

    suspend fun extract(
        entryText: String,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        entryAttemptCount: Int = 0,
        listener: ExtractionStatusListener = NO_OP_LISTENER,
    ): BackgroundExtractionResult = withContext(ioDispatcher) {
        require(entryText.isNotBlank()) {
            "BackgroundExtractionWorker.extract requires a non-blank entryText"
        }
        require(entryAttemptCount >= 0) {
            "BackgroundExtractionWorker.extract requires entryAttemptCount >= 0"
        }

        val started = System.nanoTime()
        val state = RunState(entryAttemptCount)
        listener.onUpdate(ExtractionStatus.RUNNING, entryAttemptCount, null)

        for (lens in LENSES) {
            state.results += runLens(lens, entryText, retrievedHistory, state, listener)
        }

        val totalElapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        finalize(state, totalElapsedMs, listener)
    }

    private suspend fun runLens(
        lens: Lens,
        entryText: String,
        retrievedHistory: List<HistoryChunk>,
        state: RunState,
        listener: ExtractionStatusListener,
    ): LensResult {
        val lensStarted = System.nanoTime()
        var attempts = 0
        var lensError: String? = null
        var lastRaw = ""
        var parsed: LensExtraction? = null

        while (attempts < maxAttemptsPerLens && parsed == null) {
            attempts += 1
            state.modelCallCount += 1
            val composed = composer(lens, entryText, retrievedHistory)
            val attempt = attemptOnce(lens, composed, attempts)
            lastRaw = attempt.raw
            if (attempt.error != null) {
                lensError = attempt.error
                state.lastError = attempt.error
            } else {
                parsed = parser(lens, attempt.raw)
                if (parsed == null) {
                    lensError = "parse-fail"
                    state.lastError = lensError
                    Log.w(TAG, "lens=$lens attempt=$attempts parse-fail")
                }
            }
            if (parsed == null && attempts < maxAttemptsPerLens) {
                listener.onUpdate(ExtractionStatus.RUNNING, state.entryAttemptCount, state.lastError)
            }
        }

        val lensElapsedMs = (System.nanoTime() - lensStarted) / NANOS_PER_MILLI
        Log.d(TAG, "lens=$lens done parsed=${parsed != null} attempts=$attempts elapsed=${lensElapsedMs}ms")
        return LensResult(
            lens = lens,
            extraction = parsed,
            rawResponse = lastRaw,
            attemptCount = attempts,
            elapsedMs = lensElapsedMs,
            lastError = if (parsed != null) null else lensError ?: "parse-fail",
        )
    }

    private suspend fun attemptOnce(lens: Lens, composed: ComposedPrompt, attempt: Int): AttemptOutcome = try {
        AttemptOutcome(raw = engine.generateText(composed.text), error = null)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") engineError: Exception) {
        // Native LiteRT-LM throws unchecked types we can't enumerate; a narrow catch would let
        // a single lens crash the whole entry, breaking the "no opinion" contract.
        recoverFromLensFailure(lens, attempt, engineError)
    }

    private fun recoverFromLensFailure(lens: Lens, attempt: Int, error: Throwable): AttemptOutcome {
        val reason = "engine-error:${error.javaClass.simpleName}"
        Log.w(TAG, "lens=$lens attempt=$attempt failed (${error.message ?: reason})")
        return AttemptOutcome(raw = "", error = reason)
    }

    private suspend fun finalize(
        state: RunState,
        totalElapsedMs: Long,
        listener: ExtractionStatusListener,
    ): BackgroundExtractionResult {
        val parsedExtractions = state.results.mapNotNull(LensResult::extraction)
        if (parsedExtractions.isEmpty()) {
            val terminalError = state.lastError ?: "all-lenses-failed"
            Log.w(
                TAG,
                "extract failed: every lens exhausted its retry budget " +
                    "(model_calls=${state.modelCallCount} elapsed=${totalElapsedMs}ms last_error=$terminalError)",
            )
            listener.onUpdate(ExtractionStatus.FAILED, state.entryAttemptCount, terminalError)
            return BackgroundExtractionResult.Failed(
                totalElapsedMs = totalElapsedMs,
                lensResults = state.results,
                modelCallCount = state.modelCallCount,
                lastError = terminalError,
            )
        }
        val resolved = resolver.resolve(parsedExtractions)
        Log.d(
            TAG,
            "extract completed: lenses=${parsedExtractions.size}/${LENSES.size} " +
                "model_calls=${state.modelCallCount} elapsed=${totalElapsedMs}ms",
        )
        listener.onUpdate(ExtractionStatus.COMPLETED, state.entryAttemptCount, null)
        return BackgroundExtractionResult.Success(
            totalElapsedMs = totalElapsedMs,
            lensResults = state.results,
            modelCallCount = state.modelCallCount,
            resolved = resolved,
        )
    }

    private class RunState(val entryAttemptCount: Int) {
        val results: MutableList<LensResult> = mutableListOf()
        var modelCallCount: Int = 0
        var lastError: String? = null
    }

    private data class AttemptOutcome(val raw: String, val error: String?)

    companion object {
        const val DEFAULT_MAX_ATTEMPTS_PER_LENS = 2

        private val LENSES: List<Lens> = listOf(Lens.LITERAL, Lens.INFERENTIAL, Lens.SKEPTICAL)
        private val NO_OP_LISTENER = ExtractionStatusListener { _, _, _ -> }
        private const val TAG = "VestigeBackgroundExtraction"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
