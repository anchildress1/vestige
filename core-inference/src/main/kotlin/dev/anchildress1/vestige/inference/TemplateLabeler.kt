package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import java.time.ZonedDateTime

/**
 * Deterministic post-convergence labeler. Reads the resolved schema fields plus the entry's local
 * capture time and assigns one of six [TemplateLabel]s per `concept-locked.md` §"Templates". Never
 * calls the model — reaching for inference here re-introduces user-facing template selection
 * through the back door.
 *
 * Only CANONICAL / CANONICAL_WITH_CONFLICT fields drive label selection: CANDIDATE values are
 * single-lens witnesses ("lower confidence, not used by pattern engine until promoted" per
 * `concept-locked.md` §"Convergence rules") and the template label feeds pattern grouping.
 *
 * `capturedAt` is a [ZonedDateTime] — both the instant *and* the user's local zone at capture
 * must be persisted with the entry so a TZ change between capture and background extraction can't
 * relabel the entry.
 */
class TemplateLabeler {

    fun label(resolved: ResolvedExtraction, capturedAt: ZonedDateTime): TemplateLabel {
        val energy = resolved.stringFieldOrNull(ENERGY_DESCRIPTOR_KEY)
        val stateShift = resolved.booleanFieldOrNull(STATE_SHIFT_KEY) == true
        val tags = resolved.tagSet()

        return when {
            isAftermath(energy, stateShift) -> TemplateLabel.AFTERMATH
            tags.containsAny(DECISION_SPIRAL_TAGS) -> TemplateLabel.DECISION_SPIRAL
            tags.containsAny(TUNNEL_EXIT_TAGS) -> TemplateLabel.TUNNEL_EXIT
            tags.containsAny(CONCRETE_SHOES_TAGS) -> TemplateLabel.CONCRETE_SHOES
            isGoblinHours(capturedAt, tags) -> TemplateLabel.GOBLIN_HOURS
            else -> TemplateLabel.AUDIT
        }
    }

    private fun isAftermath(energy: String?, stateShift: Boolean): Boolean =
        stateShift && energy?.trim()?.lowercase() == CRASHED

    private fun isGoblinHours(capturedAt: ZonedDateTime, tags: Set<String>): Boolean =
        capturedAt.hour in GOBLIN_HOURS_RANGE && tags.containsAny(LATE_NIGHT_TAGS)

    private fun Set<String>.containsAny(candidates: Set<String>): Boolean = candidates.any { it in this }

    private fun ResolvedExtraction.stringFieldOrNull(key: String): String? =
        fields[key]?.takeIf { it.isLoadBearing() }?.value as? String

    private fun ResolvedExtraction.booleanFieldOrNull(key: String): Boolean? =
        fields[key]?.takeIf { it.isLoadBearing() }?.value as? Boolean

    private fun ResolvedExtraction.tagSet(): Set<String> {
        val raw = fields[TAGS_KEY]?.takeIf { it.isLoadBearing() }?.value as? List<*>
            ?: return emptySet()
        return raw.mapNotNullTo(LinkedHashSet()) { it as? String }.mapTo(LinkedHashSet()) { it.lowercase() }
    }

    private fun ResolvedField.isLoadBearing(): Boolean =
        verdict == ConfidenceVerdict.CANONICAL || verdict == ConfidenceVerdict.CANONICAL_WITH_CONFLICT

    private companion object {
        const val TAGS_KEY = "tags"
        const val ENERGY_DESCRIPTOR_KEY = "energy_descriptor"
        const val STATE_SHIFT_KEY = "state_shift"

        const val CRASHED = "crashed"

        val GOBLIN_HOURS_RANGE: IntRange = TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE

        // State-surface late-night markers per `surfaces/state.txt`. "overnight" is the parallel
        // behavioral signal listed in `surfaces/behavioral.txt`.
        val LATE_NIGHT_TAGS = setOf("late-night", "overnight")

        // `tunnel-exit` is the state surface's archetype tag (`surfaces/state.txt:17`). The bare
        // `tunnel` is the energy descriptor list, not a tag — it lands in `energy_descriptor`.
        val TUNNEL_EXIT_TAGS = setOf("tunnel-exit")

        val DECISION_SPIRAL_TAGS = setOf("decision-loop", "decision-spiral")

        // Resistance / paralysis vocabulary — kept narrow on purpose. STT-C tag stability will
        // expand or trim this list with measured evidence; widening it before then risks
        // false-positive labels.
        val CONCRETE_SHOES_TAGS = setOf(
            "stuck",
            "stalled",
            "paralyzed",
            "blocked",
            "resistance",
            "concrete-shoes",
            "task-paralysis",
        )
    }
}
