package dev.anchildress1.vestige.corpus

import android.os.Bundle
import dev.anchildress1.vestige.inference.BackendChoice

/** Resolves `-PinferenceBackend=gpu` to [BackendChoice]. Missing or `gpu` returns GPU; anything else errors. */
object InferenceBackendArg {
    fun resolve(args: Bundle): BackendChoice {
        val raw = args.getString("inferenceBackend")?.trim()?.lowercase() ?: return BackendChoice.Gpu
        require(raw == "gpu") { "Unknown -PinferenceBackend value '$raw' (expected gpu)" }
        return BackendChoice.Gpu
    }
}
