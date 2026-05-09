package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonaPromptComposerTest {

    @Test
    fun `every persona produces a non-empty composed prompt`() {
        Persona.entries.forEach { persona ->
            val prompt = PersonaPromptComposer.compose(persona)
            assertTrue(prompt.isNotBlank()) { "Prompt for $persona was blank" }
        }
    }

    @Test
    fun `every persona prompt contains the shared cognition-tracker rules`() {
        // The shared rules are the part that must NOT vary across personas — extraction relies
        // on this. Smoke-test the load by checking a phrase from `shared.txt` shows up in all.
        val sentinel = "cognition tracker"
        Persona.entries.forEach { persona ->
            val prompt = PersonaPromptComposer.compose(persona)
            assertTrue(prompt.contains(sentinel)) {
                "Prompt for $persona missing shared sentinel '$sentinel'"
            }
        }
    }

    @Test
    fun `every persona prompt names its own tone tag`() {
        assertTrue(PersonaPromptComposer.compose(Persona.WITNESS).contains("Persona: Witness"))
        assertTrue(PersonaPromptComposer.compose(Persona.HARDASS).contains("Persona: Hardass"))
        assertTrue(PersonaPromptComposer.compose(Persona.EDITOR).contains("Persona: Editor"))
    }

    @Test
    fun `prompts differ across personas in the tone wrapper, share the rules block`() {
        val witness = PersonaPromptComposer.compose(Persona.WITNESS)
        val hardass = PersonaPromptComposer.compose(Persona.HARDASS)
        val editor = PersonaPromptComposer.compose(Persona.EDITOR)

        // Must not be identical strings — the tone wrappers diverge by design.
        assertNotEquals(witness, hardass)
        assertNotEquals(witness, editor)
        assertNotEquals(hardass, editor)

        // Forbidden-phrase sentinel from the shared block must show up in all three.
        val forbidden = "Forbidden phrases:"
        assertTrue(witness.contains(forbidden))
        assertTrue(hardass.contains(forbidden))
        assertTrue(editor.contains(forbidden))
    }

    @Test
    fun `compose returns a stable string across calls (idempotent)`() {
        val first = PersonaPromptComposer.compose(Persona.WITNESS)
        val second = PersonaPromptComposer.compose(Persona.WITNESS)
        assertEquals(first, second)
    }
}
