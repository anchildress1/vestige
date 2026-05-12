package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class EmbeddingArtifactManifestTest {

    @Test
    fun `loadDefault parses both artifact rows from the checked-in manifest`() {
        val manifest = EmbeddingArtifactManifest.loadDefault()
        assertEquals(EmbeddingArtifactManifest.SUPPORTED_SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("litert-community/embeddinggemma-300m", manifest.artifactRepo)
        assertEquals("embeddinggemma-300M_seq512_mixed-precision.tflite", manifest.model.filename)
        assertEquals("sentencepiece.model", manifest.tokenizer.filename)
        assertEquals(179_132_472L, manifest.model.expectedByteSize)
        assertEquals(4_683_319L, manifest.tokenizer.expectedByteSize)
        assertEquals(
            listOf("huggingface.co", "cas-bridge.xethub.hf.co"),
            manifest.allowedHosts,
        )
    }

    @Test
    fun `loadDefault reports resolved once the download probe has pinned SHA-256 + sizes`() {
        val manifest = EmbeddingArtifactManifest.loadDefault()
        assertTrue(manifest.isResolved)
        assertTrue(manifest.model.sha256IsResolved)
        assertTrue(manifest.tokenizer.sha256IsResolved)
        // Both SHA-256s must be 64 lowercase hex chars.
        assertEquals(SHA256_HEX_LENGTH, manifest.model.sha256.length)
        assertEquals(SHA256_HEX_LENGTH, manifest.tokenizer.sha256.length)
        assertTrue(manifest.model.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(manifest.tokenizer.sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `pending probe values surface as unresolved via fromProperties`() {
        // The baseProps() fixture leaves both rows at PENDING_PHASE_3_DOWNLOAD_PROBE — the
        // data class must still parse, but isResolved must be false so callers gate Embedder
        // instantiation on it.
        val manifest = EmbeddingArtifactManifest.fromProperties(baseProps())
        assertFalse(manifest.isResolved)
        assertFalse(manifest.model.sha256IsResolved)
        assertFalse(manifest.tokenizer.sha256IsResolved)
        assertEquals(null, manifest.model.expectedByteSize)
        assertEquals(null, manifest.tokenizer.expectedByteSize)
    }

    @Test
    fun `fromProperties accepts pinned values and reports resolved`() {
        val props = baseProps().apply {
            setProperty("embedding_artifact_expected_byte_size", "187746000")
            setProperty(
                "embedding_artifact_sha256",
                "0".repeat(SHA256_HEX_LENGTH),
            )
            setProperty("embedding_tokenizer_expected_byte_size", "4907968")
            setProperty(
                "embedding_tokenizer_sha256",
                "f".repeat(SHA256_HEX_LENGTH),
            )
        }
        val manifest = EmbeddingArtifactManifest.fromProperties(props)
        assertTrue(manifest.isResolved)
        assertEquals(187746000L, manifest.model.expectedByteSize)
        assertEquals(4907968L, manifest.tokenizer.expectedByteSize)
    }

    @Test
    fun `fromProperties rejects unsupported schema`() {
        val props = baseProps().apply { setProperty("schema_version", "999") }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingArtifactManifest.fromProperties(props)
        }
    }

    @Test
    fun `fromProperties rejects missing embedding keys`() {
        // Strip every embedding_artifact_* row to confirm absence is loud, not silent.
        val props = baseProps().apply { remove("embedding_artifact_repo") }
        val ex = assertThrows(IllegalStateException::class.java) {
            EmbeddingArtifactManifest.fromProperties(props)
        }
        assertTrue(ex.message?.contains("embedding_artifact_repo") == true)
    }

    @Test
    fun `fromProperties rejects non-numeric size that is not the pending token`() {
        val props = baseProps().apply {
            setProperty("embedding_artifact_expected_byte_size", "not-a-number")
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            EmbeddingArtifactManifest.fromProperties(props)
        }
        assertTrue(ex.message?.contains("embedding_artifact_expected_byte_size") == true)
    }

    @Test
    fun `loadFromResource throws when path does not exist`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            EmbeddingArtifactManifest.loadFromResource("/model/does-not-exist.properties")
        }
        assertTrue(ex.message?.contains("does-not-exist") == true)
    }

    private fun baseProps(): Properties = Properties().apply {
        val repoBase = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main"
        val pending = EmbeddingArtifactManifest.PENDING_PROBE_TOKEN
        setProperty("schema_version", "2")
        setProperty("embedding_artifact_repo", "litert-community/embeddinggemma-300m")
        setProperty("embedding_artifact_filename", "embeddinggemma-300M_seq512_mixed-precision.tflite")
        setProperty(
            "embedding_artifact_download_url",
            "$repoBase/embeddinggemma-300M_seq512_mixed-precision.tflite",
        )
        setProperty("embedding_artifact_expected_byte_size", pending)
        setProperty("embedding_artifact_sha256", pending)
        setProperty("embedding_tokenizer_filename", "sentencepiece.model")
        setProperty("embedding_tokenizer_download_url", "$repoBase/sentencepiece.model")
        setProperty("embedding_tokenizer_expected_byte_size", pending)
        setProperty("embedding_tokenizer_sha256", pending)
        setProperty("embedding_allowed_hosts", "huggingface.co,cas-bridge.xethub.hf.co")
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}
