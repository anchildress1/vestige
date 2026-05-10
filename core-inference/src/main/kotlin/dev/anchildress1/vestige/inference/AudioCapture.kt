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
 * Mono 16 kHz PCM_FLOAT capture from `AudioRecord`, **hard-capped at 30 seconds per recording**
 * per ADR-001 §Q4 + the STT-B fallback's single-call-per-capture posture
 * (`adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"). Float samples land in
 * `[-1, 1]` directly because `ENCODING_PCM_FLOAT` returns normalized floats — no manual
 * short→float conversion, so the cap boundary cannot interleave with re-encoding artifacts.
 *
 * Lifecycle: caller collects [captureChunks]; calls [requestStop] to flush the chunk early and
 * complete the flow, OR the flow self-terminates when 30 s of audio have been buffered. Either
 * way the flow emits **exactly one** [AudioChunk] with `isFinal = true` and then completes. Hard
 * cancellation (job.cancel) skips the emission entirely.
 *
 * The >30 s multi-chunk orchestration (intermediate transcription-only call + final call with
 * concatenated transcript-so-far) was deferred to backlog row `multi-chunk-foreground` after the
 * STT-B fallback — v1 does not chunk. Audio past 30 s is silently truncated at the audio layer;
 * the UI (Phase 4) is responsible for showing the user the time remaining.
 *
 * This class never persists audio. Story 1.5's harness writes a temp WAV only when LiteRT-LM
 * needs `Content.AudioFile` and deletes it within the same call.
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
            var capReached = false
            while (!capReached && !stopRequested.get() && currentCoroutineContext().isActive) {
                val read = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read < 0 -> error("AudioRecord.read returned error code $read")

                    read == 0 -> continue

                    else -> {
                        val complete = builder.append(readBuffer, read)
                        if (complete.isNotEmpty()) {
                            // The 30 s cap fired. v1 emits the first complete chunk and drops any
                            // extras — multi-chunk orchestration is deferred to backlog
                            // `multi-chunk-foreground`, so we never buffer past 30 s.
                            if (complete.size > 1) {
                                Log.w(TAG, "30s cap fired; ${complete.size - 1} tail chunk(s) discarded.")
                            }
                            emit(AudioChunk(samples = complete.first(), sampleRateHz = sampleRateHz, isFinal = true))
                            capReached = true
                        }
                    }
                }
            }
            if (!capReached) {
                builder.drainFinal()?.let { tail ->
                    emit(AudioChunk(samples = tail, sampleRateHz = sampleRateHz, isFinal = true))
                }
            }
        } finally {
            runCatching { record.stop() }.onFailure { Log.w(TAG, "stop() on un-started AudioRecord") }
            record.release()
        }
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
