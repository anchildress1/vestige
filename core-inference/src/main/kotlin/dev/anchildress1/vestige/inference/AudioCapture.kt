package dev.anchildress1.vestige.inference

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mono 16 kHz PCM_FLOAT capture, hard-capped at 30 s per recording. The flow emits exactly one
 * `isFinal=true` chunk on either [requestStop] or the cap, then completes; hard cancellation
 * skips the emission. Never persists audio.
 *
 * [onLevel] (optional) receives a 0..1 RMS level for every successful `AudioRecord.read` so the
 * UI can drive a live meter while a recording is in flight. The callback is invoked synchronously
 * on the capture coroutine; consumers must keep it fast (no allocations / no IO). Samples are not
 * exposed past this callback — even the RMS hand-off is a one-way derivation.
 */
class AudioCapture(
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    private val chunkDurationMs: Long = CHUNK_DURATION_MS,
    private val onLevel: ((Float) -> Unit)? = null,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val stopRequested = AtomicBoolean(false)

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
    }.flowOn(captureDispatcher)
    // `flowOn` is non-negotiable here: `AudioRecord.read(... READ_BLOCKING)` parks the calling
    // thread between buffer fills. Collected on Main, the read loop ANRs the UI within seconds
    // and Android force-stops the process. The dispatcher seam stays injectable so JVM tests
    // can pin a TestDispatcher without needing a real `AudioRecord`.

    // `internal` for JVM testability. Returns the cap chunk if the window fills; null on stop
    // or cancellation (caller drains the partial buffer).
    internal suspend fun readUntilCapOrStop(
        record: AudioRecord,
        readBuffer: FloatArray,
        builder: ChunkBuilder,
    ): AudioChunk? {
        while (!stopRequested.get() && currentCoroutineContext().isActive) {
            val read = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) error("AudioRecord.read returned error code $read")
            if (read > 0) {
                onLevel?.invoke(rmsLevel(readBuffer, read))
                val chunk = tryBuildCapChunk(builder, readBuffer, read)
                if (chunk != null) return chunk
            }
        }
        return null
    }

    // `internal` for JVM testability. RMS over the just-read window, clamped to [0, 1].
    internal fun rmsLevel(samples: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        val limit = if (count > samples.size) samples.size else count
        var sumSq = 0.0
        for (i in 0 until limit) {
            val s = samples[i]
            sumSq += (s * s).toDouble()
        }
        val raw = kotlin.math.sqrt(sumSq / limit).toFloat()
        return raw.coerceIn(0f, 1f)
    }

    // `internal` for JVM testability. Multi-chunk readouts past the cap are dropped with a WARN.
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

/** Mono 16 kHz float32 in `[-1, 1]`, headed straight to LiteRT-LM. */
data class AudioChunk(val samples: FloatArray, val sampleRateHz: Int, val isFinal: Boolean) {
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
