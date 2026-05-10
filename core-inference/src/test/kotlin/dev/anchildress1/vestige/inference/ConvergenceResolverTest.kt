package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Convergence resolver contract per ADR-002 §"Convergence Resolver Contract". The first four cases
 * exercise the named resolution rules (canonical / candidate / ambiguous / canonical-with-conflict)
 * and were carried forward from the Phase 1 scaffold (Story 1.12). The remaining tests cover the
 * edge cases ADR-002 §"Edge case — lens errors mid-call" calls out explicitly.
 */
class ConvergenceResolverTest {

    private val resolver = DefaultConvergenceResolver()

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

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField("aftermath", ConfidenceVerdict.CANONICAL),
            resolved.fields["template_label"],
        )
        assertEquals(
            ResolvedField("flattened", ConfidenceVerdict.CANONICAL),
            resolved.fields["energy_descriptor"],
        )
        assertEquals(
            ResolvedField(listOf("standup", "launch-doc"), ConfidenceVerdict.CANONICAL),
            resolved.fields["tags"],
        )
    }

    @Test
    fun `only Inferential populates a field resolves to candidate`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("stated_commitment" to null))
        val inferential = LensExtraction(
            Lens.INFERENTIAL,
            fields = mapOf("stated_commitment" to mapOf("text" to "review the doc", "topic_or_person" to "Nora")),
        )
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("stated_commitment" to null))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(
                value = mapOf("text" to "review the doc", "topic_or_person" to "Nora"),
                verdict = ConfidenceVerdict.CANDIDATE,
            ),
            resolved.fields["stated_commitment"],
        )
    }

    @Test
    fun `lenses disagree on a field resolves to ambiguous with null value`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("template_label" to "aftermath"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("template_label" to "tunnel-exit"))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("template_label" to "audit"))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
                flags = listOf("lens-disagreement"),
            ),
            resolved.fields["template_label"],
        )
    }

    @Test
    fun `Skeptical flags conflict even when others agree resolves to canonical with conflict marker`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("energy_descriptor" to "flattened"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("energy_descriptor" to "flattened"))
        val skeptical = LensExtraction(
            Lens.SKEPTICAL,
            fields = mapOf("energy_descriptor" to "flattened"),
            flags = listOf("energy_descriptor:contradicts:fine"),
        )

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(
                value = "flattened",
                verdict = ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
                flags = listOf("energy_descriptor:contradicts:fine"),
            ),
            resolved.fields["energy_descriptor"],
        )
    }

    @Test
    fun `two of three lenses agree resolves to canonical on the majority value`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("template_label" to "aftermath"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("template_label" to "aftermath"))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("template_label" to "audit"))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(value = "aftermath", verdict = ConfidenceVerdict.CANONICAL),
            resolved.fields["template_label"],
        )
    }

    @Test
    fun `all three lenses null on a nullable field resolves to canonical with null value`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("recurrence_link" to null))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("recurrence_link" to null))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("recurrence_link" to null))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(value = null, verdict = ConfidenceVerdict.CANONICAL),
            resolved.fields["recurrence_link"],
        )
    }

    @Test
    fun `Skeptical-only flag without populated value still surfaces conflict on consensus`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("energy_descriptor" to "flattened"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("energy_descriptor" to "flattened"))
        val skeptical = LensExtraction(
            Lens.SKEPTICAL,
            fields = mapOf("energy_descriptor" to null),
            flags = listOf("energy_descriptor:contradicts:fine"),
        )

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(
                value = "flattened",
                verdict = ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
                flags = listOf("energy_descriptor:contradicts:fine"),
            ),
            resolved.fields["energy_descriptor"],
        )
    }

    @Test
    fun `single surviving lens treats every populated field as candidate`() {
        // Two lenses parsed-failed at the worker; only Literal reaches the resolver.
        val literal = LensExtraction(
            Lens.LITERAL,
            fields = mapOf("energy_descriptor" to "flattened", "template_label" to "aftermath"),
        )

        val resolved = resolver.resolve(listOf(literal))

        assertEquals(
            ResolvedField("flattened", ConfidenceVerdict.CANDIDATE),
            resolved.fields["energy_descriptor"],
        )
        assertEquals(
            ResolvedField("aftermath", ConfidenceVerdict.CANDIDATE),
            resolved.fields["template_label"],
        )
    }

    @Test
    fun `two surviving lenses agree resolves to canonical`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("template_label" to "aftermath"))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("template_label" to "aftermath"))

        val resolved = resolver.resolve(listOf(literal, skeptical))

        assertEquals(
            ResolvedField("aftermath", ConfidenceVerdict.CANONICAL),
            resolved.fields["template_label"],
        )
    }

    @Test
    fun `two surviving lenses disagree resolves to ambiguous`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("template_label" to "aftermath"))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("template_label" to "audit"))

        val resolved = resolver.resolve(listOf(literal, skeptical))

        assertEquals(
            ResolvedField(
                value = null,
                verdict = ConfidenceVerdict.AMBIGUOUS,
                flags = listOf("lens-disagreement"),
            ),
            resolved.fields["template_label"],
        )
    }

    @Test
    fun `energy_descriptor agreement is case insensitive after trim`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("energy_descriptor" to "Flattened"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("energy_descriptor" to "flattened "))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("energy_descriptor" to "fine"))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        // Majority wins after canonicalization; the consensus value is the first matching variant.
        assertEquals(
            ResolvedField(value = "Flattened", verdict = ConfidenceVerdict.CANONICAL),
            resolved.fields["energy_descriptor"],
        )
    }

    @Test
    fun `tags partial overlap saves only tags reaching majority`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("tags" to listOf("standup", "launch-doc")))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("tags" to listOf("standup", "roadmap")))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("tags" to listOf("launch-doc", "roadmap")))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        // Each tag appears in 2 of 3 lenses; all three reach majority.
        assertEquals(
            ResolvedField(
                value = listOf("standup", "launch-doc", "roadmap"),
                verdict = ConfidenceVerdict.CANONICAL,
            ),
            resolved.fields["tags"],
        )
    }

    @Test
    fun `tags with no majority falls back to Literal's strongest as candidate`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("tags" to listOf("standup", "launch-doc")))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("tags" to listOf("roadmap")))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("tags" to listOf("review")))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(value = listOf("standup"), verdict = ConfidenceVerdict.CANDIDATE),
            resolved.fields["tags"],
        )
    }

    @Test
    fun `tags all empty resolves to canonical empty list`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("tags" to emptyList<String>()))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("tags" to emptyList<String>()))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("tags" to null))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(value = emptyList<String>(), verdict = ConfidenceVerdict.CANONICAL),
            resolved.fields["tags"],
        )
    }

    @Test
    fun `Skeptical flags whose prefix does not match a field are ignored on canonical fields`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("template_label" to "aftermath"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("template_label" to "aftermath"))
        val skeptical = LensExtraction(
            Lens.SKEPTICAL,
            fields = mapOf("template_label" to "aftermath"),
            flags = listOf("energy_descriptor:contradicts:fine"),
        )

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(
            ResolvedField(value = "aftermath", verdict = ConfidenceVerdict.CANONICAL),
            resolved.fields["template_label"],
        )
    }

    @Test
    fun `empty extraction list resolves to empty fields`() {
        val resolved = resolver.resolve(emptyList())

        assertEquals(emptyMap<String, ResolvedField>(), resolved.fields)
    }

    @Test
    fun `tags fallback uses first populated lens when Literal has none`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("tags" to emptyList<String>()))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("tags" to listOf("roadmap")))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("tags" to listOf("review")))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        // No tag reaches majority and Literal contributed nothing — fallback is the first populated
        // lens's first tag (Inferential's "roadmap").
        assertEquals(
            ResolvedField(value = listOf("roadmap"), verdict = ConfidenceVerdict.CANDIDATE),
            resolved.fields["tags"],
        )
    }

    @Test
    fun `field union covers keys present on only one lens`() {
        val literal = LensExtraction(Lens.LITERAL, fields = mapOf("template_label" to "aftermath"))
        val inferential = LensExtraction(Lens.INFERENTIAL, fields = mapOf("energy_descriptor" to "flattened"))
        val skeptical = LensExtraction(Lens.SKEPTICAL, fields = mapOf("template_label" to "aftermath"))

        val resolved = resolver.resolve(listOf(literal, inferential, skeptical))

        assertEquals(setOf("template_label", "energy_descriptor"), resolved.fields.keys)
        assertEquals(
            ResolvedField("aftermath", ConfidenceVerdict.CANONICAL),
            resolved.fields["template_label"],
        )
        assertEquals(
            ResolvedField("flattened", ConfidenceVerdict.CANDIDATE),
            resolved.fields["energy_descriptor"],
        )
    }
}
