package dev.anchildress1.vestige

import kotlin.math.ceil

/** Shared corpus gate logic for the STT-C / STT-D harnesses and their JVM unit tests. */
internal object StopAndTestCorpusRules {
    private const val STT_D_DIVERGENCE_THRESHOLD = 0.30

    private val sttDPressurePointIds: Set<String> = linkedSetOf("A1", "A4", "B1", "B2", "C2", "D1")

    private val sttCCanonicalIds: Set<String> = linkedSetOf(
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
        "X3",
    )

    private val sttDExtendedAllowedIds: Set<String> = sttCCanonicalIds - setOf("X1", "X2", "X3")

    /** `ceil(corpusSize × 30 %)`. Throws below the canonical STT-D pressure-point size. */
    fun requiredDivergentEntries(corpusSize: Int): Int {
        require(corpusSize >= sttDPressurePointIds.size) {
            "STT-D manifest must include at least ${sttDPressurePointIds.size} entries; got $corpusSize"
        }
        return ceil(corpusSize * STT_D_DIVERGENCE_THRESHOLD).toInt()
    }

    /**
     * Accept any STT-D corpus that contains the six spec-named pressure points
     * (`sample-data-scenarios.md` §STT-D) plus zero or more additional A-D scenario entries.
     * Distractor IDs (X1-X3) and unknown IDs are rejected — extras must come from the STT-C
     * canonical scenario set minus the distractors.
     */
    fun requireSttDCorpusCoversPressurePoints(ids: List<String>) {
        require(ids.distinct().size == ids.size) {
            "STT-D manifest must not contain duplicates; got $ids"
        }
        val missing = sttDPressurePointIds - ids.toSet()
        require(missing.isEmpty()) {
            "STT-D manifest must include the canonical pressure-point set " +
                "(${sttDPressurePointIds.joinToString(", ")}); missing $missing"
        }
        val unknown = ids.toSet() - sttDExtendedAllowedIds
        require(unknown.isEmpty()) {
            "STT-D manifest contains unrecognized ids $unknown; entries must come from " +
                "the A-D scenario set (no X distractors)"
        }
    }

    fun requireCanonicalSttCCorpus(ids: List<String>) {
        requireExactSet(ids, sttCCanonicalIds, label = "STT-C", canonicalDescription = "A1-D3 + X1-X3")
    }

    private fun requireExactSet(
        ids: List<String>,
        canonical: Set<String>,
        label: String,
        canonicalDescription: String,
    ) {
        require(ids.size == canonical.size) {
            "$label manifest must contain exactly ${canonical.size} entries " +
                "($canonicalDescription); got ${ids.size}"
        }
        require(ids.toSet() == canonical) {
            "$label manifest must contain exactly $canonicalDescription; got $ids"
        }
    }
}
