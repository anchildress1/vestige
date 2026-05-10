package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction

/**
 * Reduces three [LensExtraction]s to one [ResolvedExtraction] per the convergence rules:
 * ≥2 lenses agree → CANONICAL; only Inferential populates → CANDIDATE; lenses disagree →
 * AMBIGUOUS (null value, noted); Skeptical flags conflict even on agreement →
 * CANONICAL_WITH_CONFLICT.
 */
interface ConvergenceResolver {
    fun resolve(extractions: List<LensExtraction>): ResolvedExtraction
}

/** Throws on every call. Wiring this into a real call path is a bug. */
class Phase2NotImplementedConvergenceResolver : ConvergenceResolver {
    override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction =
        throw NotImplementedError("ConvergenceResolver.resolve is unimplemented (Phase 2).")
}
