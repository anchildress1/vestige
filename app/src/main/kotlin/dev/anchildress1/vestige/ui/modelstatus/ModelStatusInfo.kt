package dev.anchildress1.vestige.ui.modelstatus

import dev.anchildress1.vestige.ui.capture.ModelReadiness

/** What the Model Status screen displays — bundled so the screen stays a small-arity surface. */
data class ModelStatusInfo(val readiness: ModelReadiness, val sizeLabel: String, val versionName: String)
