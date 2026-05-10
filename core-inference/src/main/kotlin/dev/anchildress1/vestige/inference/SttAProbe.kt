package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio plumbing harness — exercises both `Content.AudioBytes` and `Content.AudioFile` paths so
 * the on-device run can record which one transcribes coherently against E4B. Temp WAV is
 * always deleted in the same call.
 */
class SttAProbe(private val engine: LiteRtLmEngine) {

    suspend fun transcribeAudioBytesAsFloat32Le(samples: FloatArray, prompt: String = DEFAULT_PROMPT): String {
        require(samples.isNotEmpty()) { "STT-A probe requires non-empty audio samples." }
        val bytes = floatsToLittleEndianBytes(samples)
        return engine.sendMessageContents(
            listOf(
                Content.Text(prompt),
                Content.AudioBytes(bytes),
            ),
        )
    }

    /** Caller owns the file's lifecycle. */
    suspend fun transcribeAudioFile(path: String, prompt: String = DEFAULT_PROMPT): String {
        require(path.isNotBlank()) { "STT-A probe requires a non-blank audio file path." }
        return engine.sendMessageContents(
            listOf(
                Content.Text(prompt),
                Content.AudioFile(path),
            ),
        )
    }

    /** Writes [samples] as a temp PCM_S16LE WAV, transcribes, and always deletes the file. */
    suspend fun transcribeViaTempWav(
        samples: FloatArray,
        sampleRateHz: Int,
        cacheDir: File,
        prompt: String = DEFAULT_PROMPT,
    ): String {
        require(samples.isNotEmpty()) { "STT-A probe requires non-empty audio samples." }
        require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }
        val temp = File.createTempFile("vestige-stt-a-", ".wav", cacheDir)
        try {
            WavWriter.writeMonoFloatWav(temp, samples, sampleRateHz)
            return engine.sendMessageContents(
                listOf(
                    Content.Text(prompt),
                    Content.AudioFile(temp.absolutePath),
                ),
            )
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit()
            }
        }
    }

    private fun floatsToLittleEndianBytes(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    companion object {
        const val DEFAULT_PROMPT: String = "Transcribe the audio. Respond with only the spoken text."
        private const val BYTES_PER_FLOAT = 4
    }
}
