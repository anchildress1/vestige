package dev.anchildress1.vestige

import kotlin.math.ceil

/**
 * Validation rules for Phase 2 stop-and-test harnesses.
 *
 * These stay as plain Kotlin so the instrumentation harnesses and JVM unit tests can share the
 * exact same gate logic.
 */
internal object StopAndTestCorpusRules {
    private const val STT_D_MINIMUM_CORPUS_SIZE = 6
    private const val STT_D_DIVERGENCE_THRESHOLD = 0.30

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

    fun requiredDivergentEntries(corpusSize: Int): Int {
        require(corpusSize >= STT_D_MINIMUM_CORPUS_SIZE) {
            "STT-D manifest must include at least $STT_D_MINIMUM_CORPUS_SIZE entries; got $corpusSize"
        }
        return ceil(corpusSize * STT_D_DIVERGENCE_THRESHOLD).toInt()
    }

    fun requireCanonicalSttCCorpus(ids: List<String>) {
        require(ids.size == sttCCanonicalIds.size) {
            "STT-C manifest must contain exactly ${sttCCanonicalIds.size} entries " +
                "(A1-D3 + X1-X3); got ${ids.size}"
        }
        val actualIds = ids.toSet()
        require(actualIds == sttCCanonicalIds) {
            "STT-C manifest must contain exactly A1-D3 + X1-X3; got $ids"
        }
    }
}
