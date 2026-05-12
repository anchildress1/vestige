package dev.anchildress1.vestige.inference

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.Embedder as SdkEmbedder
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Contract tests for [GemmaTextEmbedder]. The on-device cosine-similarity smoke (Story 3.2's
 * "cosine sim > 0.6" gate) runs as the instrumented `EmbeddingGemmaSmokeTest` in `:app` —
 * unit-level coverage exercises the SDK glue without needing the 179 MB `.tflite`.
 */
class GemmaTextEmbedderTest {

    @Test
    fun `embed forwards text and SEMANTIC_SIMILARITY task to the SDK and returns the vector`() = runTest {
        val captured = mutableListOf<EmbeddingRequest<String>>()
        val sdk = stubEmbedder(captureInto = captured, returns = listOf(0.1f, 0.2f, 0.3f))

        val embedder = GemmaTextEmbedder(
            modelPath = ANY_PATH,
            tokenizerPath = ANY_PATH,
            delegateFactory = { _, _, _ -> sdk },
        )

        val result = embedder.embed("I crashed at 3pm")

        assertEquals(3, result.size)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), result.toList())
        val embedData = captured.single().embedData.single()
        assertEquals("I crashed at 3pm", embedData.data)
        assertEquals(EmbedData.TaskType.SEMANTIC_SIMILARITY, embedData.task)
    }

    @Test
    fun `task type override flows through to the SDK request`() = runTest {
        val captured = mutableListOf<EmbeddingRequest<String>>()
        val sdk = stubEmbedder(captureInto = captured, returns = listOf(0.5f))

        val embedder = GemmaTextEmbedder(
            modelPath = ANY_PATH,
            tokenizerPath = ANY_PATH,
            taskType = EmbedData.TaskType.RETRIEVAL_QUERY,
            delegateFactory = { _, _, _ -> sdk },
        )

        embedder.embed("standup ran long")

        assertEquals(
            EmbedData.TaskType.RETRIEVAL_QUERY,
            captured.single().embedData.single().task,
        )
    }

    @Test
    fun `blank text is rejected before the SDK is touched`() {
        val sdk = mockk<SdkEmbedder<String>>(relaxed = true)
        val embedder = GemmaTextEmbedder(
            modelPath = ANY_PATH,
            tokenizerPath = ANY_PATH,
            delegateFactory = { _, _, _ -> sdk },
        )
        assertThrows(IllegalArgumentException::class.java) {
            runTest { embedder.embed("   ") }
        }
        verify(exactly = 0) { sdk.getEmbeddings(any()) }
    }

    @Test
    fun `each embed call returns a fresh FloatArray (no shared state)`() = runTest {
        val sdk = stubEmbedder(captureInto = mutableListOf(), returns = listOf(1f, 2f))
        val embedder = GemmaTextEmbedder(
            modelPath = ANY_PATH,
            tokenizerPath = ANY_PATH,
            delegateFactory = { _, _, _ -> sdk },
        )

        val first = embedder.embed("a")
        val second = embedder.embed("b")

        // FloatArray identity must not be shared — callers may mutate or stash the result.
        assertNotSame(first, second)
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `delegate factory receives constructor arguments verbatim`() {
        var capturedModel: String? = null
        var capturedTokenizer: String? = null
        var capturedGpu: Boolean? = null
        GemmaTextEmbedder(
            modelPath = "/data/model.tflite",
            tokenizerPath = "/data/spm.model",
            useGpu = true,
        ) { m, t, g ->
            capturedModel = m
            capturedTokenizer = t
            capturedGpu = g
            mockk(relaxed = true)
        }
        assertEquals("/data/model.tflite", capturedModel)
        assertEquals("/data/spm.model", capturedTokenizer)
        assertEquals(true, capturedGpu)
    }

    private fun stubEmbedder(
        captureInto: MutableList<EmbeddingRequest<String>>,
        returns: List<Float>,
    ): SdkEmbedder<String> {
        val sdk = mockk<SdkEmbedder<String>>()
        every { sdk.getEmbeddings(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            captureInto.add(firstArg() as EmbeddingRequest<String>)
            completedFuture(returns)
        }
        return sdk
    }

    private fun completedFuture(values: List<Float>): ListenableFuture<ImmutableList<Float>> =
        Futures.immediateFuture(ImmutableList.copyOf(values))

    private companion object {
        const val ANY_PATH = "/dev/null"
    }
}
