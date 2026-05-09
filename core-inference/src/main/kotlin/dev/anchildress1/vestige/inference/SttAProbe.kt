package dev.anchildress1.vestige.inference

import com.google.ai.edge.litertlm.Content
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * STT-A audio plumbing harness (Story 1.5 — existential).
 *
 * Tries both `Content.AudioBytes` and `Content.AudioFile` paths so the on-device run can record
 * which one actually transcribes coherently against Gemma 4 E4B. Whichever wins gets pinned in
 * ADR-001 §Q4 by the human running the test.
 *
 * Lifecycle invariant: when the AudioFile path is used, the temp WAV is created, handed to the
 * model, and deleted in the same call. No audio is ever persisted as product data per AGENTS.md
 * guardrail 11.
 */
class SttAProbe(private val engine: LiteRtLmEngine) {

    /**
     * Pack [samples] as little-endian float32 bytes and send them via `Content.AudioBytes`. This
     * is the most likely packing for a Gemma 4 model that expects `[-1, 1]` floats; if it fails
     * coherence on the reference device, swap to [transcribeAudioFile] before declaring STT-A
     * blocked.
     */
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

    /**
     * Send a path to an existing audio file via `Content.AudioFile`. Caller owns the file's
     * lifecycle.
     */
    suspend fun transcribeAudioFile(path: String, prompt: String = DEFAULT_PROMPT): String {
        require(path.isNotBlank()) { "STT-A probe requires a non-blank audio file path." }
        return engine.sendMessageContents(
            listOf(
                Content.Text(prompt),
                Content.AudioFile(path),
            ),
        )
    }

    /**
     * Convenience: write [samples] as a temp PCM_FLOAT WAV in [cacheDir], hand it to Gemma, and
     * delete the temp file inside this call regardless of outcome.
     */
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
