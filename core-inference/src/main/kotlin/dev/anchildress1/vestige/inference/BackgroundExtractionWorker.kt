package dev.anchildress1.vestige.inference

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `capturedAt` carries the user's local zone at recording time. Pass a [ZonedDateTime] (not an
 * [java.time.Instant]) so the eventual `template_label` can't drift when the user changes
 * timezone or DST shifts between recording and the background extraction run.
 */
data class BackgroundExtractionRequest(
    val entryText: String,
    val capturedAt: ZonedDateTime,
    val retrievedHistory: List<HistoryChunk> = emptyList(),
    val entryAttemptCount: Int = 0,
    val timeoutMs: Long? = null,
)

/**
 * Runs the three lenses **concurrently** (ADR-008 §Correction 2026-05-16 — one Engine, three
 * independent SDK contexts; the single GPU still serializes at its command queue, so this is
 * non-blocking structure, not a literal 3× speedup) against an already-persisted entry, retries
 * each lens up to [maxAttemptsPerLens] times, and reduces the parsed lens outputs through
 * [resolver]. A lens that exhausts its budget contributes a null extraction (convergence treats
 * that as "no opinion"). `RUNNING` is emitted once at fan-out; the per-lens retry no longer
 * emits its own status (interleaved per-lens transitions would be meaningless under fan-out).
 *
 * The caller threads the entry's persisted retry count in via `entryAttemptCount`; the worker
 * echoes it on every [ExtractionStatusListener] event. Lens-call volume is reported separately
 * on [BackgroundExtractionResult.modelCallCount] so the persisted `attempt_count` stays
 * sweep-level. Terminal `lastError` is `null` on `COMPLETED`, populated on `FAILED` / `TIMED_OUT`.
 *
 * Pass `timeoutMs` to bound a hung native call — the wall clock measures from the first lens
 * call through resolver completion. On timeout the worker emits [ExtractionStatus.TIMED_OUT]
 * with whatever lens results completed before the cap.
 */
