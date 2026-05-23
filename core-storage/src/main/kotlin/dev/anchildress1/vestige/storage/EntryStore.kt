package dev.anchildress1.vestige.storage

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.EntryLensReceipt
import dev.anchildress1.vestige.model.EntryObservation
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.ObservationEvidence
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.TemplateLabel
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Locale

/**
 * ObjectBox owner for entry rows. Persists the two-phase pending → completed/failed lifecycle
 * (ADR-001 §Q3).
 */
// Two-phase lifecycle + observation append + read APIs land naturally above the default ceiling.
@Suppress("TooManyFunctions")
class EntryStore(private val boxStore: BoxStore) {

    /**
     * Persist the user transcription before extraction begins. The returned id is stable for the
     * entry's lifetime and is the handle used to drive
     * [dev.anchildress1.vestige.inference.ExtractionStatusListener] callbacks into
     * [io.objectbox.BoxStore]-backed status tracking.
     */
    @Suppress("LongParameterList") // Entry seed needs the full saved single-turn transcript payload.
    fun createPendingEntry(
        entryText: String,
        timestamp: Instant,
        durationMs: Long = 0L,
        followUpText: String? = null,
        persona: Persona = Persona.WITNESS,
    ): Long {
        require(entryText.isNotBlank()) { "EntryStore.createPendingEntry requires a non-blank entryText" }
        val entry = EntryEntity(
            entryText = entryText.trimEnd(),
            followUpText = followUpText?.trimEnd()?.takeIf(String::isNotBlank),
            persona = persona,
            timestampEpochMs = timestamp.toEpochMilli(),
            durationMs = durationMs,
            extractionStatus = ExtractionStatus.PENDING,
        )
        return boxStore.callInTx<Long> {
            val box = boxStore.boxFor<EntryEntity>()
            entry.markdownFilename = uniqueMarkdownFilename(box, entry)
            box.put(entry)
        }
    }

    /**
     * Convergence resolved successfully. Maps [resolved] + [templateLabel] + [observations] onto the row.
     * Status transitions to `COMPLETED`; `lastError` clears. Pass an empty [observations] list when none are
     * available — the pattern engine ignores the row for observation surfacing.
     */
    fun completeEntry(
        entryId: Long,
        resolved: ResolvedExtraction,
        templateLabel: TemplateLabel?,
        observations: List<EntryObservation> = emptyList(),
        lensReceipts: List<EntryLensReceipt> = emptyList(),
    ) {
        boxStore.runInTx {
            val box = boxStore.boxFor<EntryEntity>()
            val entry = box.get(entryId)
                ?: throw EntryPersistenceException("No entry row id=$entryId to complete")
            applyResolved(entry, resolved, templateLabel)
            entry.entryObservationsJson = observationsJson(observations)
            entry.lensReceiptsJson = EntryLensReceiptJson.encode(lensReceipts)
            entry.extractionStatus = ExtractionStatus.COMPLETED
            entry.lastError = null
            attachTags(entry, resolved)
            box.put(entry)
        }
    }

