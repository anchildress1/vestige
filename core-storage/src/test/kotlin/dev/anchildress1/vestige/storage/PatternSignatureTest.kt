package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.PatternKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the ADR-003 §"`pattern_id` generation" determinism guarantees that drove the kebab-case
 * normalization fix — surface-form drift between separator conventions must collapse to one id.
 */
class PatternSignatureTest {

    @Test
    fun `tag-pair signature is stable across space vs hyphen surface forms`() {
        val a = PatternSignature.forTagPair("aftermath", setOf("tuesday meeting", "crashed"))
        val b = PatternSignature.forTagPair("aftermath", setOf("tuesday-meeting", "crashed"))
        assertEquals(a.patternId, b.patternId)
    }

    @Test
    fun `tag-pair signature is stable across underscore surface forms`() {
        val a = PatternSignature.forTagPair("aftermath", setOf("tuesday_meeting", "crashed"))
        val b = PatternSignature.forTagPair("aftermath", setOf("tuesday-meeting", "crashed"))
        assertEquals(a.patternId, b.patternId)
    }

    @Test
    fun `tag-pair signature is stable regardless of input order`() {
        val a = PatternSignature.forTagPair("aftermath", setOf("standup", "crashed"))
        val b = PatternSignature.forTagPair("aftermath", setOf("crashed", "standup"))
        assertEquals(a.patternId, b.patternId)
    }

    @Test
    fun `template signature kebabs the label surface form`() {
        val a = PatternSignature.forTemplateRecurrence("Tunnel Exit")
        val b = PatternSignature.forTemplateRecurrence("tunnel-exit")
        assertEquals(a.patternId, b.patternId)
    }

    @Test
    fun `commitment signature kebabs the topic surface form`() {
        val a = PatternSignature.forCommitment("Project Vestige")
        val b = PatternSignature.forCommitment("project-vestige")
        assertEquals(a.patternId, b.patternId)
    }

    @Test
    fun `different kinds with the same key never collide`() {
        val templateId = PatternSignature.forTemplateRecurrence("aftermath").patternId
        val vocabId = PatternSignature.forVocabToken("aftermath").patternId
        val commitmentId = PatternSignature.forCommitment("aftermath").patternId
        assertNotEquals(templateId, vocabId)
        assertNotEquals(templateId, commitmentId)
        assertNotEquals(vocabId, commitmentId)
    }

    @Test
    fun `vocab token signature is independent of kind discriminator across cases`() {
        val a = PatternSignature.forVocabToken("Tired")
        val b = PatternSignature.forVocabToken("tired")
        assertEquals(a.patternId, b.patternId)
        assertEquals(PatternKind.VOCAB_FREQUENCY, a.kind)
    }

    @Test
    fun `templateLabel field carries the kebabed form for downstream filtering`() {
        val sig = PatternSignature.forTemplateRecurrence("Tunnel Exit")
        assertEquals("tunnel-exit", sig.templateLabel)
    }
}
