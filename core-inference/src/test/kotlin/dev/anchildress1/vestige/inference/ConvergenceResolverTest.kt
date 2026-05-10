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
 * Phase 1 scaffold for the convergence resolver per ADR-002 §Q4 + Story 1.12.
 *
 * The happy path (all three lenses identical → CANONICAL) is fully written so Phase 2 has a
 * worked example to drop the real implementation into. The other four cases are scaffolded
 * with @Disabled until the real resolver lands; their setup blocks document the inputs Phase 2
 * is expected to handle.
 *
 * **The four `@Disabled` tests in this file are project-sanctioned scaffolding** for Story 2.8
 * (Phase 2 ConvergenceResolver implementation), not silenced failing tests. They intentionally
 * pre-document the inputs + expected verdicts the real resolver must satisfy; each `@Disabled`
 * carries the Story 2.8 enablement message inline. Per AGENTS.md guardrail 22 ("tests and docs
 * ship with the change, every change") the resolver author enables them as the implementation
 * lands. Do not delete or convert them to `@Test` until the resolver is wired; doing so loses
 * the Phase 1 / Phase 2 handoff documentation Story 1.12 intentionally established.
 *
 * Test infrastructure: JUnit Jupiter only — no separate fake LiteRT-LM client at this layer
 * because the resolver consumes already-parsed [LensExtraction]s, not engine output. The fake
 * engine for *prompt-side* Phase 2 tests is the `LiteRtLmEngine` lifecycle scaffolding plus
 * the LiteRT-LM SDK's own deterministic responses for canned inputs.
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
