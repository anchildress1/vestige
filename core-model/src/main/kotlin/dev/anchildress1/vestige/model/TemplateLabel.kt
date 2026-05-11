package dev.anchildress1.vestige.model

/** Agent-emitted (never user-selected). [serial] is the kebab-case form in markdown. */
enum class TemplateLabel(val serial: String) {
    AFTERMATH("aftermath"),
    TUNNEL_EXIT("tunnel-exit"),
    CONCRETE_SHOES("concrete-shoes"),
    DECISION_SPIRAL("decision-spiral"),
    GOBLIN_HOURS("goblin-hours"),
    AUDIT("audit"),
    ;

    companion object {
        /**
         * Local-hour window for both the [GOBLIN_HOURS] post-extraction label and the foreground
         * call's context-aware prompting addendum. `concept-locked.md` §"Templates" reads
         * "midnight–5am" as 00:00 inclusive to 05:00 exclusive. Owned here so the addendum window
         * and the label window can never drift apart.
         */
        val GOBLIN_HOURS_LOCAL_HOUR_RANGE: IntRange = 0..4

        fun fromSerial(serial: String): TemplateLabel? = entries.firstOrNull { it.serial == serial }
    }
}
