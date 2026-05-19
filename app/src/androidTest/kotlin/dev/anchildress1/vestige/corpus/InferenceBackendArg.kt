package dev.anchildress1.vestige.corpus

import android.os.Bundle
import dev.anchildress1.vestige.inference.BackendChoice

/** Resolves `-PinferenceBackend=gpu` to [BackendChoice]. Missing value defaults to GPU. */
object InferenceBackendArg {
    fun resolve(args: Bundle): BackendChoice {
        val raw = args.getString("inferenceBackend")?.trim()?.lowercase() ?: return BackendChoice.Gpu
        return when (raw) {
            "gpu" -> BackendChoice.Gpu
            else -> error("Unknown -PinferenceBackend value '$raw' (expected gpu)")
        }
    }
}
