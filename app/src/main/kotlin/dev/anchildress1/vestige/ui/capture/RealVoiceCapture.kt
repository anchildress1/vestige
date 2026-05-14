package dev.anchildress1.vestige.ui.capture

import android.Manifest
import androidx.annotation.RequiresPermission
import dev.anchildress1.vestige.inference.AudioCapture
import dev.anchildress1.vestige.inference.AudioChunk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Production-facing source seam consumed by [RealVoiceCapture]. Decouples the adapter from
 * Android's `AudioRecord`-bound [AudioCapture] so JVM tests can swap a deterministic fake.
 */
interface AudioSource {
    fun captureChunks(): Flow<AudioChunk>
    fun requestStop()
}

/** Default factory — wraps a real [AudioCapture] that owns one `AudioRecord` handle. */
class AudioRecordSource(onLevel: (Float) -> Unit) : AudioSource {
    private val capture = AudioCapture(onLevel = onLevel)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun captureChunks(): Flow<AudioChunk> = capture.captureChunks()

    override fun requestStop() = capture.requestStop()
}

/**
 * Production [VoiceCapture] backed by a fresh [AudioSource] per recording. The supplied
 * `onLevel` callback receives a 0..1 RMS value for every `AudioRecord.read` chunk; the host VM
 * routes those into [AudioLevelMeter] for the live bar strip. `stopFlow` raises the stop signal
 * on tap-stop or discard. A new instance is created per recording session so the underlying
 * `AudioRecord` handle never leaks across sessions.
 */
class RealVoiceCapture(private val sourceFactory: ((Float) -> Unit) -> AudioSource = ::AudioRecordSource) :
    VoiceCapture {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun invoke(onLevel: (Float) -> Unit, stopFlow: Flow<Unit>): AudioChunk? = coroutineScope {
        val source = sourceFactory(onLevel)
        val stopJob = launch {
            stopFlow.first()
            source.requestStop()
        }
        try {
            source.captureChunks().firstOrNull()
        } finally {
            stopJob.cancel()
        }
    }
}
