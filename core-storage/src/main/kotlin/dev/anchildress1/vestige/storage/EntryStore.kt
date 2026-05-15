package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ObservationEvidence
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
// Two-phase lifecycle + observation append + read APIs land naturally above the default ceiling.
@Suppress("TooManyFunctions")
class EntryStore(private val boxStore: BoxStore, private val markdownStore: MarkdownEntryStore) {

    /**
     * Persist the user transcription before extraction begins. The returned id is stable for the
     * entry's lifetime and is the handle used to drive
     * [dev.anchildress1.vestige.inference.ExtractionStatusListener] callbacks into
     * [io.objectbox.BoxStore]-backed status tracking.
     */
    fun createPendingEntry(entryText: String, timestamp: Instant, durationMs: Long = 0L): Long {
        require(entryText.isNotBlank()) { "EntryStore.createPendingEntry requires a non-blank entryText" }
        val entry = EntryEntity(
            entryText = entryText.trimEnd(),
            timestampEpochMs = timestamp.toEpochMilli(),
            durationMs = durationMs,
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

    /** Read-only lookup. Returns `null` for missing rows so callers can act without throwing. */
    fun readEntry(entryId: Long): EntryEntity? = boxStore.boxFor<EntryEntity>().get(entryId)

    /** Total persisted rows, regardless of extraction terminality. */
    fun count(): Long = boxStore.boxFor<EntryEntity>().count()

    /** Completed entries only — denominator for pattern stats and pattern-empty-state gating. */
    fun countCompleted(): Long = boxStore.boxFor<EntryEntity>()
        .query()
        .equal(EntryEntity_.extractionStatus, ExtractionStatus.COMPLETED.name, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .build()
        .use { it.count() }

    /** Most-recent completed entries, newest first. [limit] is a guard, not pagination. */
    fun listCompleted(limit: Int = 100): List<EntryEntity> = boxStore.boxFor<EntryEntity>()
        .query()
        .equal(EntryEntity_.extractionStatus, ExtractionStatus.COMPLETED.name, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .orderDesc(EntryEntity_.timestampEpochMs)
        .build()
        .use { it.find(0, limit.toLong()) }

    /** Single most-recent completed entry, or `null` when none exist. */
    fun lastCompleted(): EntryEntity? = boxStore.boxFor<EntryEntity>()
        .query()
        .equal(EntryEntity_.extractionStatus, ExtractionStatus.COMPLETED.name, QueryBuilder.StringOrder.CASE_SENSITIVE)
        .orderDesc(EntryEntity_.timestampEpochMs)
        .build()
        .use { it.find(0, 1).firstOrNull() }

    /**
     * Append one observation to an already-completed entry's persisted list. Used by the
     * pattern-detection orchestrator when a callout fires after `completeEntry` has already
     * landed. [afterPersist] runs inside the same ObjectBox write transaction so the caller can
     * atomically update adjacent structured state (for example, the global callout cooldown row).
     * Throws when the entry is missing — callers must hold a valid id.
     */
    fun appendObservation(entryId: Long, observation: EntryObservation, afterPersist: (() -> Unit)? = null) {
        boxStore.runInTx {
            val box = boxStore.boxFor<EntryEntity>()
            val entry = box.get(entryId)
                ?: throw EntryPersistenceException("No entry row id=$entryId to append observation")
            // Refuse to overwrite a malformed observations array. `decodeObservations` returns an
            // empty list on parse failure (with a logged warning); appending in that branch would
            // silently destroy every previously persisted observation for this entry. Aborting
            // here surfaces the corruption via `EntryPersistenceException`, which the save flow's
            // orchestrator-wrapper catches → callout is dropped, cooldown reservation released.
            val existing = parseObservationsForAppend(entry.entryObservationsJson, entryId)
            entry.entryObservationsJson = observationsJson(existing + observation)
            try {
                markdownStore.write(entry)
            } catch (@Suppress("TooGenericExceptionCaught") writeFail: Exception) {
                throw EntryPersistenceException(
                    "Markdown rewrite failed appending observation to id=$entryId",
                    writeFail,
                )
            }
            box.put(entry)
            afterPersist?.invoke()
        }
    }

    /**
     * Terminal failure path — [status] is one of [ExtractionStatus.FAILED] or
     * [ExtractionStatus.TIMED_OUT]. Leaves the structured fields untouched; the row keeps the
     * `entry_text` already persisted by [createPendingEntry]. Markdown stays in PENDING shape.
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

// Top-level helpers — kept off `EntryStore` to stay under detekt's function budget.
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

// Cap on the malformed-payload preview included in `Log.w` lines so logcat doesn't get
// flooded by a single corrupt row. 80 chars is enough to identify the shape (object vs
// array, leading keys) without paying for the long tail.
private const val LOG_PREVIEW_CHARS = 80

// Used by `appendObservation` only — distinguishes legit empty from malformed-and-fell-back.
// Throws so the malformed-existing case can't silently overwrite real persisted observations.
private fun parseObservationsForAppend(json: String, entryId: Long): List<EntryObservation> {
    if (json.isBlank() || json.trim() == "[]") return emptyList()
    val parsed = decodeObservations(json)
    if (parsed.isEmpty()) {
        throw EntryPersistenceException(
            "Refusing to append observation to entry id=$entryId — existing JSON is malformed",
        )
    }
    return parsed
}

private fun decodeObservations(json: String): List<EntryObservation> {
    val raw = json.takeIf { it.isNotBlank() } ?: return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull()
    // `appendObservation` rewrites this field — if we cannot read the existing list, the next
    // write would silently overwrite real persisted observations. Surface it then fall back to
    // empty so the rewrite path can still make progress.
    return when (array) {
        null -> {
            android.util.Log.w("VestigeEntryStore", "malformed entryObservationsJson: ${raw.take(LOG_PREVIEW_CHARS)}")
            emptyList()
        }

        else -> (0 until array.length()).mapNotNull { idx -> decodeOne(array.optJSONObject(idx)) }
    }
}

private fun decodeOne(obj: JSONObject?): EntryObservation? {
    val text = obj?.optString("text")?.takeIf { it.isNotBlank() }
    val evidence = obj?.optString("evidence")?.let { ObservationEvidence.fromSerial(it) }
    val fields = obj?.optJSONArray("fields")?.let { arr ->
        (0 until arr.length()).mapNotNull { (arr.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } }
    } ?: emptyList()
    return if (text != null && evidence != null) EntryObservation(text, evidence, fields) else null
}
