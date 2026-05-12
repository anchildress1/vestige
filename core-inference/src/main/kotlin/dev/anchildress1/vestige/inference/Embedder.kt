package dev.anchildress1.vestige.inference

/**
 * Encode a single piece of text into a fixed-dimension vector. Implementations are
 * process-scoped (per `architecture-brief.md` §"AppContainer Ownership") and may hold
 * native resources — callers are responsible for [close]-ing on shutdown.
 *
 * Distinct runtime from [LiteRtLmEngine] — see
 * `adrs/ADR-010-embeddinggemma-runtime-switch-to-litert.md` §"Addendum (2026-05-11)".
 */
interface Embedder : AutoCloseable {
    /** Returns the embedding vector for [text]. EmbeddingGemma yields 768 floats. */
    suspend fun embed(text: String): FloatArray
}
