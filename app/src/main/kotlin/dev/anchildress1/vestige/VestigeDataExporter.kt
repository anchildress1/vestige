package dev.anchildress1.vestige

import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.storage.callClosingThreadResources
import dev.anchildress1.vestige.storage.lensReceiptsJsonOrEmpty
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import io.objectbox.BoxStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes the complete user-data export: readable markdown plus a structured ObjectBox snapshot. */
@Suppress("TooManyFunctions")
internal class VestigeDataExporter(
    private val boxStore: BoxStore,
    private val markdownStore: MarkdownEntryStore,
    private val onboardingPrefs: OnboardingPrefs,
) {

    fun writeTo(out: OutputStream) {
        val content = boxStore.callClosingThreadResources {
            boxStore.callInReadTx {
                val markdownFiles = markdownStore.listAll()
                ExportContent(buildSnapshot(markdownFiles), markdownFiles)
            }
        }
        // Assemble the whole archive in a temp file first: a read or file failure must not
        // leave a truncated zip at the user's export target.
        val staged = File.createTempFile("vestige-export", ".zip")
        try {
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                zip.putTextEntry(SNAPSHOT_ENTRY, content.snapshot.toString(JSON_INDENT))
                content.markdownFiles.forEach { zip.putFileEntry(it) }
            }
            staged.inputStream().use { it.copyTo(out) }
        } finally {
            staged.delete()
        }
    }

    private fun buildSnapshot(markdownFiles: List<File>): JSONObject = JSONObject()
        .put("format", EXPORT_FORMAT)
        .put("schema_version", EXPORT_SCHEMA_VERSION)
        .put("exported_at", Instant.now().toString())
        .put("settings", settingsJson())
        .put("entries", entriesJson())
        .put("patterns", patternsJson())
        .put("tags", tagsJson())
        .put("callout_cooldowns", calloutCooldownsJson())
        .put("markdown_files", markdownFilesJson(markdownFiles))

    private fun settingsJson(): JSONObject = JSONObject()
        .put("onboarding_complete", onboardingPrefs.isComplete)
        .put("default_persona", onboardingPrefs.defaultPersona.name)
        .put("current_step", onboardingPrefs.currentStep.name)

    private fun entriesJson(): JSONArray = boxStore.boxFor(EntryEntity::class.java).all
        .sortedBy { it.id }
        .fold(JSONArray()) { arr, entry ->
            arr.put(
                JSONObject()
                    .put("objectbox_id", entry.id)
                    .put("markdown_filename", entry.markdownFilename)
                    .put("entry_text", entry.entryText)
                    .putNullable("follow_up_text", entry.followUpText)
                    .put("persona", entry.persona.name)
                    .put("timestamp_epoch_ms", entry.timestampEpochMs)
                    .putNullable("template_label", entry.templateLabel?.serial)
                    .putNullable("energy_descriptor", entry.energyDescriptor)
                    .putNullable("recurrence_link", entry.recurrenceLink)
                    .putNullable("stated_commitment_json", entry.statedCommitmentJson)
                    .put("entry_observations_json", entry.entryObservationsJson)
                    .put("lens_receipts_json", entry.lensReceiptsJsonOrEmpty)
                    .put("confidence_json", entry.confidenceJson)
                    .put("extraction_status", entry.extractionStatus.name)
                    .put("duration_ms", entry.durationMs)
                    .put("attempt_count", entry.attemptCount)
                    .putNullable("last_error", entry.lastError)
                    .put("vector_schema_version", entry.vectorSchemaVersion)
                    .putNullable("vector", entry.vector?.toJsonArray())
                    .put("tags", entry.tags.map { it.name }.sorted().toJsonArray()),
            )
        }

    private fun patternsJson(): JSONArray = boxStore.boxFor(PatternEntity::class.java).all
        .sortedBy { it.id }
        .fold(JSONArray()) { arr, pattern ->
            arr.put(
                JSONObject()
                    .put("objectbox_id", pattern.id)
                    .put("pattern_id", pattern.patternId)
                    .put("kind", pattern.kind.serial)
                    .put("signature_json", pattern.signatureJson)
                    .put("title", pattern.title)
                    .putNullable("template_label", pattern.templateLabel)
                    .put("first_seen_timestamp", pattern.firstSeenTimestamp)
                    .put("last_seen_timestamp", pattern.lastSeenTimestamp)
                    .put("state", pattern.state.serial)
                    .putNullable("snoozed_until", pattern.snoozedUntil)
                    .put("state_changed_timestamp", pattern.stateChangedTimestamp)
                    .put("latest_callout_text", pattern.latestCalloutText)
                    .put("supporting_entry_ids", pattern.supportingEntries.map { it.id }.sorted().toJsonArray())
                    .put(
                        "supporting_entry_markdown_filenames",
                        pattern.supportingEntries.map { it.markdownFilename }.sorted().toJsonArray(),
                    ),
            )
        }

    private fun tagsJson(): JSONArray = boxStore.boxFor(TagEntity::class.java).all
        .sortedBy { it.name }
        .fold(JSONArray()) { arr, tag ->
            arr.put(
                JSONObject()
                    .put("objectbox_id", tag.id)
                    .put("name", tag.name)
                    .put("entry_count", tag.entryCount),
            )
        }

    private fun calloutCooldownsJson(): JSONArray = boxStore.boxFor(CalloutCooldownEntity::class.java).all
        .sortedBy { it.id }
        .fold(JSONArray()) { arr, cooldown ->
            arr.put(
                JSONObject()
                    .put("objectbox_id", cooldown.id)
                    .putNullable("last_callout_entry_id", cooldown.lastCalloutEntryId)
                    .putNullable("last_callout_timestamp", cooldown.lastCalloutTimestamp)
                    .put("remaining_suppression", cooldown.remainingSuppression)
                    .putNullable("pending_callout_entry_id", cooldown.pendingCalloutEntryId),
            )
        }

    private fun markdownFilesJson(markdownFiles: List<File>): JSONArray = markdownFiles
        .map { it.name }
        .sorted()
        .toJsonArray()

    private fun ZipOutputStream.putTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        try {
            write(value.toByteArray(Charsets.UTF_8))
        } finally {
            closeEntrySafely()
        }
    }

    private fun ZipOutputStream.putFileEntry(file: File) {
        putNextEntry(ZipEntry("$MARKDOWN_EXPORT_DIR/${file.name}"))
        try {
            file.inputStream().use { it.copyTo(this) }
        } finally {
            closeEntrySafely()
        }
    }

    private fun ZipOutputStream.closeEntrySafely() {
        runCatching { closeEntry() }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun FloatArray.toJsonArray(): JSONArray = fold(JSONArray()) { arr, value -> arr.put(value.toDouble()) }

    private fun Iterable<Any>.toJsonArray(): JSONArray = fold(JSONArray()) { arr, value -> arr.put(value) }

    private class ExportContent(val snapshot: JSONObject, val markdownFiles: List<File>)

    companion object {
        const val SNAPSHOT_ENTRY = "vestige-export.json"
        const val MARKDOWN_EXPORT_DIR = "entries"
        private const val EXPORT_FORMAT = "vestige.full-export"
        private const val EXPORT_SCHEMA_VERSION = 1
        private const val JSON_INDENT = 2
    }
}
