package dev.anchildress1.vestige.model

import java.util.Properties

/**
 * Pinned descriptor for the model artifact, loaded from `resources/model/manifest.properties` at
 * startup. SHA-256, size, and allowed download hosts ship as code — never user-editable config.
 */
data class ModelManifest(
    val schemaVersion: Int,
    val artifactRepo: String,
    val filename: String,
    val downloadUrl: String,
    val expectedByteSize: Long,
    val sha256: String,
    val allowedHosts: List<String>,
) {
    val sha256IsResolved: Boolean get() = sha256 != PENDING_HASH_TOKEN

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val PENDING_HASH_TOKEN = "PENDING_STT_A_DOWNLOAD_PROBE"

        private const val DEFAULT_RESOURCE = "/model/manifest.properties"

        /** Load the bundled manifest from the classpath. */
        fun loadDefault(): ModelManifest = loadFromResource(DEFAULT_RESOURCE)

        internal fun loadFromResource(path: String): ModelManifest {
            val stream = ModelManifest::class.java.getResourceAsStream(path)
                ?: error("Model manifest resource missing: $path")
            val props = Properties()
            stream.bufferedReader(Charsets.UTF_8).use { props.load(it) }
            return fromProperties(props)
        }

        internal fun fromProperties(props: Properties): ModelManifest {
            val schema = props.requireInt("schema_version")
            require(schema == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported model manifest schema_version: $schema (this build understands $SUPPORTED_SCHEMA_VERSION)"
            }
            return ModelManifest(
                schemaVersion = schema,
                artifactRepo = props.requireString("artifact_repo"),
                filename = props.requireString("filename"),
                downloadUrl = props.requireString("download_url"),
                expectedByteSize = props.requireLong("expected_byte_size"),
                sha256 = props.requireString("sha256"),
                allowedHosts = props.requireString("allowed_hosts")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }

        private fun Properties.requireString(key: String): String = getProperty(key)?.takeIf { it.isNotBlank() }
            ?: error("Model manifest missing required key: $key")

        private fun Properties.requireInt(key: String): Int = requireString(key).toIntOrNull()
            ?: error("Model manifest key '$key' is not an integer: '${getProperty(key)}'")

        private fun Properties.requireLong(key: String): Long = requireString(key).toLongOrNull()
            ?: error("Model manifest key '$key' is not a long: '${getProperty(key)}'")
    }
}
