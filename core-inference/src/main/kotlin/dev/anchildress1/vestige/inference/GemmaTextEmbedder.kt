package dev.anchildress1.vestige.inference

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import com.google.ai.edge.localagents.rag.models.Embedder as SdkEmbedder

class GemmaTextEmbedder(
    modelPath: String,
    tokenizerPath: String,
    useGpu: Boolean = false,
    private val taskType: EmbedData.TaskType = EmbedData.TaskType.SEMANTIC_SIMILARITY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    delegateFactory: (String, String, Boolean) -> SdkEmbedder<String> = ::defaultDelegate,
) : Embedder {

    private val delegate: SdkEmbedder<String> = delegateFactory(modelPath, tokenizerPath, useGpu)

    override suspend fun embed(text: String): FloatArray {
        // Eager check on the caller's thread so the verify-no-SDK-call invariant is real on
        // every dispatcher.
        require(text.isNotBlank()) { "Embedder.embed requires non-blank text." }
        return withContext(ioDispatcher) {
            val request = EmbeddingRequest.create(
                listOf(EmbedData.create(text, taskType)),
            )
            val response = delegate.getEmbeddings(request).await()
            FloatArray(response.size) { i -> response[i] }
        }
    }

    private companion object {
        fun defaultDelegate(modelPath: String, tokenizerPath: String, useGpu: Boolean): SdkEmbedder<String> =
            GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)
    }
}
