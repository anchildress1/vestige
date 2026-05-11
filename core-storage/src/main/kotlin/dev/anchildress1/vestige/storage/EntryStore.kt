package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Joint owner of the markdown source-of-truth and the ObjectBox `EntryEntity` row.
 *
 * Two-phase lifecycle per ADR-001 §Q3 and architecture-brief §"Sync direction":
 *   1. [createPendingEntry] — foreground call returned. Persist `entry_text` + timestamp +
 *      `extraction_status=PENDING`. Returns the assigned entry id so the caller can register an
 *      [dev.anchildress1.vestige.inference.ExtractionStatusListener][listener] against it.
 *   2. [completeEntry] / [failEntry] — background extraction terminal. Updates the row + rewrites
 *      the markdown front-matter atomically.
 *
 * **Transactional contract.** Markdown is the source of truth, but the box write is staged first
 * inside `boxStore.callInTx` / `runInTx` so the row can mint its auto-id; the markdown write
 * happens next; on markdown failure the throw escapes the transaction and ObjectBox rolls back
 * the staged row before it commits. From a durable-state perspective the contract that holds is
 * "no markdown ⇒ no ObjectBox row" — the order of the in-flight operations is box-first only
 * within the un-committed transaction window.
 */
class EntryStore(private val boxStore: BoxStore, private val markdownStore: MarkdownEntryStore) {

    /**
     * Persist the user transcription before extraction begins. The returned id is stable for the
     * entry's lifetime and is the handle used to drive
     * [dev.anchildress1.vestige.inference.ExtractionStatusListener] callbacks into
     * [io.objectbox.BoxStore]-backed status tracking.
     */
    fun createPendingEntry(entryText: String, timestamp: Instant): Long {
        require(entryText.isNotBlank()) { "EntryStore.createPendingEntry requires a non-blank entryText" }
        val entry = EntryEntity(
            entryText = entryText.trimEnd(),
            timestampEpochMs = timestamp.toEpochMilli(),
            extractionStatus = ExtractionStatus.PENDING,
        )
        return boxStore.callInTx<Long> {
            val box = boxStore.boxFor<EntryEntity>()
            val id = box.put(entry)
            try {
                markdownStore.write(entry)
            } catch (@Suppress("TooGenericExceptionCaught") writeFail: Exception) {
                // Markdown is the source of truth; box-and-no-markdown is the inconsistency we
                // must not ship. Roll the row back inside the same transaction.
                throw EntryPersistenceException("Markdown write failed for entry id=$id", writeFail)
            }
            // Re-put so the markdown filename (assigned by MarkdownEntryStore) lands on the row.
            box.put(entry)
            id
        }
    }

    /**
     * Convergence resolved successfully. Maps [resolved] + [templateLabel] + [observations] onto
     * the row and rewrites the markdown front-matter. Status transitions to `COMPLETED`;
     * `lastError` clears. Pass an empty [observations] list when none are available — the
     * markdown front-matter renders `entry_observations: []` and the pattern engine ignores the
     * row for observation surfacing.
     */
    fun completeEntry(
        entryId: Long,
        resolved: ResolvedExtraction,
        templateLabel: TemplateLabel?,
        observations: List<EntryObservation> = emptyList(),
    ) {
        boxStore.runInTx {
            val box = boxStore.boxFor<EntryEntity>()
            val entry = box.get(entryId)
                ?: throw EntryPersistenceException("No entry row id=$entryId to complete")
            applyResolved(entry, resolved, templateLabel)
            entry.entryObservationsJson = observationsJson(observations)
            entry.extractionStatus = ExtractionStatus.COMPLETED
            entry.lastError = null
            attachTags(entry, resolved)
            try {
                markdownStore.write(entry)
            } catch (@Suppress("TooGenericExceptionCaught") writeFail: Exception) {
                throw EntryPersistenceException("Markdown rewrite failed for entry id=$entryId", writeFail)
            }
            box.put(entry)
        }
    }

