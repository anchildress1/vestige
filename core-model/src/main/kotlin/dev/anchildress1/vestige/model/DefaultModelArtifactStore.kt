package dev.anchildress1.vestige.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.HexFormat

/**
 * File-on-disk + HTTP implementation. Uses `HttpURLConnection` to keep `:core-model`
 * dependency-free; the [NetworkGate]-wrapped client is the sole outbound primitive.
 */
class DefaultModelArtifactStore(
    override val manifest: ModelManifest,
    private val baseDir: File,
    private val httpClient: HttpClient,
    private val backoff: BackoffPolicy = ExponentialBackoff(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelArtifactStore {

    override val artifactFile: File = File(baseDir, manifest.filename)

    private val partFile: File = File(baseDir, "${manifest.filename}.part")

    override suspend fun probe(): ModelArtifactState = withContext(ioDispatcher) {
        if (artifactFile.exists()) {
            val length = artifactFile.length()
            return@withContext when {
                length < manifest.expectedByteSize ->
                    ModelArtifactState.Partial(length, manifest.expectedByteSize)

                length > manifest.expectedByteSize ->
                    ModelArtifactState.Corrupt(manifest.sha256, "size_mismatch:$length")

                // Size matches — trust it for UI gating. Integrity is the load path's job.
                else -> ModelArtifactState.Complete
            }
        }
        // No final file, but a resumable `.part` means a download is mid-flight: report its
        // byte count so a cold-process re-entry shows real progress instead of flashing 0%.
        if (partFile.exists()) {
            return@withContext ModelArtifactState.Partial(partFile.length(), manifest.expectedByteSize)
        }
        ModelArtifactState.Absent
    }

    override suspend fun currentState(): ModelArtifactState = withContext(ioDispatcher) {
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

    override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState = withContext(ioDispatcher) {
        baseDir.mkdirs()
        val state = currentState()

        // Wipe the file when its previous state is Corrupt — never silently rewrite around
        // a known-bad payload.
        if (state is ModelArtifactState.Corrupt) {
            check(artifactFile.delete()) { "Could not delete corrupt artifact at ${artifactFile.absolutePath}" }
        }

        var lastFailure: Throwable? = null
        for (attempt in 1..ModelArtifactStore.MAX_DOWNLOAD_ATTEMPTS) {
            try {
                return@withContext downloadOnce(onProgress)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
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

    override suspend fun verifyChecksum(): Boolean = withContext(ioDispatcher) {
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

    private suspend fun downloadOnce(onProgress: (Long, Long) -> Unit): ModelArtifactState {
        streamPayloadToPartFile(partFile, onProgress)
        promotePartToArtifact(partFile)

        val actual = sha256(artifactFile)
        return if (actual.equals(manifest.sha256, ignoreCase = true)) {
            ModelArtifactState.Complete
        } else {
            ModelArtifactState.Corrupt(expectedSha256 = manifest.sha256, actualSha256 = actual)
        }
    }

    private suspend fun streamPayloadToPartFile(partFile: File, onProgress: (Long, Long) -> Unit) {
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

    private suspend fun writeBodyToFile(
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
                currentCoroutineContext().ensureActive()
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
        // HexFormat lowercase keeps each byte at exactly two hex chars. The naive
        // `"%02x".format(byte)` path sign-extends negative bytes to 8 hex chars and produces
        // an invalid SHA-256 string — every digest with a high-bit byte breaks otherwise.
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
    }
}

/** Implementations honor the manifest `allowed_hosts` allowlist before opening a stream. */
interface HttpClient {
    fun open(url: String, resumeFromByte: Long): HttpResponse
}

interface HttpResponse : AutoCloseable {
    val statusCode: Int
    val inputStream: java.io.InputStream
}

/**
 * `HttpURLConnection`-backed client. Fails fast on hosts not in [allowedHosts]; consults
 * [networkGate] before each connect so a sealed gate prevents dial-out regardless of allowlist.
 */
class ArtifactHttpClient(private val allowedHosts: List<String>, private val networkGate: NetworkGate) : HttpClient {
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

fun interface BackoffPolicy {
    fun delayMs(attempt: Int): Long
}

/** Capped exponential backoff: 1s, 2s, 4s … up to [maxDelayMs]. */
class ExponentialBackoff(private val baseDelayMs: Long = 1_000L, private val maxDelayMs: Long = 30_000L) :
    BackoffPolicy {
    override fun delayMs(attempt: Int): Long {
        val raw = baseDelayMs shl (attempt - 1).coerceAtLeast(0)
        return raw.coerceAtMost(maxDelayMs)
    }
}
