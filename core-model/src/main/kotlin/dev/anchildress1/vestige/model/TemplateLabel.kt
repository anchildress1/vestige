package dev.anchildress1.vestige.model

/**
 * Six agent-emitted labels per `concept-locked.md` §Templates. Templates are model output, not
 * user-selected modes (AGENTS.md guardrail 10). [serial] is the kebab-case form persisted to
 * markdown frontmatter.
 */
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
