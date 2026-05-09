package dev.anchildress1.vestige.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest

class DefaultModelArtifactStoreTest {

    private lateinit var baseDir: File

    @BeforeEach
    fun setUp(@TempDir tempDir: File) {
        baseDir = tempDir
    }

    @Test
    fun `currentState reports Absent when no file exists`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        assertEquals(ModelArtifactState.Absent, store.currentState())
    }

    @Test
    fun `currentState reports Partial when file is below expected size`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(expectedSize = 100, sha256 = "any"),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        File(baseDir, MANIFEST_FILENAME).writeBytes(ByteArray(50))
        val state = store.currentState()
        assertTrue(state is ModelArtifactState.Partial, "expected Partial, got $state")
    }

    @Test
    fun `currentState reports Corrupt when file size exceeds expected`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(expectedSize = 5, sha256 = "any"),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        File(baseDir, MANIFEST_FILENAME).writeBytes(ByteArray(99))
        assertTrue(store.currentState() is ModelArtifactState.Corrupt)
    }

    @Test
    fun `currentState reports Corrupt when sha256 does not match`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256 = WRONG_HASH),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        File(baseDir, MANIFEST_FILENAME).writeBytes(SHORT_BYTES)
        assertTrue(store.currentState() is ModelArtifactState.Corrupt)
    }

    @Test
    fun `download writes the artifact and verifies the checksum`() = runTest {
        val client = ByteArrayHttpClient(SHORT_BYTES)
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = client,
            backoff = ZeroBackoff,
        )
        val state = store.download()
        assertEquals(ModelArtifactState.Complete, state)
        assertTrue(store.verifyChecksum())
        assertEquals(1, client.openCalls)
    }

    @Test
    fun `download retries on transient errors and succeeds on the third attempt`() = runTest {
        val client = FailingThenSucceedingClient(failures = 2, payload = SHORT_BYTES)
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = client,
            backoff = ZeroBackoff,
        )
        val state = store.download()
        assertEquals(ModelArtifactState.Complete, state)
        assertEquals(3, client.openCalls)
    }

    @Test
    fun `download surfaces failure after MAX_DOWNLOAD_ATTEMPTS`() = runTest {
        val client = FailingThenSucceedingClient(
            failures = ModelArtifactStore.MAX_DOWNLOAD_ATTEMPTS,
            payload = SHORT_BYTES,
        )
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = client,
            backoff = ZeroBackoff,
        )
        val raised = runCatching { store.download() }
        assertTrue(raised.exceptionOrNull() is IOException) {
            "Expected IOException, got ${raised.exceptionOrNull()}"
        }
        assertEquals(ModelArtifactStore.MAX_DOWNLOAD_ATTEMPTS, client.openCalls)
    }

    @Test
    fun `download deletes a Corrupt file before re-downloading`() = runTest {
        val artifact = File(baseDir, MANIFEST_FILENAME)
        // Right size, wrong content → SHA mismatch → Corrupt (not Partial).
        artifact.writeBytes(ByteArray(SHORT_BYTES.size))
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        assertTrue(store.currentState() is ModelArtifactState.Corrupt)

        val state = store.download()
        assertEquals(ModelArtifactState.Complete, state)
        assertEquals(SHORT_BYTES.size.toLong(), artifact.length())
    }

    @Test
    fun `download surfaces Corrupt when bytes arrive but the SHA does not match`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256 = WRONG_HASH),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        val state = store.download()
        assertTrue(state is ModelArtifactState.Corrupt, "expected Corrupt, got $state")
    }

    @Test
    fun `requireComplete throws when state is anything but Complete`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(100, "any"),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        assertThrows(IllegalStateException::class.java) { runTest { store.requireComplete() } }
    }

    @Test
    fun `requireComplete returns the file when state is Complete`() = runTest {
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        store.download()
        val complete = store.requireComplete()
        assertNotNull(complete)
        assertEquals(MANIFEST_FILENAME, complete.name)
    }

    @Test
    fun `DefaultHttpClient rejects hosts not in the allowlist`() {
        val client = DefaultHttpClient(
            allowedHosts = listOf("huggingface.co"),
            networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            client.open("https://evil.example.com/model.bin", resumeFromByte = 0)
        }
    }

    @Test
    fun `DefaultHttpClient rejects lookalike hostnames that only share a suffix`() {
        val client = DefaultHttpClient(
            allowedHosts = listOf("huggingface.co"),
            networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            client.open("https://evilhuggingface.co/model.bin", resumeFromByte = 0)
        }
    }

    @Test
    fun `DefaultHttpClient follows redirects only when each hop stays on the allowlist`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.redirectTo("http://127.0.0.1:${server.address.port}/final")
        }
        server.createContext("/final") { exchange ->
            exchange.sendResponseHeaders(200, SHORT_BYTES.size.toLong())
            exchange.responseBody.use { it.write(SHORT_BYTES) }
        }
        server.start()

        try {
            val client = DefaultHttpClient(
                allowedHosts = listOf("127.0.0.1"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            client.open("http://127.0.0.1:${server.address.port}/start", resumeFromByte = 0).use { response ->
                assertEquals(200, response.statusCode)
                assertEquals(SHORT_BYTES.toList(), response.inputStream.readBytes().toList())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `DefaultHttpClient rejects redirects that leave the allowlist`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.redirectTo("http://localhost:${server.address.port}/final")
        }
        server.start()

        try {
            val client = DefaultHttpClient(
                allowedHosts = listOf("127.0.0.1"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            assertThrows(IllegalArgumentException::class.java) {
                client.open("http://127.0.0.1:${server.address.port}/start", resumeFromByte = 0)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ExponentialBackoff caps delay at the configured ceiling`() {
        val backoff = ExponentialBackoff(baseDelayMs = 1_000L, maxDelayMs = 4_000L)
        assertEquals(1_000L, backoff.delayMs(1))
        assertEquals(2_000L, backoff.delayMs(2))
        assertEquals(4_000L, backoff.delayMs(3))
        assertEquals(4_000L, backoff.delayMs(4)) // capped
        // Defensive: very large attempts must not overflow into a negative delay.
        assertFalse(backoff.delayMs(64) < 0)
    }

    private fun manifest(expectedSize: Long, sha256: String) = ModelManifest(
        schemaVersion = ModelManifest.SUPPORTED_SCHEMA_VERSION,
        artifactRepo = "test/repo",
        filename = MANIFEST_FILENAME,
        downloadUrl = "https://huggingface.co/test/repo/resolve/main/$MANIFEST_FILENAME",
        expectedByteSize = expectedSize,
        sha256 = sha256,
        allowedHosts = listOf("huggingface.co"),
    )

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private object ZeroBackoff : BackoffPolicy {
        override fun delayMs(attempt: Int): Long = 0L
    }

    private class ByteArrayHttpClient(private val payload: ByteArray) : HttpClient {
        var openCalls = 0
        override fun open(url: String, resumeFromByte: Long): HttpResponse {
            openCalls++
            val slice = if (resumeFromByte == 0L) payload else payload.copyOfRange(resumeFromByte.toInt(), payload.size)
            val status = if (resumeFromByte == 0L) 200 else 206
            return InMemoryResponse(status, ByteArrayInputStream(slice))
        }
    }

    private class FailingThenSucceedingClient(private val failures: Int, private val payload: ByteArray) : HttpClient {
        var openCalls = 0
        override fun open(url: String, resumeFromByte: Long): HttpResponse {
            openCalls++
            if (openCalls <= failures) throw IOException("simulated transient error #$openCalls")
            return InMemoryResponse(200, ByteArrayInputStream(payload))
        }
    }

    private class InMemoryResponse(override val statusCode: Int, override val inputStream: java.io.InputStream) :
        HttpResponse {
        override fun close() = inputStream.close()
    }

    private fun HttpExchange.redirectTo(location: String) {
        responseHeaders.add("Location", location)
        sendResponseHeaders(302, -1)
        close()
    }

    private companion object {
        const val MANIFEST_FILENAME = "test-model.bin"
        const val WRONG_HASH = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
        val SHORT_BYTES: ByteArray = "fake-model-payload-bytes".toByteArray()
    }
}
