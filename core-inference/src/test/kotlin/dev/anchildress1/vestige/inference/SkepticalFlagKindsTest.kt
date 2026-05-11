package dev.anchildress1.vestige.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkepticalFlagKindsTest {

    @Test
    fun `schema-binding registry mirrors lenses_skeptical txt`() {
        // Locked set; updating it requires touching resources/lenses/skeptical.txt + this test.
        assertEquals(
            mapOf(
                "vocabulary-contradiction" to "energy_descriptor",
                "state-behavior-mismatch" to "energy_descriptor",
                "commitment-without-anchor" to "stated_commitment",
                "unsupported-recurrence" to "recurrence_link",
            ),
            SkepticalFlagKinds.SCHEMA_BINDING,
        )
    }

    @Test
    fun `isSchemaBinding accepts known kinds with snippet and note suffixes`() {
        assertTrue(SkepticalFlagKinds.isSchemaBinding("vocabulary-contradiction:fine before vs flattened:state shift"))
        assertTrue(SkepticalFlagKinds.isSchemaBinding("state-behavior-mismatch::"))
        assertTrue(SkepticalFlagKinds.isSchemaBinding("commitment-without-anchor:send invoice:"))
    }

    @Test
    fun `isSchemaBinding rejects entry-level kinds`() {
        assertFalse(SkepticalFlagKinds.isSchemaBinding("time-inconsistency:11am vs lunch:"))
        assertFalse(SkepticalFlagKinds.isSchemaBinding("other:something:"))
    }

    @Test
    fun `isSchemaBinding rejects malformed and empty flags`() {
        assertFalse(SkepticalFlagKinds.isSchemaBinding(""))
        assertFalse(SkepticalFlagKinds.isSchemaBinding("no-colon-here"))
        assertFalse(SkepticalFlagKinds.isSchemaBinding(":no-kind:"))
    }
}
