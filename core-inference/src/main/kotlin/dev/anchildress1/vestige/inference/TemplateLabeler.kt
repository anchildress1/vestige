package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.TemplateLabel
import java.time.ZonedDateTime

/**
 * The goblin-window predicate. `template_label` is the model's pick; the only deterministic override
 * is that a non-committal `audit` becomes Goblin Hours when the entry was captured between
 * midnight–5am local — see [BackgroundExtractionWorker.resolveTemplateLabel]. This class answers the
 * one question that override needs: was the capture inside the goblin window? It is a *clock* call,
 * read from the timestamp — never from the model spotting an hour in the text (the text can lie; an
 * entry that says "1am" may have been captured at 8pm).
 *
 * `capturedAt` is a [ZonedDateTime] — the user's local zone at capture must ride with the entry so a
 * timezone / DST shift between capture and background extraction can't relabel it.
 */
class TemplateLabeler {

    fun isGoblinHours(capturedAt: ZonedDateTime): Boolean = capturedAt.hour in GOBLIN_HOURS_RANGE

    private companion object {
        val GOBLIN_HOURS_RANGE: IntRange = TemplateLabel.GOBLIN_HOURS_LOCAL_HOUR_RANGE
    }
}
