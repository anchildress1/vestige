package dev.anchildress1.vestige.inference

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mono 16 kHz PCM_FLOAT capture from `AudioRecord`, hard-capped at 30 s per recording per
 * ADR-001 §Q4. `ENCODING_PCM_FLOAT` returns normalized `[-1, 1]` floats directly so the cap
 * boundary cannot interleave with re-encoding artifacts.
 *
 * Lifecycle: caller collects [captureChunks]; either calls [requestStop] OR the flow self-
 * terminates at the cap. Either path emits exactly one [AudioChunk] (`isFinal = true`) then
 * completes. Hard cancellation (job.cancel) skips the emission. Audio past 30 s is silently
 * truncated at this layer; the UI owns the time-remaining indicator. The deferred >30 s
 * orchestration lives in backlog row `multi-chunk-foreground`.
 *
 * Never persists audio. The temp WAV that LiteRT-LM needs is owned by [ForegroundInference]
 * and deleted inside its own call.
 */
class AudioCapture(
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    private val chunkDurationMs: Long = CHUNK_DURATION_MS,
) {
    private val stopRequested = AtomicBoolean(false)

    /**
     * Stream the single capture chunk. Completes after one emission, triggered by either
     * [requestStop] or the 30 s hard cap (whichever fires first). Hard cancellation skips the
     * emission.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun captureChunks(): Flow<AudioChunk> = flow {
        stopRequested.set(false)
        val samplesPerChunk = (sampleRateHz.toLong() * chunkDurationMs / MS_PER_SECOND).toInt()
        val bufferBytes = resolveBufferBytes()
        val readBuffer = FloatArray(bufferBytes / BYTES_PER_FLOAT)
        val record = openAudioRecord(bufferBytes)
        val builder = ChunkBuilder(samplesPerChunk)
        try {
            record.startRecording()
            val capChunk = readUntilCapOrStop(record, readBuffer, builder)
            val finalChunk = capChunk ?: builder.drainFinal()?.let { tail ->
                AudioChunk(samples = tail, sampleRateHz = sampleRateHz, isFinal = true)
            }
            if (finalChunk != null) {
                emit(finalChunk)
            }
        } finally {
            runCatching { record.stop() }.onFailure { Log.w(TAG, "stop() on un-started AudioRecord") }
            record.release()
        }
    }

    /**
     * Returns the cap chunk if the 30 s window completes, or `null` on [requestStop] /
     * coroutine cancellation (caller drains the partial buffer). `internal` for JVM testability,
     * not public API.
     */
    internal suspend fun readUntilCapOrStop(
        record: AudioRecord,
        readBuffer: FloatArray,
        builder: ChunkBuilder,
    ): AudioChunk? {
        while (!stopRequested.get() && currentCoroutineContext().isActive) {
            val read = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) error("AudioRecord.read returned error code $read")
            val chunk = if (read > 0) tryBuildCapChunk(builder, readBuffer, read) else null
            if (chunk != null) return chunk
        }
        return null
    }

    /**
     * Returns the cap chunk if [builder] completes a window from [readCount] samples, otherwise
     * null. Extras (multi-chunk readouts) are discarded with a WARN — v1 never buffers past the
     * cap; the deferred orchestration lives in backlog row `multi-chunk-foreground`. `internal`
     * for JVM testability, not public API.
     */
    internal fun tryBuildCapChunk(builder: ChunkBuilder, readBuffer: FloatArray, readCount: Int): AudioChunk? {
        val complete = builder.append(readBuffer, readCount)
        if (complete.isEmpty()) return null
        if (complete.size > 1) {
            Log.w(TAG, "30s cap fired; ${complete.size - 1} tail chunk(s) discarded.")
        }
        return AudioChunk(samples = complete.first(), sampleRateHz = sampleRateHz, isFinal = true)
    }

    private fun resolveBufferBytes(): Int {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize returned $minBufferBytes — device does not support " +
                "mono PCM_FLOAT at ${sampleRateHz}Hz."
        }
        return maxOf(minBufferBytes, sampleRateHz * BYTES_PER_FLOAT)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openAudioRecord(bufferBytes: Int): AudioRecord {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize for ${sampleRateHz}Hz mono PCM_FLOAT."
        }
        return record
    }

    /** Request the read loop to exit and the partial chunk to be emitted as the final chunk. */
    fun requestStop() {
        stopRequested.set(true)
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val CHUNK_DURATION_MS: Long = 30_000L
        private const val BYTES_PER_FLOAT: Int = 4
        private const val MS_PER_SECOND: Long = 1_000L
        private const val TAG = "VestigeAudioCapture"
    }
}

/**
 * Captured slice of audio destined for LiteRT-LM. The Gemma audio spec wants mono 16 kHz
 * float32 in `[-1, 1]`; emit as-is without resampling.
 */
data class AudioChunk(val samples: FloatArray, val sampleRateHz: Int, val isFinal: Boolean) {
    /** Duration of this chunk in milliseconds. */
    val durationMs: Long get() = samples.size * 1_000L / sampleRateHz

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return isFinal == other.isFinal &&
            sampleRateHz == other.sampleRateHz &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + isFinal.hashCode()
        return result
    }
}
