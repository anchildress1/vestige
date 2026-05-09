package dev.anchildress1.vestige.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LensExtractionTest {

    @Test
    fun `LensExtraction flags default to empty list`() {
        val extraction = LensExtraction(lens = Lens.LITERAL, fields = emptyMap())
        assertTrue(extraction.flags.isEmpty())
    }

    @Test
    fun `LensExtraction equality holds when lens fields and flags match`() {
        val a = LensExtraction(Lens.INFERENTIAL, mapOf("template_label" to "aftermath"))
        val b = LensExtraction(Lens.INFERENTIAL, mapOf("template_label" to "aftermath"))
        assertEquals(a, b)
    }

    @Test
    fun `LensExtraction equality discriminates on lens`() {
        val a = LensExtraction(Lens.LITERAL, mapOf("x" to "y"))
        val b = LensExtraction(Lens.SKEPTICAL, mapOf("x" to "y"))
        assertNotEquals(a, b)
    }

    @Test
    fun `LensExtraction equality discriminates on fields`() {
        val a = LensExtraction(Lens.LITERAL, mapOf("x" to "y"))
        val b = LensExtraction(Lens.LITERAL, mapOf("x" to "z"))
        assertNotEquals(a, b)
    }

    @Test
    fun `LensExtraction copy changes only the specified property`() {
        val original = LensExtraction(Lens.LITERAL, mapOf("k" to "v"), flags = listOf("conflict"))
        val copy = original.copy(lens = Lens.INFERENTIAL)
        assertEquals(Lens.INFERENTIAL, copy.lens)
        assertEquals(original.fields, copy.fields)
        assertEquals(original.flags, copy.flags)
    }

    @Test
    fun `LensExtraction fields can carry null values`() {
        val extraction = LensExtraction(Lens.SKEPTICAL, mapOf("stated_commitment" to null))
        assertNull(extraction.fields["stated_commitment"])
    }

    @Test
    fun `LensExtraction all three lens variants are valid`() {
        Lens.entries.forEach { lens ->
            val extraction = LensExtraction(lens = lens, fields = mapOf("field" to "value"))
            assertEquals(lens, extraction.lens)
        }
    }

    @Test
    fun `ResolvedField flags default to empty list`() {
        val field = ResolvedField(value = "aftermath", verdict = ConfidenceVerdict.CANONICAL)
        assertTrue(field.flags.isEmpty())
    }

    @Test
    fun `ResolvedField value is null for AMBIGUOUS verdict`() {
        val field = ResolvedField(value = null, verdict = ConfidenceVerdict.AMBIGUOUS)
        assertNull(field.value)
        assertEquals(ConfidenceVerdict.AMBIGUOUS, field.verdict)
    }

    @Test
    fun `ResolvedField equality holds when value verdict and flags match`() {
        val a = ResolvedField("flattened", ConfidenceVerdict.CANONICAL, flags = listOf("x"))
        val b = ResolvedField("flattened", ConfidenceVerdict.CANONICAL, flags = listOf("x"))
        assertEquals(a, b)
    }

    @Test
    fun `ResolvedField equality discriminates on verdict`() {
        val a = ResolvedField("flattened", ConfidenceVerdict.CANONICAL)
        val b = ResolvedField("flattened", ConfidenceVerdict.CANDIDATE)
        assertNotEquals(a, b)
    }

    @Test
    fun `ResolvedField equality discriminates on flags`() {
        val a = ResolvedField("flattened", ConfidenceVerdict.CANONICAL_WITH_CONFLICT, flags = listOf("flag-a"))
        val b = ResolvedField("flattened", ConfidenceVerdict.CANONICAL_WITH_CONFLICT, flags = listOf("flag-b"))
        assertNotEquals(a, b)
    }

    @Test
    fun `ResolvedField all four ConfidenceVerdict values are valid`() {
        ConfidenceVerdict.entries.forEach { verdict ->
            val field = ResolvedField(value = if (verdict == ConfidenceVerdict.AMBIGUOUS) null else "val", verdict = verdict)
            assertEquals(verdict, field.verdict)
        }
    }

    @Test
    fun `ResolvedExtraction equality holds on identical fields maps`() {
        val field = ResolvedField("aftermath", ConfidenceVerdict.CANONICAL)
        val a = ResolvedExtraction(fields = mapOf("template_label" to field))
        val b = ResolvedExtraction(fields = mapOf("template_label" to field))
        assertEquals(a, b)
    }

    @Test
    fun `ResolvedExtraction with empty fields map is valid`() {
        val extraction = ResolvedExtraction(fields = emptyMap())
        assertTrue(extraction.fields.isEmpty())
    }

    @Test
    fun `ResolvedExtraction equality discriminates on field name`() {
        val field = ResolvedField("aftermath", ConfidenceVerdict.CANONICAL)
        val a = ResolvedExtraction(fields = mapOf("template_label" to field))
        val b = ResolvedExtraction(fields = mapOf("energy_descriptor" to field))
        assertNotEquals(a, b)
    }
}
