package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.Persona
import org.junit.jupiter.api.Assertions.assertAll
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

        val sharedSentinel = "The follow-up is memory completion"
        assertTrue(witness.contains(sharedSentinel))
        assertTrue(hardass.contains(sharedSentinel))
        assertTrue(editor.contains(sharedSentinel))
    }

    @Test
    fun `compose returns a stable string across calls (idempotent)`() {
        val first = PersonaPromptComposer.compose(Persona.WITNESS)
        val second = PersonaPromptComposer.compose(Persona.WITNESS)
        assertEquals(first, second)
    }

    @Test
    fun `shared prompt targets missing observable facts instead of next actions`() {
        Persona.entries.forEach { persona ->
            val prompt = PersonaPromptComposer.compose(persona)
            assertAll(
                { assertTrue(prompt.contains("The primary thing the user explicitly recorded")) },
                { assertTrue(prompt.contains("The secondary thing they also recorded")) },
                { assertTrue(prompt.contains("The non-obvious missing detail")) },
                { assertTrue(prompt.contains("What got stuck, repeated, reopened")) },
                { assertTrue(prompt.contains("Use artifact names only when they belong to the missing detail")) },
                {
                    assertTrue(
                        prompt.contains(
                            "Only when the transcript already names a user-side state word " +
                                "or state phrase may you ask for the missing state detail",
                        ),
                    )
                },
                { assertTrue(prompt.contains("Ask one recall question about the original moment")) },
                { assertTrue(prompt.contains("Do not ask for a next action, deadline, plan")) },
                { assertTrue(prompt.contains("Keep the question anchored to recall")) },
                { assertTrue(prompt.contains("The silent checklist is identical for every persona")) },
                { assertTrue(prompt.contains("Do not output this checklist")) },
            )
        }
    }

    @Test
    fun `hardass prompt is recall focused and forbids action pressure`() {
        val hardass = PersonaPromptComposer.compose(Persona.HARDASS)
        assertAll(
            { assertTrue(hardass.contains("recall-focused")) },
            { assertTrue(hardass.contains("Push recall only. Do not push action.")) },
            { assertTrue(hardass.contains("Open by naming what the entry failed to capture")) },
            { assertTrue(hardass.contains("Use one of these question starts")) },
            { assertTrue(hardass.contains("You recorded the renaming loop, not the before-moment")) },
        )
    }

    @Test
    fun `editor prompt recovers precise wording without collapsing into witness`() {
        val editor = PersonaPromptComposer.compose(Persona.EDITOR)
        assertAll(
            { assertTrue(editor.contains("linguistic bullshit")) },
            { assertTrue(editor.contains("Do not merge two recorded facts into a new claim")) },
            { assertTrue(editor.contains("Point at the word or phrase doing too much work")) },
            { assertTrue(editor.contains("'Still open' names the doc, not you")) },
            { assertTrue(editor.contains("Ask for the missing edge around that quoted phrase")) },
            { assertTrue(editor.contains("End with one short precision recall question")) },
            { assertTrue(editor.contains("What word belongs before it?")) },
            { assertTrue(editor.contains("Do not ask for feelings the transcript never named")) },
            { assertTrue(editor.contains("What exactly kept the choice from closing?")) },
        )
    }
}
