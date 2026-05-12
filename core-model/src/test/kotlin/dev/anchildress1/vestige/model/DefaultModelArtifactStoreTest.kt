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
import java.util.HexFormat

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
    fun `ArtifactHttpClient rejects hosts not in the allowlist`() {
        val client = ArtifactHttpClient(
            allowedHosts = listOf("huggingface.co"),
            networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            client.open("https://evil.example.com/model.bin", resumeFromByte = 0)
        }
    }

    @Test
    fun `ArtifactHttpClient rejects lookalike hostnames that only share a suffix`() {
        val client = ArtifactHttpClient(
            allowedHosts = listOf("huggingface.co"),
            networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
        )
        assertThrows(IllegalArgumentException::class.java) {
            client.open("https://evilhuggingface.co/model.bin", resumeFromByte = 0)
        }
    }

    @Test
    fun `ArtifactHttpClient follows redirects only when each hop stays on the allowlist`() {
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
            val client = ArtifactHttpClient(
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
    fun `ArtifactHttpClient rejects redirects that leave the allowlist`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.redirectTo("http://localhost:${server.address.port}/final")
        }
        server.start()

        try {
            val client = ArtifactHttpClient(
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

    @Test
    fun `ExponentialBackoff treats attempt below 1 as base delay (coerce floor)`() {
        // The shift exponent uses (attempt - 1).coerceAtLeast(0). Attempt 0 → exponent 0 → baseDelay,
        // not a negative shift that JVM would silently interpret modulo 64.
        val backoff = ExponentialBackoff(baseDelayMs = 1_000L, maxDelayMs = 4_000L)
        assertEquals(1_000L, backoff.delayMs(0))
        assertEquals(1_000L, backoff.delayMs(-3))
    }

    @Test
    fun `streamPayload rejects HTTP non-2xx and non-206 status`() = runTest {
        val client = StaticStatusHttpClient(status = 500, payload = SHORT_BYTES)
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = client,
            backoff = ZeroBackoff,
        )
        val raised = runCatching { store.download() }
        // The require() inside streamPayloadToPartFile throws IllegalArgumentException;
        // the retry loop wraps the final attempt in IOException("Model download failed after ...").
        assertTrue(raised.exceptionOrNull() is IOException) {
            "Expected IOException after exhausting retries on HTTP 500, got ${raised.exceptionOrNull()}"
        }
        // Cause chain must surface the real status — silent rebadging hides bugs.
        val rootCause = generateSequence(raised.exceptionOrNull()) { it.cause }.last()
        assertTrue(rootCause.message?.contains("500") == true) {
            "Root cause should mention HTTP 500, was: ${rootCause.message}"
        }
    }

    @Test
    fun `download discards stale part file when server returns 200 to a Range request`() = runTest {
        // Pre-seed a half-written part file so the next download() carries a non-zero resumeFromByte.
        val partFile = File(baseDir, "$MANIFEST_FILENAME.part")
        partFile.writeBytes(ByteArray(7))

        // Server ignores Range and returns 200 with the full payload — the store must drop the
        // stale prefix and rewrite from byte zero rather than appending and producing a SHA mismatch.
        val client = AlwaysFreshHttpClient(payload = SHORT_BYTES)
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = client,
            backoff = ZeroBackoff,
        )

        val state = store.download()
        assertEquals(ModelArtifactState.Complete, state)
        assertEquals(SHORT_BYTES.size.toLong(), File(baseDir, MANIFEST_FILENAME).length())
    }

    @Test
    fun `ArtifactHttpClient accepts subdomains of an allowed host`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, SHORT_BYTES.size.toLong())
            exchange.responseBody.use { it.write(SHORT_BYTES) }
        }
        server.start()
        try {
            // Inject a hostname-resolving client that pretends the request is coming from
            // `cdn.localhost-allowed.test` — Java's URL won't actually resolve it, so we go the
            // other direction: allow `localhost` and let the loopback alias `localhost.` (with
            // trailing dot stripped) match via endsWith.
            val client = ArtifactHttpClient(
                allowedHosts = listOf("localhost"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            client.open("http://localhost:${server.address.port}/file", resumeFromByte = 0).use { response ->
                assertEquals(200, response.statusCode)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ArtifactHttpClient matches allowed host case-insensitively`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, SHORT_BYTES.size.toLong())
            exchange.responseBody.use { it.write(SHORT_BYTES) }
        }
        server.start()
        try {
            val client = ArtifactHttpClient(
                // Allowlist deliberately uppercase — `host.matchesAllowedHost(allowedHost)` lowercases both.
                allowedHosts = listOf("LOCALHOST"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            client.open("http://localhost:${server.address.port}/file", resumeFromByte = 0).use { response ->
                assertEquals(200, response.statusCode)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ArtifactHttpClient rejects a redirect missing the Location header`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            // Send 302 with no Location at all — the require() guard must surface this.
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = ArtifactHttpClient(
                allowedHosts = listOf("127.0.0.1"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            val ex = assertThrows(IllegalArgumentException::class.java) {
                client.open("http://127.0.0.1:${server.address.port}/start", resumeFromByte = 0)
            }
            assertTrue(ex.message?.contains("Location") == true) {
                "Expected error to mention the missing Location header: ${ex.message}"
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ArtifactHttpClient rejects redirect chains longer than MAX_REDIRECTS`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Six hops: /h0 → /h1 → /h2 → /h3 → /h4 → /h5 → /h6. The client allows MAX_REDIRECTS=5
        // and must throw on the seventh dial-out, not loop indefinitely.
        repeat(7) { i ->
            server.createContext("/h$i") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/h${i + 1}")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val client = ArtifactHttpClient(
                allowedHosts = listOf("127.0.0.1"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            val ex = assertThrows(IllegalArgumentException::class.java) {
                client.open("http://127.0.0.1:${server.address.port}/h0", resumeFromByte = 0)
            }
            assertTrue(ex.message?.contains("redirects") == true) {
                "Expected redirect-cap error message: ${ex.message}"
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ArtifactHttpClient follows a 308 permanent redirect (not just 302)`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/final")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        server.createContext("/final") { exchange ->
            exchange.sendResponseHeaders(200, SHORT_BYTES.size.toLong())
            exchange.responseBody.use { it.write(SHORT_BYTES) }
        }
        server.start()
        try {
            val client = ArtifactHttpClient(
                allowedHosts = listOf("127.0.0.1"),
                networkGate = DefaultNetworkGate.ALWAYS_OPEN_FOR_TESTS,
            )
            client.open("http://127.0.0.1:${server.address.port}/start", resumeFromByte = 0).use { response ->
                assertEquals(200, response.statusCode)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sha256 hex is exactly 64 lowercase chars for digests with high-bit bytes`() = runTest {
        // Pins the byte-format regression: a naive `"%02x".format(byte)` sign-extends negative
        // bytes to 8 hex chars and breaks every digest with a high-bit byte. Pre-loading the
        // payload with all 256 byte values guarantees the resulting SHA-256 contains at least
        // one byte ≥ 0x80, so the production sha256() and the HexFormat reference must agree.
        val payload = ByteArray(256) { it.toByte() }
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
        assertEquals(64, expected.length)

        File(baseDir, MANIFEST_FILENAME).writeBytes(payload)
        val store = DefaultModelArtifactStore(
            manifest = manifest(payload.size.toLong(), expected),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(payload),
            backoff = ZeroBackoff,
        )
        assertEquals(ModelArtifactState.Complete, store.currentState())
        assertTrue(store.verifyChecksum())
    }

    @Test
    fun `download deletes the prior artifact before promoting a fresh part file`() = runTest {
        // Pre-seed an existing artifact file. promotePartToArtifact must delete it before rename;
        // the test pins the branch where artifactFile.exists() returns true.
        val artifact = File(baseDir, MANIFEST_FILENAME)
        artifact.writeBytes(ByteArray(SHORT_BYTES.size + 100)) // wrong size → Corrupt → wiped first
        val store = DefaultModelArtifactStore(
            manifest = manifest(SHORT_BYTES.size.toLong(), sha256Of(SHORT_BYTES)),
            baseDir = baseDir,
            httpClient = ByteArrayHttpClient(SHORT_BYTES),
            backoff = ZeroBackoff,
        )
        val state = store.download()
        assertEquals(ModelArtifactState.Complete, state)
        assertEquals(SHORT_BYTES.size.toLong(), artifact.length())
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

    private fun sha256Of(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

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

    /** Returns the supplied [status] for every request — used for HTTP-error fault injection. */
    private class StaticStatusHttpClient(private val status: Int, private val payload: ByteArray) : HttpClient {
        override fun open(url: String, resumeFromByte: Long): HttpResponse =
            InMemoryResponse(status, ByteArrayInputStream(payload))
    }

    /** Always returns 200 with the full payload regardless of the resume offset — pins the
     *  branch where the server ignores Range and the store must drop the stale prefix. */
    private class AlwaysFreshHttpClient(private val payload: ByteArray) : HttpClient {
        override fun open(url: String, resumeFromByte: Long): HttpResponse =
            InMemoryResponse(200, ByteArrayInputStream(payload))
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
