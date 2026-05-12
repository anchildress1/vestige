package dev.anchildress1.vestige

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.vestige.inference.GemmaTextEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Story 3.2 — EmbeddingGemma on-device smoke + cosine-similarity gate.
 *
 * Prerequisites — adb-push both artifacts to the device:
 *
 *   adb push embeddinggemma-300M_seq512_mixed-precision.tflite <BASE>/models/
 *   adb push sentencepiece.model <BASE>/models/
 *
 * where `<BASE>` is `/sdcard/Android/data/dev.anchildress1.vestige/files`. Then run:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.embeddingModelPath=<BASE>/models/embedding.tflite \
 *     -Pandroid.testInstrumentationRunnerArguments.embeddingTokenizerPath=<BASE>/models/sentencepiece.model
 *
 * (Where `embedding.tflite` resolves to `embeddinggemma-300M_seq512_mixed-precision.tflite`
 * per `core-model/src/main/resources/model/manifest.properties`.)
 *
 * If either argument is missing the test is skipped via [assumeTrue] so CI without the
 * artifacts stays green.
 *
 * The first assertion is the cosine-sim > 0.6 gate from Story 3.2's done-when list. The
 * second is the dimensionality check (768d per EmbeddingGemma 300M documentation).
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaSmokeTest {

    @Test
    fun semanticallyRelatedEntries_haveCosineSimilarityAboveSixTenths() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("embeddingModelPath")
        val tokenizerPath = args.getString("embeddingTokenizerPath")
        assumeTrue("embeddingModelPath instrumentation argument not provided", modelPath != null)
        assumeTrue("embeddingTokenizerPath instrumentation argument not provided", tokenizerPath != null)
        val modelFile = File(modelPath!!)
        val tokenizerFile = File(tokenizerPath!!)
        assumeTrue("Model file not found at $modelPath", modelFile.exists() && modelFile.canRead())
        assumeTrue("Tokenizer file not found at $tokenizerPath", tokenizerFile.exists() && tokenizerFile.canRead())

        val embedder = GemmaTextEmbedder(modelPath = modelPath, tokenizerPath = tokenizerPath)

        val vectorA = embedder.embed("I crashed at 3pm")
        val vectorB = embedder.embed("I felt overwhelmed at 3pm")

        assertEquals(
            "EmbeddingGemma 300M produces 768d vectors; mismatch suggests artifact corruption.",
            EMBEDDING_DIMENSIONS,
            vectorA.size,
        )
        assertEquals(EMBEDDING_DIMENSIONS, vectorB.size)

        val similarity = cosineSimilarity(vectorA, vectorB)
        val formatted = String.format(Locale.ROOT, "%.3f", similarity)
        assertTrue(
            "Cosine similarity $formatted below the 0.6 sanity floor.",
            similarity > MIN_COSINE_SIMILARITY,
        )
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have the same dimension." }
        var dot = 0f
        var magA = 0f
        var magB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        return dot / (sqrt(magA) * sqrt(magB))
    }

    private companion object {
        const val EMBEDDING_DIMENSIONS = 768
        const val MIN_COSINE_SIMILARITY = 0.6f
    }
}