class BackgroundExtractionWorker(
    private val engine: LiteRtLmEngine,
    private val resolver: ConvergenceResolver,
    private val parser: (Lens, String) -> LensExtraction? = LensResponseParser::parse,
    private val composer: (Lens, String, List<HistoryChunk>) -> ComposedPrompt = PromptComposer::compose,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val maxAttemptsPerLens: Int = DEFAULT_MAX_ATTEMPTS_PER_LENS,
) {
    private val templateLabeler = TemplateLabeler()

    init {
        require(maxAttemptsPerLens >= 1) {
            "maxAttemptsPerLens must be ≥1 (got $maxAttemptsPerLens) — every lens needs at least one attempt."
        }
    }

    suspend fun extract(
        request: BackgroundExtractionRequest,
        listener: ExtractionStatusListener = NO_OP_LISTENER,
    ): BackgroundExtractionResult = withContext(ioDispatcher) {
        require(request.entryText.isNotBlank()) {
            "BackgroundExtractionWorker.extract requires a non-blank entryText"
        }
        require(request.entryAttemptCount >= 0) {
            "BackgroundExtractionWorker.extract requires entryAttemptCount >= 0"
        }
        require(request.timeoutMs == null || request.timeoutMs > 0) {
            "BackgroundExtractionWorker.extract requires timeoutMs > 0 (got ${request.timeoutMs})"
        }

        val started = System.nanoTime()
        // Lenses finish out of order under fan-out; this thread-safe accumulator lets the timeout
        // path still report whichever lenses completed before the cap (structured-concurrency
        // cancellation discards in-flight `async` results otherwise).
        val completed = CopyOnWriteArrayList<LensResult>()
        listener.onUpdate(ExtractionStatus.RUNNING, request.entryAttemptCount, null)

        try {
            withTimeoutOrNoCap(request.timeoutMs) {
                val results = coroutineScope {
                    LENSES.map { lens ->
                        async {
                            runLens(lens, request.entryText, request.retrievedHistory)
                                .also { completed += it }
                        }
                    }.awaitAll()
                }
                completeRun(results, request.entryAttemptCount, request.capturedAt, started, listener)
            }
        } catch (timeout: TimeoutCancellationException) {
            handleTimeout(
                completed = completed.toList(),
                entryAttemptCount = request.entryAttemptCount,
                startedNanos = started,
                timeoutMs = request.timeoutMs ?: 0L,
                listener = listener,
                cause = timeout,
            )
        }
    }

    private suspend inline fun <T> withTimeoutOrNoCap(timeoutMs: Long?, crossinline block: suspend () -> T): T =
        if (timeoutMs == null) block() else withTimeout(timeoutMs) { block() }

    // Pure per-lens runner: no shared state, no listener — three of these run concurrently, so
    // any cross-lens mutation would race. Diagnostics (modelCallCount, lastError) are derived
    // from the returned [LensResult]s after fan-out completes.
    private suspend fun runLens(lens: Lens, entryText: String, retrievedHistory: List<HistoryChunk>): LensResult {
        val lensStarted = System.nanoTime()
        var attempts = 0
        var lensError: String? = null
        var lastRaw = ""
        var parsed: LensExtraction? = null

        while (attempts < maxAttemptsPerLens && parsed == null) {
            attempts += 1
            val composed = composer(lens, entryText, retrievedHistory)
            val attempt = attemptOnce(lens, composed, attempts)
            lastRaw = attempt.raw
            if (attempt.error != null) {
                lensError = attempt.error
            } else {
                parsed = parser(lens, attempt.raw)
                if (parsed == null) {
                    lensError = "parse-fail"
                    Log.w(TAG, "lens=$lens attempt=$attempts parse-fail")
                }
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

    private suspend fun completeRun(
        results: List<LensResult>,
        entryAttemptCount: Int,
        capturedAt: ZonedDateTime,
        startedNanos: Long,
        listener: ExtractionStatusListener,
    ): BackgroundExtractionResult {
        val modelCallCount = results.sumOf { it.attemptCount }
        val lensLastError = results.firstNotNullOfOrNull { it.lastError }
        val parsedExtractions = results.mapNotNull(LensResult::extraction)
        val resolved = if (parsedExtractions.isEmpty()) {
            null
        } else {
            tryResolve(parsedExtractions, lensLastError)
        }
        val totalElapsedMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        return when {
            resolved is Resolution.Ok -> {
                val templateLabel = templateLabeler.label(resolved.value, capturedAt)
                Log.d(
                    TAG,
                    "extract completed: lenses=${parsedExtractions.size}/${LENSES.size} " +
                        "model_calls=$modelCallCount elapsed=${totalElapsedMs}ms",
                )
                listener.onUpdate(ExtractionStatus.COMPLETED, entryAttemptCount, null)
                BackgroundExtractionResult.Success(
                    totalElapsedMs = totalElapsedMs,
                    lensResults = results,
                    modelCallCount = modelCallCount,
                    resolved = resolved.value,
                    templateLabel = templateLabel,
                )
            }

            else -> {
                val terminalError = (resolved as? Resolution.Failure)?.error
                    ?: lensLastError
                    ?: "all-lenses-failed"
                Log.w(
                    TAG,
                    "extract failed (model_calls=$modelCallCount " +
                        "elapsed=${totalElapsedMs}ms last_error=$terminalError)",
                )
                listener.onUpdate(ExtractionStatus.FAILED, entryAttemptCount, terminalError)
                BackgroundExtractionResult.Failed(
                    totalElapsedMs = totalElapsedMs,
                    lensResults = results,
                    modelCallCount = modelCallCount,
                    lastError = terminalError,
                )
            }
        }
    }

    private fun tryResolve(parsed: List<LensExtraction>, currentLastError: String?): Resolution = try {
        Resolution.Ok(resolver.resolve(parsed))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") resolverError: Exception) {
        val terminalError = "resolver-error:${resolverError.javaClass.simpleName}"
        Log.w(TAG, "resolver failed (${resolverError.message ?: terminalError}); prior last_error=$currentLastError")
        Resolution.Failure(terminalError)
    }

    private sealed interface Resolution {
        data class Ok(val value: dev.anchildress1.vestige.model.ResolvedExtraction) : Resolution
        data class Failure(val error: String) : Resolution
    }

    @Suppress("LongParameterList") // Timeout-path diagnostics: partial results + counters + cause.
    private suspend fun handleTimeout(
        completed: List<LensResult>,
        entryAttemptCount: Int,
        startedNanos: Long,
        timeoutMs: Long,
        listener: ExtractionStatusListener,
        cause: TimeoutCancellationException,
    ): BackgroundExtractionResult.TimedOut {
        val totalElapsedMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        val terminalError = "timeout-after-${timeoutMs}ms"
        Log.w(TAG, "extract timed out (${cause.message ?: terminalError})")
        listener.onUpdate(ExtractionStatus.TIMED_OUT, entryAttemptCount, terminalError)
        return BackgroundExtractionResult.TimedOut(
            totalElapsedMs = totalElapsedMs,
            lensResults = completed,
            modelCallCount = completed.sumOf { it.attemptCount },
            timeoutMs = timeoutMs,
        )
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
