package dev.anchildress1.vestige

import kotlin.math.ceil

/** Shared corpus gate logic for the STT-C / STT-D harnesses and their JVM unit tests. */
internal object StopAndTestCorpusRules {
    private const val STT_D_DIVERGENCE_THRESHOLD = 0.30

    private val sttDCanonicalIds: Set<String> = linkedSetOf("A1", "A4", "B1", "B2", "C2", "D1")

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

    /** `ceil(corpusSize × 30 %)`. Throws below the canonical STT-D corpus size. */
    fun requiredDivergentEntries(corpusSize: Int): Int {
        require(corpusSize >= sttDCanonicalIds.size) {
            "STT-D manifest must include at least ${sttDCanonicalIds.size} entries; got $corpusSize"
        }
        return ceil(corpusSize * STT_D_DIVERGENCE_THRESHOLD).toInt()
    }

    fun requireCanonicalSttDCorpus(ids: List<String>) {
        requireExactSet(ids, sttDCanonicalIds, label = "STT-D", canonicalDescription = "A1, A4, B1, B2, C2, D1")
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
