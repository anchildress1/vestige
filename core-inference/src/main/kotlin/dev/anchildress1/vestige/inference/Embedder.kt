package dev.anchildress1.vestige.inference

/** Process-scoped text→vector encoder. EmbeddingGemma yields 768 floats. */
interface Embedder {
    suspend fun embed(text: String): FloatArray
}
