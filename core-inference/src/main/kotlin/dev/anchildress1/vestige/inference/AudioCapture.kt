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
 * Mono 16 kHz PCM_FLOAT capture from `AudioRecord`, chunked at 30-second boundaries per
 * ADR-001 §Q4. Float samples land in `[-1, 1]` directly because `ENCODING_PCM_FLOAT` returns
 * normalized floats — no manual short→float conversion, so chunk boundaries cannot interleave
 * with re-encoding artifacts.
 *
 * Lifecycle: caller collects [captureChunks]; calls [requestStop] to flush the trailing chunk
 * and complete the flow. Hard cancellation (job.cancel) skips the trailing emission.
 *
 * This class never persists audio. Story 1.5's harness writes a temp WAV only when LiteRT-LM
 * needs `Content.AudioFile` and deletes it within the same call.
 */
class AudioCapture(
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    private val chunkDurationMs: Long = CHUNK_DURATION_MS,
) {
    private val stopRequested = AtomicBoolean(false)

    /** Stream chunks until [requestStop] or coroutine cancellation. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun captureChunks(): Flow<AudioChunk> = flow {
        stopRequested.set(false)
        val samplesPerChunk = (sampleRateHz.toLong() * chunkDurationMs / MS_PER_SECOND).toInt()
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize returned $minBufferBytes — device does not support " +
                "mono PCM_FLOAT at ${sampleRateHz}Hz."
        }
        val bufferBytes = maxOf(minBufferBytes, sampleRateHz * BYTES_PER_FLOAT)
        val readBuffer = FloatArray(bufferBytes / BYTES_PER_FLOAT)

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

        val builder = ChunkBuilder(samplesPerChunk)
        try {
            record.startRecording()
            while (!stopRequested.get() && currentCoroutineContext().isActive) {
                val read = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read < 0 -> error("AudioRecord.read returned error code $read")

                    read == 0 -> continue

                    else -> builder.append(readBuffer, read).forEach { samples ->
                        emit(AudioChunk(samples = samples, sampleRateHz = sampleRateHz, isFinal = false))
                    }
                }
            }
            builder.drainFinal()?.let { tail ->
                emit(AudioChunk(samples = tail, sampleRateHz = sampleRateHz, isFinal = true))
            }
        } finally {
            runCatching { record.stop() }.onFailure { Log.w(TAG, "stop() on un-started AudioRecord") }
            record.release()
        }
    }

    /** Request the next read loop to exit and the trailing chunk to be emitted. */
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
