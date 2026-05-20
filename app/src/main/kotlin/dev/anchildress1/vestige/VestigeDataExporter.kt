package dev.anchildress1.vestige

import android.util.Log
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.EntryMarkdownRenderer
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.storage.callClosingThreadResources
import dev.anchildress1.vestige.storage.lensReceiptsJsonOrEmpty
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import io.objectbox.BoxStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes the complete user-data export: readable markdown plus a structured ObjectBox snapshot. */
@Suppress("TooManyFunctions")
internal class VestigeDataExporter(private val boxStore: BoxStore, private val onboardingPrefs: OnboardingPrefs) {

    fun writeTo(out: OutputStream) {
        // One ObjectBox snapshot. Markdown is rendered from these rows for export only.
        val content = boxStore.callClosingThreadResources {
            boxStore.callInReadTx {
                val entries = sortedEntries()
                ExportContent(
                    snapshot = buildSnapshot(entries),
                    markdownEntries = entries.map { entry ->
                        MarkdownExportEntry(
                            filename = EntryMarkdownRenderer.filenameFor(entry),
                            body = EntryMarkdownRenderer.render(entry),
                        )
                    },
                )
            }
        }
        // Staging avoids partial archives in our own temp area, but an arbitrary OutputStream
        // cannot provide an atomic destination guarantee. A mid-copy failure can still leave the
        // caller's sink truncated; fixing that requires a file/Uri contract, not just this helper.
        // POSIX 0600: the staged archive carries the full user-data export — owner-only on any
        // shared filesystem (no-op on Android's per-app sandbox, real protection on shared JVM tmp).
        val staged = Files.createTempFile(
            "vestige-export",
            ".zip",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        ).toFile()
        try {
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                zip.putTextEntry(SNAPSHOT_ENTRY, content.snapshot.toString(JSON_INDENT))
                content.markdownEntries.forEach { zip.putTextEntry("$MARKDOWN_EXPORT_DIR/${it.filename}", it.body) }
            }
            staged.inputStream().use { it.copyTo(out) }
        } finally {
            if (!staged.delete() && staged.exists()) {
                staged.deleteOnExit()
            }
        }
    }

    private fun buildSnapshot(entries: List<EntryEntity>): JSONObject = JSONObject()
        .put("format", EXPORT_FORMAT)
        .put("schema_version", EXPORT_SCHEMA_VERSION)
        .put("exported_at", Instant.now().toString())
        .put("settings", settingsJson())
        .put("entries", entriesJson(entries))
        .put("patterns", patternsJson())
        .put("tags", tagsJson())
        .put("callout_cooldowns", calloutCooldownsJson())
        .put("markdown_files", markdownFilesJson(entries))

    private fun settingsJson(): JSONObject = JSONObject()
        .put("onboarding_complete", onboardingPrefs.isComplete)
        .put("default_persona", onboardingPrefs.defaultPersona.name)
        .put("current_step", onboardingPrefs.currentStep.name)

    private fun sortedEntries(): List<EntryEntity> = boxStore.boxFor(EntryEntity::class.java).all.sortedBy { it.id }

    private fun entriesJson(entries: List<EntryEntity>): JSONArray = entries.fold(JSONArray()) { arr, entry ->
        arr.put(
            JSONObject()
                .put("entry_id", stableEntryId(entry))
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
                    .put(
                        "supporting_entry_ids",
                        pattern.supportingEntries.map(::stableEntryId).sorted().toJsonArray(),
                    )
                    .put(
                        "supporting_entry_objectbox_ids",
                        pattern.supportingEntries.map { it.id }.sorted().toJsonArray(),
                    )
                    .put(
                        "supporting_entry_markdown_filenames",
                        pattern.supportingEntries.map { EntryMarkdownRenderer.filenameFor(it) }.sorted().toJsonArray(),
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
                    .put("pattern_id", cooldown.patternId)
                    .putNullable("last_callout_entry_id", cooldown.lastCalloutEntryId)
                    .putNullable("last_callout_timestamp", cooldown.lastCalloutTimestamp)
                    .put("remaining_suppression", cooldown.remainingSuppression)
                    .putNullable("pending_callout_entry_id", cooldown.pendingCalloutEntryId),
            )
        }

    private fun markdownFilesJson(entries: List<EntryEntity>): JSONArray = entries
        .map { EntryMarkdownRenderer.filenameFor(it) }
        .sorted()
        .toJsonArray()

    private fun stableEntryId(entry: EntryEntity): String = EntryMarkdownRenderer.filenameFor(entry).removeSuffix(".md")

    private fun ZipOutputStream.putTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        try {
            write(value.toByteArray(Charsets.UTF_8))
        } finally {
            closeEntrySafely()
        }
    }

    private fun ZipOutputStream.closeEntrySafely() {
        try {
            closeEntry()
        } catch (e: IOException) {
            Log.e("VestigeDataExporter", "ZipEntry close failed — archive may be corrupt", e)
            throw e
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun FloatArray.toJsonArray(): JSONArray = fold(JSONArray()) { arr, value -> arr.put(value.toDouble()) }

    private fun Iterable<Any>.toJsonArray(): JSONArray = fold(JSONArray()) { arr, value -> arr.put(value) }

    private data class MarkdownExportEntry(val filename: String, val body: String)

    private class ExportContent(val snapshot: JSONObject, val markdownEntries: List<MarkdownExportEntry>)

    companion object {
        const val SNAPSHOT_ENTRY = "vestige-export.json"
        const val MARKDOWN_EXPORT_DIR = "entries"
        private const val EXPORT_FORMAT = "vestige.full-export"
        private const val EXPORT_SCHEMA_VERSION = 2
        private const val JSON_INDENT = 2
    }
}
