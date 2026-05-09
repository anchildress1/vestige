package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction

/**
 * Reduces three [LensExtraction]s (Literal / Inferential / Skeptical) to one [ResolvedExtraction]
 * per `concept-locked.md` §"Convergence rules":
 *
 * - ≥2 of 3 lenses agree on a field → `CANONICAL`, saved as authoritative.
 * - Only Inferential populates → `CANDIDATE`, lower confidence, not used by pattern engine.
 * - Lenses disagree → `AMBIGUOUS`, value is `null`, saved with a note.
 * - Skeptical flags conflict even when the others agree → `CANONICAL_WITH_CONFLICT`.
 *
 * Phase 1 ships only the contract. Phase 2 implements [Phase2NotImplementedConvergenceResolver]'s
 * replacement against the test scaffold in `:core-inference`'s test source set.
 */
interface ConvergenceResolver {
    fun resolve(extractions: List<LensExtraction>): ResolvedExtraction
}

/**
 * Stub stand-in for the real resolver. Throws on every call — the goal is to make Phase 2's
 * implementation work the test contract on day one. If anything in Phase 1 wires this into a
 * real call path, that's a bug.
 */
class Phase2NotImplementedConvergenceResolver : ConvergenceResolver {
    override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction = throw NotImplementedError(
        "ConvergenceResolver.resolve is Phase 2 work — see ADR-002 §Q4 and Story 1.12.",
    )
}
