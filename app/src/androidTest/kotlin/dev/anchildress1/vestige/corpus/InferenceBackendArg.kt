package dev.anchildress1.vestige.corpus

import android.os.Bundle
import dev.anchildress1.vestige.inference.BackendChoice

/** Resolves `-PinferenceBackend=cpu|gpu` to [BackendChoice]. Default CPU. Unknown value throws. */
object InferenceBackendArg {
    fun resolve(args: Bundle): BackendChoice {
        val raw = args.getString("inferenceBackend")?.trim()?.lowercase() ?: return BackendChoice.Cpu
        return when (raw) {
            "cpu" -> BackendChoice.Cpu
            "gpu" -> BackendChoice.Gpu
            else -> error("Unknown -PinferenceBackend value '$raw' (expected cpu or gpu)")
        }
    }
}
