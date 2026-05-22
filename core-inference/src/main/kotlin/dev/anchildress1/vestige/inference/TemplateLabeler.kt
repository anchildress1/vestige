package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import java.time.ZonedDateTime

/**
 * Deterministic labeler — now the validator and fallback for the model-emitted `template_label`.
 * The lenses pick one of six [TemplateLabel] serials per the output schema;
 * [BackgroundExtractionWorker] uses the model's converged pick when present and falls back to this
 * labeler when the lenses didn't agree on a serial, logging any divergence between the two. Reads
 * the resolved schema fields plus the entry's local capture time and assigns one of six
 * [TemplateLabel]s per `concept-locked.md` §"Templates" (AGENTS.md: templates are model-emitted).
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
        val tags = resolved.tagSet()

        return when {
            tags.containsAny(AFTERMATH_TAGS) -> TemplateLabel.AFTERMATH
            tags.containsAny(DECISION_SPIRAL_TAGS) -> TemplateLabel.DECISION_SPIRAL
            tags.containsAny(TUNNEL_EXIT_TAGS) -> TemplateLabel.TUNNEL_EXIT
            tags.containsAny(STALLED_TAGS) -> TemplateLabel.STALLED
            isGoblinHours(capturedAt, tags) -> TemplateLabel.GOBLIN_HOURS
            else -> TemplateLabel.AUDIT
        }
    }

    private fun isGoblinHours(capturedAt: ZonedDateTime, tags: Set<String>): Boolean =
        capturedAt.hour in GOBLIN_HOURS_RANGE && tags.containsAny(LATE_NIGHT_TAGS)

    private fun Set<String>.containsAny(candidates: Set<String>): Boolean = candidates.any { it in this }

    private fun ResolvedExtraction.tagSet(): Set<String> {
        val raw = fields[TAGS_KEY]?.takeIf { it.isLoadBearing() }?.value as? List<*>
            ?: return emptySet()
        return raw.mapNotNullTo(LinkedHashSet()) { it as? String }.mapTo(LinkedHashSet()) { it.lowercase() }
    }

    private fun ResolvedField.isLoadBearing(): Boolean =
        verdict == ConfidenceVerdict.CANONICAL || verdict == ConfidenceVerdict.CANONICAL_WITH_CONFLICT

    private companion object {
        const val TAGS_KEY = "tags"

        val GOBLIN_HOURS_RANGE: IntRange = TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE

        // State-surface late-night markers per `surfaces/state.txt`. "overnight" is the parallel
        // behavioral signal listed in `surfaces/behavioral.txt`.
        val LATE_NIGHT_TAGS = setOf("late-night", "overnight")

        // `tunnel-exit` is the state surface's archetype tag (`surfaces/state.txt:17`).
        val TUNNEL_EXIT_TAGS = setOf("tunnel-exit")

        // Demo-time widening — see `docs/backlog.md` §`labeler-prompt-tightening`. Pre-STT-C
        // tag stability the prompts emit imprecise tags; this list compensates so the demo
        // labels land. STT-C measurements will replace this with narrower, evidence-driven
        // sets — do not expand without an STT result.
        val DECISION_SPIRAL_TAGS = setOf(
            "comparing",
            "decision-loop",
            "decision-spiral",
            "rewrite",
            "rewrite-migration",
            "rewrote-again",
            "spreadsheet",
        )

        // Demo-time widening — see `docs/backlog.md` §`labeler-prompt-tightening`. Same caveat
        // as DECISION_SPIRAL_TAGS above.
        val AFTERMATH_TAGS = setOf("aftermath", "all-hands", "crash", "crashed", "hollow", "hollow-thing")

        // Resistance / paralysis vocabulary — kept narrow on purpose. STT-C tag stability will
        // expand or trim this list with measured evidence; widening it before then risks
        // false-positive labels.
        val STALLED_TAGS = setOf(
            "stuck",
            "stalled",
            "paralyzed",
            "blocked",
            "resistance",
            "task-paralysis",
        )
    }
}
