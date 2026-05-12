package dev.anchildress1.vestige.model

/** Closed enum of pattern primitives per ADR-003 §"Pattern primitives (v1)". */
enum class PatternKind(val serial: String) {
    TEMPLATE_RECURRENCE("template_recurrence"),
    TAG_PAIR_CO_OCCURRENCE("tag_pair_co_occurrence"),
    TIME_OF_DAY_CLUSTER("time_of_day_cluster"),
    COMMITMENT_RECURRENCE("commitment_recurrence"),
    VOCAB_FREQUENCY("vocab_frequency"),
    ;

    companion object {
        fun fromSerial(serial: String): PatternKind? = entries.firstOrNull { it.serial == serial }
    }
}