    /** Read-only lookup. Returns `null` for missing rows so callers can act without throwing. */
    fun readEntry(entryId: Long): EntryEntity? = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>().get(entryId)
    }

    /** Most-recent entry still in-flight, or `null` when no notification deep-link target exists. */
    fun mostRecentNonTerminalEntryId(): Long? = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>()
            .query()
            .`in`(EntryEntity_.extractionStatus, NON_TERMINAL_STATUS_NAMES, QueryBuilder.StringOrder.CASE_SENSITIVE)
            .orderDesc(EntryEntity_.id)
            .build()
            .use { it.findFirst()?.id }
    }

    /** Total persisted rows, regardless of extraction terminality. */
    fun count(): Long = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>().count()
    }

    /** Completed entries only — denominator for pattern stats and pattern-empty-state gating. */
    fun countCompleted(): Long = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>()
            .query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .build()
            .use { it.count() }
    }

    /** Most-recent completed entries, newest first. [limit] is a guard, not pagination. */
    fun listCompleted(limit: Int = 100): List<EntryEntity> = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>()
            .query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .orderDesc(EntryEntity_.timestampEpochMs)
            .build()
            .use { it.find(0, limit.toLong()) }
    }

    /** Completed rows whose 3-lens extraction receipts have not been populated yet. */
    fun listCompletedMissingLensReceipts(limit: Int = 100): List<EntryEntity> = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>()
            .query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .filter { it.lensReceiptsJsonOrEmpty == "[]" }
            .orderDesc(EntryEntity_.timestampEpochMs)
            .build()
            .use { it.find().take(limit) }
    }

    /** Single most-recent completed entry, or `null` when none exist. */
    fun lastCompleted(): EntryEntity? = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>()
            .query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .orderDesc(EntryEntity_.timestampEpochMs)
            .build()
            .use { it.find(0, 1).firstOrNull() }
    }

    /** Single earliest completed entry, or `null` when none exist. Feeds the days-since-first stat. */
    fun firstCompleted(): EntryEntity? = boxStore.callClosingThreadResources {
        boxStore.boxFor<EntryEntity>()
            .query()
            .equal(
                EntryEntity_.extractionStatus,
                ExtractionStatus.COMPLETED.name,
                QueryBuilder.StringOrder.CASE_SENSITIVE,
            )
            .order(EntryEntity_.timestampEpochMs)
            .build()
            .use { it.find(0, 1).firstOrNull() }
    }

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

    /**
     * Land the persona follow-up on a still-in-flight entry. The voice path persists the entry on
     * the call-1 transcription — before call-2 has produced the follow-up — so the follow-up
     * arrives separately and is written here once call-2 terminal lands. Blank input is a no-op.
     *
     * This also patches terminal rows: background extraction and call-2 share the engine, so
     * extraction can complete first and the follow-up can still be the latest valid foreground
     * result.
     */
    fun attachFollowUp(entryId: Long, followUpText: String) {
        val trimmed = followUpText.trimEnd().takeIf(String::isNotBlank) ?: return
        boxStore.runInTx {
            val box = boxStore.boxFor<EntryEntity>()
            val entry = box.get(entryId)
                ?: throw EntryPersistenceException("No entry row id=$entryId to attach follow-up")
            entry.followUpText = trimmed
            box.put(entry)
        }
    }

    private fun uniqueMarkdownFilename(box: io.objectbox.Box<EntryEntity>, entry: EntryEntity): String {
        val baseName = EntryFilename.buildFilename(entry.timestampEpochMs, entry.entryText)
        // Hoist the blank-row scan once: blank rows derive their filename via
        // `EntryMarkdownRenderer.filenameFor`, and the set is invariant across the suffix loop.
        val blankRowDerivedNames = box.query()
            .equal(EntryEntity_.markdownFilename, "", QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()
            .use { query -> query.find().mapTo(HashSet()) { EntryMarkdownRenderer.filenameFor(it) } }
        if (!markdownFilenameExists(box, baseName, blankRowDerivedNames)) return baseName
        val stem = baseName.removeSuffix(".md")
        var suffix = 2
        while (suffix <= MAX_FILENAME_SUFFIX) {
            val candidate = "$stem-$suffix.md"
            if (!markdownFilenameExists(box, candidate, blankRowDerivedNames)) return candidate
            suffix++
        }
        throw EntryPersistenceException(
            "uniqueMarkdownFilename exhausted $MAX_FILENAME_SUFFIX suffixes for stem=$stem — refusing to loop",
        )
    }

    private fun markdownFilenameExists(
        box: io.objectbox.Box<EntryEntity>,
        filename: String,
        blankRowDerivedNames: Set<String>,
    ): Boolean {
        val storedMatch = box.query()
            .equal(EntryEntity_.markdownFilename, filename, QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()
            .use { it.count() > 0 }
        return storedMatch || filename in blankRowDerivedNames
    }

    private fun applyResolved(entry: EntryEntity, resolved: ResolvedExtraction, templateLabel: TemplateLabel?) {
        entry.templateLabel = templateLabel
        entry.vocabularyWord = stringField(resolved, KEY_VOCABULARY)?.trim()?.lowercase(Locale.ROOT)
        entry.recurrenceLink = recurrenceField(resolved)
        entry.statedCommitmentJson = commitmentJson(resolved)
        entry.confidenceJson = confidenceJson(resolved)
    }

    private fun attachTags(entry: EntryEntity, resolved: ResolvedExtraction) {
        val resolvedTags = resolved.fields[KEY_TAGS]
            ?.takeIf { it.verdict in PROMOTABLE_VERDICTS }
            ?.value
        val names = (resolvedTags as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }
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

    private fun recurrenceField(resolved: ResolvedExtraction): String? = stringField(resolved, KEY_RECURRENCE)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(PATTERN_ID_REGEX) }

    private fun commitmentJson(resolved: ResolvedExtraction): String? {
        val map = resolved.fields[KEY_COMMITMENT]?.value as? Map<*, *>
        return map?.let {
            JSONObject(it.mapKeys { entry -> entry.key.toString() }).apply {
                if (has(KEY_TOPIC_OR_PERSON)) {
                    val normalized = normalizeCommitmentTopic(optString(KEY_TOPIC_OR_PERSON))
                    put(KEY_TOPIC_OR_PERSON, normalized ?: JSONObject.NULL)
                }
            }.toString()
        }
    }

    private fun confidenceJson(resolved: ResolvedExtraction): String {
        val payload = JSONObject()
        resolved.fields.forEach { (key, field) -> payload.put(key, field.verdict.name) }
        return payload.toString()
    }

    private companion object {
        private const val MAX_FILENAME_SUFFIX = 1000
        private const val KEY_TAGS = "tags"
        private const val KEY_VOCABULARY = "vocabulary"
        private const val KEY_RECURRENCE = "recurrence_link"
        private const val KEY_COMMITMENT = "stated_commitment"
        private const val KEY_TOPIC_OR_PERSON = "topic_or_person"
        private val PATTERN_ID_REGEX = Regex("[0-9a-f]{64}")
        private val NON_TERMINAL_STATUSES = setOf(ExtractionStatus.PENDING, ExtractionStatus.RUNNING)
        private val NON_TERMINAL_STATUS_NAMES: Array<String> =
            NON_TERMINAL_STATUSES.map(ExtractionStatus::name).toTypedArray()
        private val PROMOTABLE_VERDICTS = setOf(
            ConfidenceVerdict.CANONICAL,
            ConfidenceVerdict.CANONICAL_WITH_CONFLICT,
        )
    }
}

/** Failure while mutating entry persistence state. */
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

// `internal` so `buildEmbeddingText` reuses the one canonical `{ text, evidence, fields[] }`
// decoder instead of duplicating the JSON shape — divergence here is a silent embedding bug.
internal fun decodeObservations(json: String): List<EntryObservation> {
    val raw = json.takeIf { it.isNotBlank() } ?: return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull()
    // `appendObservation` rewrites this field — if we cannot read the existing list, the next
    // write would silently overwrite real persisted observations. Surface it then fall back to
    // empty so the rewrite path can still make progress.
    return when (array) {
        null -> {
            android.util.Log.w("VestigeEntryStore", "malformed entryObservationsJson (len=${raw.length})")
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
