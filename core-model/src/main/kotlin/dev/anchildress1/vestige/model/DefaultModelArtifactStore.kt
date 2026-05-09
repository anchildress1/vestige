package dev.anchildress1.vestige.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Default file-on-disk + HTTP implementation of [ModelArtifactStore]. Uses `HttpURLConnection`
 * to keep `:core-model` dependency-free (no OkHttp / Ktor pull-in); Story 1.10 wraps the actual
 * outbound call in `NetworkGate` so this store is the only outbound primitive in v1.
 *
 * @param httpClient open-and-connect indirection so tests can swap in a fake without standing
 *  up a local HTTP server. The default uses [HttpURLConnection] and respects the manifest's
 *  `allowed_hosts` allowlist before opening the connection.
 */
class DefaultModelArtifactStore(
    override val manifest: ModelManifest,
    private val baseDir: File,
    private val httpClient: HttpClient,
    private val backoff: BackoffPolicy = ExponentialBackoff(),
) : ModelArtifactStore {

    override val artifactFile: File = File(baseDir, manifest.filename)

    override suspend fun currentState(): ModelArtifactState = withContext(Dispatchers.IO) {
        if (!artifactFile.exists()) return@withContext ModelArtifactState.Absent

        val length = artifactFile.length()
        if (length < manifest.expectedByteSize) {
            return@withContext ModelArtifactState.Partial(length, manifest.expectedByteSize)
        }
        if (length > manifest.expectedByteSize) {
            return@withContext ModelArtifactState.Corrupt(
                expectedSha256 = manifest.sha256,
                actualSha256 = "size_mismatch:$length",
            )
        }
        val actual = sha256(artifactFile)
        if (actual.equals(manifest.sha256, ignoreCase = true)) {
            ModelArtifactState.Complete
        } else {
            ModelArtifactState.Corrupt(expectedSha256 = manifest.sha256, actualSha256 = actual)
        }
    }

    override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        val state = currentState()

        // Wipe the file when its previous state is Corrupt — never silently rewrite around
        // a known-bad payload.
        if (state is ModelArtifactState.Corrupt) {
            check(artifactFile.delete()) { "Could not delete corrupt artifact at ${artifactFile.absolutePath}" }
        }

        var attempt = 0
        var lastFailure: Throwable? = null
        while (attempt < ModelArtifactStore.MAX_DOWNLOAD_ATTEMPTS) {
            attempt++
            val outcome = runCatching { downloadOnce(onProgress) }
            outcome.onSuccess { result ->
                return@withContext result
            }
            outcome.onFailure { error ->
                lastFailure = error
                if (attempt >= ModelArtifactStore.MAX_DOWNLOAD_ATTEMPTS) {
                    throw IOException("Model download failed after $attempt attempts", error)
                }
                delay(backoff.delayMs(attempt))
            }
        }
        throw IOException(
            "Unreachable: model download exhausted retries",
            lastFailure,
        )
    }

    override suspend fun verifyChecksum(): Boolean = withContext(Dispatchers.IO) {
        if (!artifactFile.exists()) return@withContext false
        sha256(artifactFile).equals(manifest.sha256, ignoreCase = true)
    }

    override suspend fun requireComplete(): File {
        val state = currentState()
        check(state is ModelArtifactState.Complete) {
            "Model artifact not ready (state=$state). Call download() first."
        }
        return artifactFile
    }

    private fun downloadOnce(onProgress: (Long, Long) -> Unit): ModelArtifactState {
        val partFile = File(baseDir, "${manifest.filename}.part")
        streamPayloadToPartFile(partFile, onProgress)
        promotePartToArtifact(partFile)

        val actual = sha256(artifactFile)
        return if (actual.equals(manifest.sha256, ignoreCase = true)) {
            ModelArtifactState.Complete
        } else {
            ModelArtifactState.Corrupt(expectedSha256 = manifest.sha256, actualSha256 = actual)
        }
    }

    private fun streamPayloadToPartFile(partFile: File, onProgress: (Long, Long) -> Unit) {
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L
        val response = httpClient.open(manifest.downloadUrl, resumeFrom)
        response.use {
            require(it.statusCode in HTTP_OK..HTTP_PARTIAL_CONTENT) {
                "Unexpected HTTP status ${it.statusCode} from ${manifest.downloadUrl}"
            }
            val appendMode = resumeFrom > 0 && it.statusCode == HTTP_PARTIAL_CONTENT
            if (!appendMode && partFile.exists()) {
                check(partFile.delete()) { "Could not delete stale part file at ${partFile.absolutePath}" }
            }
            val startBytes = if (appendMode) resumeFrom else 0L
            writeBodyToFile(it.inputStream, partFile, appendMode, startBytes, onProgress)
        }
    }

    private fun writeBodyToFile(
        body: java.io.InputStream,
        partFile: File,
        appendMode: Boolean,
        startBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val expected = manifest.expectedByteSize
        java.io.FileOutputStream(partFile, appendMode).use { sink ->
            val buffer = ByteArray(BUFFER_BYTES)
            var totalWritten = startBytes
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                totalWritten += read
                onProgress(totalWritten, expected)
            }
        }
    }

    private fun promotePartToArtifact(partFile: File) {
        if (artifactFile.exists()) {
            check(artifactFile.delete()) { "Could not delete prior artifact at ${artifactFile.absolutePath}" }
        }
        require(partFile.renameTo(artifactFile)) {
            "Could not rename ${partFile.absolutePath} -> ${artifactFile.absolutePath}"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
    }
}

