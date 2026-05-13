package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import io.objectbox.BoxStore
import java.io.File
import java.security.MessageDigest

/**
 * Debug-only fixture seeder. Lets the dev surface verify the Stories 3.9 / 3.10 pattern UI with
 * real cards on a device before Phase 4's capture UI ships. Idempotent — re-running clears the
 * box first so the dev gets a fresh, well-formed corpus every time.
 *
 * Not wired into any release path; the call site in `PhaseOneShell` is `FLAG_DEBUGGABLE`-gated.
 */
object DebugPatternSeeder {

    private const val ENTRY_COUNT = 12

    private data class SeedPattern(
        val signature: String,
        val title: String,
        val templateLabel: String,
        val callout: String,
        val supporting: List<EntryEntity>,
    )

    @Suppress("MagicNumber") // Fixture timestamps + corpus shape are deliberately concrete.
    fun seed(filesDir: File, boxStore: BoxStore, patternStore: PatternStore) {
        val markdownStore = MarkdownEntryStore(filesDir)
        boxStore.runInTx {
            markdownStore.listAll().forEach(File::delete)
            boxStore.boxFor(EntryEntity::class.java).removeAll()
            boxStore.boxFor(PatternEntity::class.java).removeAll()

            val entryTexts = listOf(
                "crashed after standup, wired until 2am",
                "tuesday meeting again, same concrete shoes",
                "wrote that doc in one sitting, surprising",
                "wired until 2am, can't tell if good or bad",
                "another tuesday, another aftermath",
                "shipped the thing, immediate crash",
                "decided to rewrite the migration, third time this week",
                "rewrote it again, this version is the one",
                "tuesday standup landed harder than expected",
                "audit cycle hit; reviewed everything twice",
                "concrete shoes on the morning standup",
                "crashed at 3pm, no warning, just gone",
            )
            val baseMs = System.currentTimeMillis() - DAY_MS * ENTRY_COUNT
            val entries = entryTexts.mapIndexed { idx, text ->
                EntryEntity(
                    markdownFilename = "debug-seed-$idx.md",
                    entryText = text,
                    timestampEpochMs = baseMs + idx * DAY_MS,
                    extractionStatus = ExtractionStatus.COMPLETED,
                ).also {
                    markdownStore.write(it)
                    boxStore.boxFor(EntryEntity::class.java).put(it)
                }
            }

            // Two ACTIVE patterns wired to disjoint entry slices so the list has multiple cards
            // and the detail screen has visibly-different source lists.
            seedPattern(
                patternStore,
                SeedPattern(
                    signature = "tuesday-meeting-aftermath",
                    title = "Tuesday Meetings",
                    templateLabel = "Aftermath",
                    callout = "Fourth entry mentions Tuesday meetings. State before: cruising. After: crashed.",
                    supporting = listOf(entries[1], entries[4], entries[8], entries[10]),
                ),
            )
            seedPattern(
                patternStore,
                SeedPattern(
                    signature = "decision-spiral-migrations",
                    title = "Migration Rewrites",
                    templateLabel = "Decision spiral",
                    callout = "Three decisions to rewrite the migration in one week. " +
                        "Pattern: rewriting beats committing.",
                    supporting = listOf(entries[6], entries[7], entries[2]),
                ),
            )
        }
    }

    private fun seedPattern(patternStore: PatternStore, fixture: SeedPattern) {
        val signature = fixture.signature
        val title = fixture.title
        val templateLabel = fixture.templateLabel
        val callout = fixture.callout
        val supporting = fixture.supporting
        val now = System.currentTimeMillis()
        val patternId = sha256Hex(signature)
        val firstSeen = supporting.minOf { it.timestampEpochMs }
        val lastSeen = supporting.maxOf { it.timestampEpochMs }
        val entity = PatternEntity(
            patternId = patternId,
            kind = PatternKind.TEMPLATE_RECURRENCE,
            signatureJson = """{"signature":"$signature"}""",
            title = title,
            templateLabel = templateLabel,
            firstSeenTimestamp = firstSeen,
            lastSeenTimestamp = lastSeen,
            state = PatternState.ACTIVE,
            stateChangedTimestamp = now,
            latestCalloutText = callout,
        )
        patternStore.put(entity)
        val saved = patternStore.findByPatternId(patternId)
            ?: error("debug seed: pattern $patternId did not persist")
        saved.supportingEntries.addAll(supporting)
        patternStore.put(saved)
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000
}