    /**
     * Terminal failure path — [status] is one of [ExtractionStatus.FAILED] or
     * [ExtractionStatus.TIMED_OUT]. Leaves the structured fields untouched; the row keeps the
     * `entry_text` already persisted by [createPendingEntry]. Markdown stays in PENDING shape.
     *
     * `attemptCount` is the cold-start sweep's counter per ADR-001 §Q3 — it is owned by the
     * recovery path, not by this terminal call. The sweep increments before re-invoking the
     * worker; a single terminal failure does not advance the counter on its own.
     */
    fun failEntry(entryId: Long, status: ExtractionStatus, lastError: String?) {
        require(status == ExtractionStatus.FAILED || status == ExtractionStatus.TIMED_OUT) {
            "EntryStore.failEntry requires terminal-fail status (got $status)"
        }
        boxStore.runInTx {
            val box = boxStore.boxFor<EntryEntity>()
            val entry = box.get(entryId)
                ?: throw EntryPersistenceException("No entry row id=$entryId to fail")
            entry.extractionStatus = status
            entry.lastError = lastError
            box.put(entry)
        }
    }

    private fun applyResolved(entry: EntryEntity, resolved: ResolvedExtraction, templateLabel: TemplateLabel?) {
        entry.templateLabel = templateLabel
        entry.energyDescriptor = stringField(resolved, KEY_ENERGY)
        entry.recurrenceLink = stringField(resolved, KEY_RECURRENCE)
        entry.statedCommitmentJson = commitmentJson(resolved)
        entry.confidenceJson = confidenceJson(resolved)
    }

    private fun attachTags(entry: EntryEntity, resolved: ResolvedExtraction) {
        val resolvedTags = resolved.fields[KEY_TAGS]
            ?.takeIf { it.verdict in PROMOTABLE_VERDICTS }
            ?.value
        val names = (resolvedTags as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            ?: emptyList()
        val previous = entry.tags.toList()
        val tagBox = boxStore.boxFor<TagEntity>()
        val resolvedEntities = names.map { name ->
            val existing = tagBox.query()
                .equal(TagEntity_.name, name, QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
                .use { it.findFirst() }
            existing ?: TagEntity(name = name).also { tagBox.put(it) }
        }
        entry.tags.clear()
        entry.tags.addAll(resolvedEntities)
        // entryCount maintenance: increment new links, decrement orphaned ones.
        val added = resolvedEntities.filter { tag -> previous.none { it.id == tag.id } }
        val removed = previous.filter { tag -> resolvedEntities.none { it.id == tag.id } }
        added.forEach { tag ->
            tag.entryCount += 1
            tagBox.put(tag)
        }
        removed.forEach { tag ->
            tag.entryCount = (tag.entryCount - 1).coerceAtLeast(0)
            tagBox.put(tag)
        }
    }

    private fun stringField(resolved: ResolvedExtraction, key: String): String? {
        val field = resolved.fields[key] ?: return null
        return (field.value as? String)?.takeIf { it.isNotBlank() }
    }

    private fun commitmentJson(resolved: ResolvedExtraction): String? {
        val map = resolved.fields[KEY_COMMITMENT]?.value as? Map<*, *>
        return map?.let { JSONObject(it.mapKeys { entry -> entry.key.toString() }).toString() }
    }

    private fun confidenceJson(resolved: ResolvedExtraction): String {
        val payload = JSONObject()
        resolved.fields.forEach { (key, field) -> payload.put(key, field.verdict.name) }
        return payload.toString()
    }

    private fun observationsJson(observations: List<EntryObservation>): String {
        if (observations.isEmpty()) return "[]"
        val array = JSONArray()
        observations.forEach { observation ->
            val obj = JSONObject()
                .put("text", observation.text)
                .put("evidence", observation.evidence.serial)
                .put("fields", JSONArray(observation.fields))
            array.put(obj)
        }
        return array.toString()
    }

    private companion object {
        private const val KEY_TAGS = "tags"
        private const val KEY_ENERGY = "energy_descriptor"
        private const val KEY_RECURRENCE = "recurrence_link"
        private const val KEY_COMMITMENT = "stated_commitment"
        private val PROMOTABLE_VERDICTS = setOf(
            ConfidenceVerdict.CANONICAL,
            ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
        )
    }
}

/** Failure on the markdown/ObjectBox join. The transaction the throw escapes from rolls back. */
class EntryPersistenceException(message: String, cause: Throwable? = null) : IOException(message, cause)