/**
 * Minimal HTTP indirection so the artifact store is testable without a network. Implementations
 * are responsible for honoring the manifest's `allowed_hosts` allowlist before opening a stream.
 */
interface HttpClient {
    fun open(url: String, resumeFromByte: Long): HttpResponse
}

interface HttpResponse : AutoCloseable {
    val statusCode: Int
    val inputStream: java.io.InputStream
}

/**
 * Default `HttpURLConnection`-backed client. Fails fast on hosts not in [allowedHosts] and
 * consults the supplied [networkGate] before each connect — so a sealed gate prevents the
 * dial-out even if the allowlist would otherwise permit the host. The gate is required: there
 * is no production scenario where the model-download client should bypass the privacy gate.
 */
class DefaultHttpClient(private val allowedHosts: List<String>, private val networkGate: NetworkGate) : HttpClient {
    override fun open(url: String, resumeFromByte: Long): HttpResponse {
        var currentUrl = URI.create(url).toURL()
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            networkGate.assertOpen()
            validateHost(currentUrl)
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                if (resumeFromByte > 0) {
                    setRequestProperty("Range", "bytes=$resumeFromByte-")
                }
            }
            val statusCode = connection.responseCode
            if (!statusCode.isRedirect()) {
                return UrlHttpResponse(connection)
            }

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) {
                "Redirect from $currentUrl returned HTTP $statusCode without a Location header."
            }
            require(redirectCount < MAX_REDIRECTS) {
                "Too many redirects while fetching $url"
            }
            currentUrl = currentUrl.toURI().resolve(location).toURL()
        }
        error("Unreachable: redirect loop should have returned or thrown.")
    }

    private fun validateHost(url: URL) {
        val host = url.host.orEmpty()
        require(allowedHosts.any { allowedHost -> host.matchesAllowedHost(allowedHost) }) {
            "Refusing to open a connection to '$host' — not in manifest allowed_hosts $allowedHosts"
        }
    }

    private fun String.matchesAllowedHost(allowedHost: String): Boolean {
        val candidate = lowercase()
        val allowed = allowedHost.lowercase()
        return candidate == allowed || candidate.endsWith(".$allowed")
    }

    private fun Int.isRedirect(): Boolean = when (this) {
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        HTTP_TEMP_REDIRECT,
        HTTP_PERM_REDIRECT,
        -> true

        else -> false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val HTTP_TEMP_REDIRECT = 307
        const val HTTP_PERM_REDIRECT = 308
    }
}

private class UrlHttpResponse(private val connection: HttpURLConnection) : HttpResponse {
    override val statusCode: Int get() = connection.responseCode
    override val inputStream: java.io.InputStream get() = connection.inputStream
    override fun close() {
        connection.disconnect()
    }
}

/** Pluggable retry timing — tests inject a deterministic shim. */
fun interface BackoffPolicy {
    fun delayMs(attempt: Int): Long
}

/** Capped exponential backoff: 1s, 2s, 4s … with a [maxDelayMs] ceiling. */
class ExponentialBackoff(private val baseDelayMs: Long = 1_000L, private val maxDelayMs: Long = 30_000L) :
    BackoffPolicy {
    override fun delayMs(attempt: Int): Long {
        val raw = baseDelayMs shl (attempt - 1).coerceAtLeast(0)
        return raw.coerceAtMost(maxDelayMs)
    }
}
