package dev.anchildress1.vestige.inference

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import com.google.ai.edge.localagents.rag.models.Embedder as SdkEmbedder

/**
 * Wraps `GemmaEmbeddingModel` from `com.google.ai.edge.localagents:localagents-rag`. The SDK's
 * bundled `libgemma_embedding_model_jni.so` statically links LiteRT TFLite + SentencePiece, so
 * this class is the only seam to that runtime — `LiteRtLmEngine` (chat / audio) stays untouched.
 *
 * Constructor accepts pre-verified absolute paths to the EmbeddingGemma `.tflite` and the
 * paired `sentencepiece.model`. Path verification lives in the caller (`ModelArtifactStore`
 * SHA-256 check); this class is dumb-once-paths-are-good.
 *
 * Authorized by `adrs/ADR-010-embeddinggemma-runtime-switch-to-litert.md` §"Addendum
 * (2026-05-11)".
 */
class GemmaTextEmbedder(
    modelPath: String,
    tokenizerPath: String,
    useGpu: Boolean = false,
    private val taskType: EmbedData.TaskType = EmbedData.TaskType.SEMANTIC_SIMILARITY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    delegateFactory: (String, String, Boolean) -> SdkEmbedder<String> = ::defaultDelegate,
) : Embedder {

    private val delegate: SdkEmbedder<String> = delegateFactory(modelPath, tokenizerPath, useGpu)

    override suspend fun embed(text: String): FloatArray = withContext(ioDispatcher) {
        require(text.isNotBlank()) { "Embedder.embed requires non-blank text." }
        val request = EmbeddingRequest.create(
            listOf(EmbedData.create(text, taskType)),
        )
        val response = delegate.getEmbeddings(request).await()
        FloatArray(response.size) { i -> response[i] }
    }

    override fun close() {
        // GemmaEmbeddingModel does not expose a public close; the JNI handle is released when
        // the delegate is GC'd. Holding the reference for the process lifetime is the intended
        // shape — AppContainer treats `Embedder` as a process-scoped singleton.
    }

    private companion object {
        fun defaultDelegate(modelPath: String, tokenizerPath: String, useGpu: Boolean): SdkEmbedder<String> =
            GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)
    }
}
