package dev.anchildress1.vestige.model

import java.util.Properties

/**
 * Pinned descriptor for the EmbeddingGemma model + paired SentencePiece tokenizer. Loaders for
 * the two artifacts namespace by key prefix (`embedding_artifact_*`, `embedding_tokenizer_*`)
 * inside `resources/model/manifest.properties`. [isResolved] gates `Embedder` instantiation —
 * absent on-device probe values mean the SDK can't be safely loaded yet.
 */
data class EmbeddingArtifactManifest(
    val schemaVersion: Int,
    val artifactRepo: String,
    val model: ArtifactSpec,
    val tokenizer: ArtifactSpec,
    val allowedHosts: List<String>,
) {
    /** A single downloadable file with its integrity contract. */
    data class ArtifactSpec(
        val filename: String,
        val downloadUrl: String,
        val expectedByteSize: Long?,
        val sha256: String,
    ) {
        val sha256IsResolved: Boolean get() = sha256 != PENDING_PROBE_TOKEN
        val sizeIsResolved: Boolean get() = expectedByteSize != null
        val isResolved: Boolean get() = sha256IsResolved && sizeIsResolved
    }

    /** Returns true only when both artifacts have pinned SHA-256s and byte sizes. */
    val isResolved: Boolean get() = model.isResolved && tokenizer.isResolved

    /** Adapts the pinned model row to the single-file artifact contract from Story 1.9. */
    fun modelArtifactManifest(): ModelManifest = model.toModelManifest(
        schemaVersion = schemaVersion,
        artifactRepo = artifactRepo,
        allowedHosts = allowedHosts,
    )

    /** Adapts the pinned tokenizer row to the single-file artifact contract from Story 1.9. */
    fun tokenizerArtifactManifest(): ModelManifest = tokenizer.toModelManifest(
        schemaVersion = schemaVersion,
        artifactRepo = artifactRepo,
        allowedHosts = allowedHosts,
    )

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 2
        const val PENDING_PROBE_TOKEN = "PENDING_PHASE_3_DOWNLOAD_PROBE"

        private const val DEFAULT_RESOURCE = "/model/manifest.properties"

        /** Load the bundled embedding manifest entries from the classpath. */
        fun loadDefault(): EmbeddingArtifactManifest = loadFromResource(DEFAULT_RESOURCE)

        internal fun loadFromResource(path: String): EmbeddingArtifactManifest {
            val stream = EmbeddingArtifactManifest::class.java.getResourceAsStream(path)
                ?: error("Model manifest resource missing: $path")
            val props = Properties()
            stream.bufferedReader(Charsets.UTF_8).use { props.load(it) }
            return fromProperties(props)
        }

        internal fun fromProperties(props: Properties): EmbeddingArtifactManifest {
            val schema = props.requireInt("schema_version")
            require(schema == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported model manifest schema_version: $schema (this build understands $SUPPORTED_SCHEMA_VERSION)"
            }
            return EmbeddingArtifactManifest(
                schemaVersion = schema,
                artifactRepo = props.requireString("embedding_artifact_repo"),
                model = ArtifactSpec(
                    filename = props.requireString("embedding_artifact_filename"),
                    downloadUrl = props.requireString("embedding_artifact_download_url"),
                    expectedByteSize = props.optionalLong("embedding_artifact_expected_byte_size"),
                    sha256 = props.requireString("embedding_artifact_sha256"),
                ),
                tokenizer = ArtifactSpec(
                    filename = props.requireString("embedding_tokenizer_filename"),
                    downloadUrl = props.requireString("embedding_tokenizer_download_url"),
                    expectedByteSize = props.optionalLong("embedding_tokenizer_expected_byte_size"),
                    sha256 = props.requireString("embedding_tokenizer_sha256"),
                ),
                allowedHosts = props.requireString("embedding_allowed_hosts")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }

        private fun Properties.requireString(key: String): String = getProperty(key)?.takeIf { it.isNotBlank() }
            ?: error("Model manifest missing required key: $key")

        private fun Properties.requireInt(key: String): Int = requireString(key).toIntOrNull()
            ?: error("Model manifest key '$key' is not an integer: '${getProperty(key)}'")

        private fun Properties.optionalLong(key: String): Long? {
            val raw = requireString(key)
            if (raw == PENDING_PROBE_TOKEN) return null
            return raw.toLongOrNull()
                ?: error("Model manifest key '$key' is not a long or the pending-probe token: '$raw'")
        }

        private fun ArtifactSpec.toModelManifest(
            schemaVersion: Int,
            artifactRepo: String,
            allowedHosts: List<String>,
        ): ModelManifest {
            check(isResolved) {
                "Embedding artifact '$filename' is unresolved; expected byte size " +
                    "and SHA-256 must be pinned before adapting it to ModelArtifactStore."
            }
            return ModelManifest(
                schemaVersion = schemaVersion,
                artifactRepo = artifactRepo,
                filename = filename,
                downloadUrl = downloadUrl,
                expectedByteSize = checkNotNull(expectedByteSize),
                sha256 = sha256,
                allowedHosts = allowedHosts,
            )
        }
    }
}
