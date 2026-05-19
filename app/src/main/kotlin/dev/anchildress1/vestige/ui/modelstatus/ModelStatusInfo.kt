package dev.anchildress1.vestige.ui.modelstatus

import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.components.ModelDownloadProgress

/**
 * What the Model Status screen displays — bundled so the screen stays a small-arity surface.
 * [sizeLabel] is the model's nominal footprint (stack row + Ready detail line); [onDiskLabel]
 * is the *actual* on-disk size, which drops to `"0"` once the artifact is deleted.
 * [downloadProgress] is non-null only while [readiness] is `Downloading`, feeding the shared
 * download card the same byte/ETA/speed snapshot onboarding uses.
 */
data class ModelStatusInfo(
    val readiness: ModelReadiness,
    val sizeLabel: String,
    val onDiskLabel: String,
    val versionName: String,
    val downloadProgress: ModelDownloadProgress? = null,
)
