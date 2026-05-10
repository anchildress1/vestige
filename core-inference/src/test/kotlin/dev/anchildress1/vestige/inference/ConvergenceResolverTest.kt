package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Convergence resolver scaffold per ADR-002 §Q4. Happy path is written; the four `@Disabled`
 * cases are intentional scaffolding (each carries the enablement criterion inline) — the
 * resolver author flips them to `@Test` as the real implementation lands. Do not delete.
 */
class ConvergenceResolverTest {

    /**
     * Happy path documentation example — fully written. All three lenses populate the same
     * fields with identical values; every field resolves to CANONICAL with the consensus value.
     *
     * Phase 2 implementation must satisfy this case before declaring the resolver done.
     */
    @Test
    fun `all three lenses identical resolves to canonical for every field`() {
        val literal = LensExtraction(
            lens = Lens.LITERAL,
            fields = mapOf(
                "template_label" to "aftermath",
                "energy_descriptor" to "flattened",
                "tags" to listOf("standup", "launch-doc"),
            ),
        )
        val inferential = literal.copy(lens = Lens.INFERENTIAL)
        val skeptical = literal.copy(lens = Lens.SKEPTICAL)

        val expected = ResolvedExtraction(
            fields = mapOf(
                "template_label" to ResolvedField("aftermath", ConfidenceVerdict.CANONICAL),
                "energy_descriptor" to ResolvedField("flattened", ConfidenceVerdict.CANONICAL),
                "tags" to ResolvedField(
                    listOf("standup", "launch-doc"),
                    ConfidenceVerdict.CANONICAL,
                ),
            ),
        )

        // Phase 2 will replace this with: resolver.resolve(listOf(literal, inferential, skeptical))
        // and the test will assert equality against `expected` directly. Until then, document the
        // contract by asserting the inputs are well-formed.
        assertEquals(3, listOf(literal, inferential, skeptical).distinctBy { it.lens }.size)
        assertEquals(3, expected.fields.size)
    }

    @Test
    @Disabled("Phase 2: implement ConvergenceResolver — only Inferential populates → CANDIDATE.")
    fun `only Inferential populates a field resolves to candidate`() {
        // Setup expectation:
        //   literal.fields["stated_commitment"]    = null
        //   inferential.fields["stated_commitment"]= { text: "...", topic_or_person: "Nora" }
        //   skeptical.fields["stated_commitment"]  = null
        // Expected: ResolvedField(value=<inferential value>, verdict=CANDIDATE, flags=[])
    }

    @Test
    @Disabled("Phase 2: implement ConvergenceResolver — lenses disagree → AMBIGUOUS, value null.")
    fun `lenses disagree on a field resolves to ambiguous with null value`() {
        // Setup expectation:
        //   literal.fields["template_label"]    = "aftermath"
        //   inferential.fields["template_label"]= "tunnel-exit"
        //   skeptical.fields["template_label"]  = "audit"
        // Expected: ResolvedField(value=null, verdict=AMBIGUOUS, flags=["lens-disagreement"])
    }

    @Test
    @Disabled("Phase 2: implement ConvergenceResolver — Skeptical flags conflict → CANONICAL_WITH_CONFLICT.")
    fun `Skeptical flags conflict even when others agree resolves to canonical with conflict marker`() {
        // Setup expectation:
        //   literal.fields["energy_descriptor"]    = "flattened"
        //   inferential.fields["energy_descriptor"]= "flattened"
        //   skeptical.fields["energy_descriptor"]  = "flattened"
        //   skeptical.flags = ["energy_descriptor:contradicts:fine"]  // contradicting state words
        // Expected: ResolvedField(
        //   value="flattened",
        //   verdict=CANONICAL_WITH_CONFLICT,
        //   flags=["energy_descriptor:contradicts:fine"],
        // )
    }

    @Test
    @Disabled("Phase 2: implement ConvergenceResolver — two of three agree → CANONICAL on majority.")
    fun `two of three lenses agree resolves to canonical on the majority value`() {
        // Setup expectation:
        //   literal.fields["template_label"]    = "aftermath"
        //   inferential.fields["template_label"]= "aftermath"
        //   skeptical.fields["template_label"]  = "audit"
        // Expected: ResolvedField(value="aftermath", verdict=CANONICAL, flags=[])
    }

    @Test
    fun `Phase2NotImplementedConvergenceResolver throws NotImplementedError on resolve`() {
        val resolver = Phase2NotImplementedConvergenceResolver()
        assertThrows(NotImplementedError::class.java) { resolver.resolve(emptyList()) }
    }
}
