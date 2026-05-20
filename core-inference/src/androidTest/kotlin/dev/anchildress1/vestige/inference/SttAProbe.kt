package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import java.io.File

/**
 * STT-A harness for on-device instrumentation tests.
 *
 * Kept in `androidTest` so release/runtime artifacts never carry this probe helper.
 */
class SttAProbe(private val engine: LiteRtLmEngine) {

    suspend fun transcribeAudioBytesAsWav(
        samples: FloatArray,
        sampleRateHz: Int,
        cacheDir: File,
        prompt: String = DEFAULT_PROMPT,
    ): String {
        require(samples.isNotEmpty()) { "STT-A probe requires non-empty audio samples." }
        require(cacheDir.isDirectory) { "cacheDir must be an existing directory: $cacheDir" }
        val temp = File.createTempFile("vestige-stt-a-bytes-", ".wav", cacheDir)
        try {
            WavWriter.writeMonoFloatWav(temp, samples, sampleRateHz)
            return engine.sendMessageContents(
                prompt,
                listOf(Content.AudioBytes(temp.readBytes())),
            )
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit()
            }
        }
    }

    /** Caller owns the file's lifecycle. */
    suspend fun transcribeAudioFile(path: String, prompt: String = DEFAULT_PROMPT): String {
        require(path.isNotBlank()) { "STT-A probe requires a non-blank audio file path." }
        return engine.sendMessageContents(
            prompt,
            listOf(Content.AudioFile(path)),
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
                prompt,
                listOf(Content.AudioFile(temp.absolutePath)),
            )
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit()
            }
        }
    }

    companion object {
        const val DEFAULT_PROMPT: String = "Transcribe the audio. Respond with only the spoken text."
    }
}
