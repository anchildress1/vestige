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
        fun fromSerial(serial: String): TemplateLabel? = entries.firstOrNull { it.serial == serial }
    }
}
