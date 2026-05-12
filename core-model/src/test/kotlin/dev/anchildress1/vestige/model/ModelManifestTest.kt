package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class ModelManifestTest {

    @Test
    fun `loadDefault parses the checked-in manifest`() {
        val manifest = ModelManifest.loadDefault()
        assertEquals(ModelManifest.SUPPORTED_SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("litert-community/gemma-4-E4B-it-litert-lm", manifest.artifactRepo)
        assertEquals("gemma-4-E4B-it.litertlm", manifest.filename)
        assertEquals(3_659_530_240L, manifest.expectedByteSize)
        assertEquals(
            listOf("huggingface.co", "cas-bridge.xethub.hf.co"),
            manifest.allowedHosts,
        )
    }

    @Test
    fun `sha256IsResolved is true after STT-A download probe resolved the hash`() {
        val manifest = ModelManifest.loadDefault()
        // STT-A's download probe completed and pinned the canonical SHA-256 into manifest.properties.
        assertTrue(manifest.sha256IsResolved)
        // 64 hex chars — SHA-256 output is 32 bytes = 64 lowercase hex digits.
        assertEquals(64, manifest.sha256.length)
        assertTrue(manifest.sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `fromProperties accepts the supported schema version`() {
        val manifest = ModelManifest.loadDefault()
        assertEquals(2, manifest.schemaVersion)
    }

    @Test
    fun `fromProperties rejects an unsupported schema version`() {
        val props = Properties().apply {
            setProperty("schema_version", "999")
            setProperty("artifact_repo", "x")
            setProperty("filename", "x.bin")
            setProperty("download_url", "https://example.com/x.bin")
            setProperty("expected_byte_size", "1")
            setProperty("sha256", "abc")
            setProperty("allowed_hosts", "example.com")
        }
        // require(...) for input validation throws IllegalArgumentException.
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.fromProperties(props) }
    }

    @Test
    fun `fromProperties rejects missing required keys`() {
        val props = Properties().apply { setProperty("schema_version", "2") }
        // requireString uses error(...) which throws IllegalStateException for missing keys.
        assertThrows(IllegalStateException::class.java) { ModelManifest.fromProperties(props) }
    }

    @Test
    fun `fromProperties rejects non-numeric size or schema`() {
        val props = Properties().apply {
            setProperty("schema_version", "2")
            setProperty("artifact_repo", "x")
            setProperty("filename", "x.bin")
            setProperty("download_url", "https://example.com/x.bin")
            setProperty("expected_byte_size", "not-a-number")
            setProperty("sha256", "abc")
            setProperty("allowed_hosts", "example.com")
        }
        assertThrows(IllegalStateException::class.java) { ModelManifest.fromProperties(props) }
    }

    @Test
    fun `fromProperties rejects whitespace-only required values`() {
        // requireString uses isNotBlank — `   ` reads as present-but-empty and must surface as
        // a missing-key error, not a silent default.
        val props = Properties().apply {
            setProperty("schema_version", "2")
            setProperty("artifact_repo", "   ")
            setProperty("filename", "x.bin")
            setProperty("download_url", "https://example.com/x.bin")
            setProperty("expected_byte_size", "1")
            setProperty("sha256", "abc")
            setProperty("allowed_hosts", "example.com")
        }
        val ex = assertThrows(IllegalStateException::class.java) { ModelManifest.fromProperties(props) }
        assertTrue(ex.message?.contains("artifact_repo") == true) {
            "Error must identify the offending key (artifact_repo): ${ex.message}"
        }
    }

    @Test
    fun `fromProperties rejects non-numeric schema version`() {
        val props = Properties().apply {
            setProperty("schema_version", "one")
            setProperty("artifact_repo", "x")
            setProperty("filename", "x.bin")
            setProperty("download_url", "https://example.com/x.bin")
            setProperty("expected_byte_size", "1")
            setProperty("sha256", "abc")
            setProperty("allowed_hosts", "example.com")
        }
        assertThrows(IllegalStateException::class.java) { ModelManifest.fromProperties(props) }
    }

    @Test
    fun `loadFromResource throws when the resource path does not exist`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            ModelManifest.loadFromResource("/model/does-not-exist.properties")
        }
        assertTrue(ex.message?.contains("does-not-exist") == true) {
            "Error must echo the missing path: ${ex.message}"
        }
    }

    @Test
    fun `allowed_hosts splits and trims comma-separated entries`() {
        val props = Properties().apply {
            setProperty("schema_version", "2")
            setProperty("artifact_repo", "x")
            setProperty("filename", "x.bin")
            setProperty("download_url", "https://example.com/x.bin")
            setProperty("expected_byte_size", "1024")
            setProperty("sha256", "abc")
            setProperty("allowed_hosts", "  one.com  ,two.com,  ,three.com")
        }
        val manifest = ModelManifest.fromProperties(props)
        assertEquals(listOf("one.com", "two.com", "three.com"), manifest.allowedHosts)
    }
}
