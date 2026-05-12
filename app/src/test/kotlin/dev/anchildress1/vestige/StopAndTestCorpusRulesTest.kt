package dev.anchildress1.vestige

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StopAndTestCorpusRulesTest {

    @Test
    fun `STT-D divergence threshold rounds up to 30 percent of the manifest`() {
        assertEquals(2, StopAndTestCorpusRules.requiredDivergentEntries(6))
        assertEquals(3, StopAndTestCorpusRules.requiredDivergentEntries(7))
        assertEquals(6, StopAndTestCorpusRules.requiredDivergentEntries(18))
    }

    @Test
    fun `STT-D rejects undersized manifests`() {
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requiredDivergentEntries(5)
        }
    }

    @Test
    fun `STT-D accepts the canonical 6 pressure points regardless of order`() {
        StopAndTestCorpusRules.requireSttDCorpusCoversPressurePoints(
            listOf("D1", "C2", "B2", "B1", "A4", "A1"),
        )
    }

    @Test
    fun `STT-D accepts the canonical 6 plus extra A-D scenario entries`() {
        StopAndTestCorpusRules.requireSttDCorpusCoversPressurePoints(
            listOf("A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "C1", "C2", "C3", "D1", "D2", "D3"),
        )
    }

    @Test
    fun `STT-D rejects a subset manifest missing pressure points`() {
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requireSttDCorpusCoversPressurePoints(listOf("A1", "B1"))
        }
    }

    @Test
    fun `STT-D rejects a manifest that swaps a pressure point for a non-canonical id`() {
        // Has A1-A6 but no B1, B2, C2 → missing pressure points despite the right count.
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requireSttDCorpusCoversPressurePoints(
                listOf("A1", "A2", "A3", "A4", "A5", "A6"),
            )
        }
    }

    @Test
    fun `STT-D rejects X distractor ids — only A-D scenarios allowed`() {
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requireSttDCorpusCoversPressurePoints(
                listOf("A1", "A4", "B1", "B2", "C2", "D1", "X1"),
            )
        }
    }

    @Test
    fun `STT-D rejects duplicates`() {
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requireSttDCorpusCoversPressurePoints(
                listOf("A1", "A1", "B1", "B2", "C2", "D1"),
            )
        }
    }

    @Test
    fun `STT-C accepts the canonical 18-entry corpus regardless of order`() {
        StopAndTestCorpusRules.requireCanonicalSttCCorpus(
            listOf(
                "X3",
                "D3",
                "C3",
                "B3",
                "A6",
                "X2",
                "D2",
                "C2",
                "B2",
                "A5",
                "X1",
                "D1",
                "C1",
                "B1",
                "A4",
                "A3",
                "A2",
                "A1",
            ),
        )
    }

    @Test
    fun `STT-C rejects a subset manifest`() {
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requireCanonicalSttCCorpus(listOf("A1", "A2", "A3"))
        }
    }

    @Test
    fun `STT-C rejects duplicates or unexpected ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            StopAndTestCorpusRules.requireCanonicalSttCCorpus(
                listOf(
                    "A1",
                    "A2",
                    "A3",
                    "A4",
                    "A5",
                    "A6",
                    "B1",
                    "B2",
                    "B3",
                    "C1",
                    "C2",
                    "C3",
                    "D1",
                    "D2",
                    "D3",
                    "X1",
                    "X2",
                    "X2",
                ),
            )
        }
    }
}
