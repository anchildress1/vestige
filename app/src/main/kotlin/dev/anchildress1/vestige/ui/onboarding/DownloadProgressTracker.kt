package dev.anchildress1.vestige.ui.onboarding

import android.util.Log
import dev.anchildress1.vestige.model.ModelArtifactState

/**
 * Wraps the per-tick progress bookkeeping for the onboarding download. Splitting speed sampling
 * and pct logging into their own methods keeps the orchestrator's cognitive complexity within
 * S3776's limit and lets each helper be reasoned about in isolation.
 *
 * The sample baseline is set on the FIRST `onProgress` callback (not zero-initialised) because
 * HTTP-Range resume reports the .part file's existing length as the first `currentBytes` — a
 * zero baseline would emit an absurd MB/s spike before the next chunk arrives.
 */
internal class DownloadProgressTracker(
    private val onState: (ModelArtifactState) -> Unit,
    private val onSpeed: (Float?) -> Unit,
    private val onEta: (Long?) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastPct = -1
    private var sampleBytes = -1L
    private var sampleTimeMs = 0L
    private var lastBytesPerSec = -1.0

    fun onProgress(currentBytes: Long, expectedBytes: Long) {
        onState(ModelArtifactState.Partial(currentBytes, expectedBytes))
        sampleSpeed(currentBytes)
        emitEta(currentBytes, expectedBytes)
        logPercent(currentBytes, expectedBytes)
    }

    private fun sampleSpeed(currentBytes: Long) {
        val now = nowMillis()
        if (sampleBytes < 0L) {
            sampleBytes = currentBytes
            sampleTimeMs = now
            return
        }
        val elapsed = now - sampleTimeMs
        if (elapsed < SPEED_SAMPLE_INTERVAL_MS) return
        val deltaBytes = (currentBytes - sampleBytes).coerceAtLeast(0L)
        val mbps = (deltaBytes.toFloat() / BYTES_PER_MB) / (elapsed.toFloat() / MS_PER_SECOND)
        onSpeed(mbps.coerceAtLeast(0f))
        lastBytesPerSec = deltaBytes.toDouble() / (elapsed.toDouble() / MS_PER_SECOND)
        sampleBytes = currentBytes
        sampleTimeMs = now
    }

    // ETA rides the last good speed sample. Before any sample (or a zero-rate window) the
    // remaining time is genuinely unknown — emit null rather than a fabricated number.
    private fun emitEta(currentBytes: Long, expectedBytes: Long) {
        if (lastBytesPerSec <= 0.0) {
            onEta(null)
            return
        }
        val remaining = (expectedBytes - currentBytes).coerceAtLeast(0L)
        onEta((remaining / lastBytesPerSec).toLong())
    }

    private fun logPercent(currentBytes: Long, expectedBytes: Long) {
        val pct = if (expectedBytes > 0L) {
            ((currentBytes * PERCENT_LOG_SCALE) / expectedBytes).toInt()
        } else {
            -1
        }
        if (pct != lastPct) {
            lastPct = pct
            Log.d(ONBOARDING_TAG, "download progress $currentBytes/$expectedBytes ($pct%)")
        }
    }
}
