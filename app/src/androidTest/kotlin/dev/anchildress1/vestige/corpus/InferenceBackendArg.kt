package dev.anchildress1.vestige.corpus

import android.os.Bundle
import dev.anchildress1.vestige.inference.BackendChoice

/**
 * Resolves the `-PinferenceBackend=cpu|gpu` instrumentation arg into a [BackendChoice]. Default
 * is CPU because the current `.litertlm` artifact ships CPU-only per ADR-001 §Q3 — `Backend.GPU()`
 * fails at the native JNI layer (`llm_litert_compiled_model_executor.cc`) until a GPU-compiled
 * model variant ships. The arg stays available so Phase 4/5 GPU/NPU enablement can opt in without
 * re-touching the harnesses. Unknown values throw so a typo can't silently fall back.
 */
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
