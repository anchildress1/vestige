package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertTrue(manifest.expectedByteSize > 0)
        assertTrue(manifest.allowedHosts.contains("huggingface.co"))
    }

    @Test
    fun `sha256IsResolved is false until the STT-A probe fills it in`() {
        val manifest = ModelManifest.loadDefault()
        // Phase 1 ships the placeholder; STT-A's download probe replaces it.
        assertFalse(manifest.sha256IsResolved)
        assertEquals(ModelManifest.PENDING_HASH_TOKEN, manifest.sha256)
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
        val props = Properties().apply { setProperty("schema_version", "1") }
        // requireString uses error(...) which throws IllegalStateException for missing keys.
        assertThrows(IllegalStateException::class.java) { ModelManifest.fromProperties(props) }
    }

    @Test
    fun `fromProperties rejects non-numeric size or schema`() {
        val props = Properties().apply {
            setProperty("schema_version", "1")
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
    fun `allowed_hosts splits and trims comma-separated entries`() {
        val props = Properties().apply {
            setProperty("schema_version", "1")
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
