package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Runs the three-lens background extraction per Story 2.6 — for each of [Lens.LITERAL],
 * [Lens.INFERENTIAL], [Lens.SKEPTICAL]: compose the system prompt (Story 2.5), call
 * [LiteRtLmEngine], parse the structured response, accumulate one [LensResult] per lens, and
 * hand the parsed extractions to [resolver] for convergence.
 *
 * **Sequential by design.** ADR-002 §"Why three calls and not one combined call" requires
 * statistical independence between lens outputs, but E4B is one model on one device — the lens
 * calls run one after another. Total wall-clock time per entry ≈ 3× single-lens latency.
 *
 * **Per-lens retry budget.** Each lens is attempted up to [maxAttemptsPerLens] times (default 2,
 * matching Story 2.6's "two consecutive failures on the same lens" cap). After exhausting its
 * budget, that lens contributes a `null` extraction — convergence treats that as "no opinion"
 * per ADR-002 §"Convergence edge cases" rather than blocking the entry. The entry-level
 * `attempt_count` (ADR-001 §Q3) is the sum of attempts across all three lenses.
 *
 * **Status surfacing.** [ExtractionStatusListener.onUpdate] fires on every transition the entry
 * persistence layer needs to mirror onto the `EntryEntity` row: one `RUNNING` at start, one
 * `RUNNING` per retry (with the latest `lastError`), and exactly one terminal call —
 * [ExtractionStatus.COMPLETED] when the resolver runs (≥1 lens succeeded), or
 * [ExtractionStatus.FAILED] when every lens exhausted its budget.
 *
 * **Storage isolation.** This module does not depend on `:core-storage`. The worker takes the
 * already-persisted entry text as input and emits a result the caller writes to ObjectBox /
 * markdown (Story 2.12).
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

    /**
     * Run the three-lens pipeline for [entryText]. [retrievedHistory] is forwarded to the prompt
     * composer (top three chunks, ~500-token cap per ADR-002 §Q2). [listener] receives every
     * status transition; the no-op default is fine for callers that read the result directly.
     */
    suspend fun extract(
        entryText: String,
        retrievedHistory: List<HistoryChunk> = emptyList(),
        listener: ExtractionStatusListener = NO_OP_LISTENER,
    ): BackgroundExtractionResult = withContext(ioDispatcher) {
        require(entryText.isNotBlank()) {
            "BackgroundExtractionWorker.extract requires a non-blank entryText"
        }

        val started = System.nanoTime()
        val state = RunState()
        listener.onUpdate(ExtractionStatus.RUNNING, 0, null)

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
            state.totalAttempts += 1
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
                listener.onUpdate(ExtractionStatus.RUNNING, state.totalAttempts, state.lastError)
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
    } catch (engineError: IllegalStateException) {
        recoverFromLensFailure(lens, attempt, engineError)
    } catch (engineError: IOException) {
        recoverFromLensFailure(lens, attempt, engineError)
    }

    /**
     * Lens-level engine failures (engine state, IO) become a "no opinion" attempt instead of
     * aborting the entry. ADR-002 §"Convergence edge cases" routes lens failures to convergence
     * rather than retrying indefinitely; the worker's per-lens retry budget covers transient
     * blips, the convergence resolver covers the rest.
     */
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
                    "(total_attempts=${state.totalAttempts} elapsed=${totalElapsedMs}ms last_error=$terminalError)",
            )
            listener.onUpdate(ExtractionStatus.FAILED, state.totalAttempts, terminalError)
            return BackgroundExtractionResult.Failed(
                totalElapsedMs = totalElapsedMs,
                lensResults = state.results,
                attemptCount = state.totalAttempts,
                lastError = terminalError,
            )
        }
        val resolved = resolver.resolve(parsedExtractions)
        Log.d(
            TAG,
            "extract completed: lenses=${parsedExtractions.size}/${LENSES.size} " +
                "total_attempts=${state.totalAttempts} elapsed=${totalElapsedMs}ms",
        )
        listener.onUpdate(ExtractionStatus.COMPLETED, state.totalAttempts, state.lastError)
        return BackgroundExtractionResult.Success(
            totalElapsedMs = totalElapsedMs,
            lensResults = state.results,
            attemptCount = state.totalAttempts,
            resolved = resolved,
        )
    }

    private class RunState {
        val results: MutableList<LensResult> = mutableListOf()
        var totalAttempts: Int = 0
        var lastError: String? = null
    }

    private data class AttemptOutcome(val raw: String, val error: String?)

    companion object {
        // ADR-001 §Q3 caps the entry-level retry budget at 3; per-lens we use 2 (one initial +
        // one retry) so a single bad lens can't burn the entry-level budget by itself.
        const val DEFAULT_MAX_ATTEMPTS_PER_LENS = 2

        private val LENSES: List<Lens> = listOf(Lens.LITERAL, Lens.INFERENTIAL, Lens.SKEPTICAL)
        private val NO_OP_LISTENER = ExtractionStatusListener { _, _, _ -> }
        private const val TAG = "VestigeBackgroundExtraction"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
