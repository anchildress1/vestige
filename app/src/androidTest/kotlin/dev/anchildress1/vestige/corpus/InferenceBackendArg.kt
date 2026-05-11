package dev.anchildress1.vestige.corpus

import android.os.Bundle
import dev.anchildress1.vestige.inference.BackendChoice

/**
 * Resolves the `-PinferenceBackend=cpu|gpu` instrumentation arg into a [BackendChoice]. Default
 * is GPU because the on-device suites target the S24 Ultra Adreno 750 — CPU is for fallback /
 * comparison runs, not the everyday path. Unknown values throw so a typo doesn't silently fall
 * back to a different backend than the operator thought.
 */
object InferenceBackendArg {
    fun resolve(args: Bundle): BackendChoice {
        val raw = args.getString("inferenceBackend")?.trim()?.lowercase() ?: return BackendChoice.Gpu
        return when (raw) {
            "cpu" -> BackendChoice.Cpu
            "gpu" -> BackendChoice.Gpu
            else -> error("Unknown -PinferenceBackend value '$raw' (expected cpu or gpu)")
        }
    }
}
