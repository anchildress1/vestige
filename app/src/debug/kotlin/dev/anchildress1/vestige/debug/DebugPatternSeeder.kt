package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.TagEntity
import io.objectbox.BoxStore
import java.io.File
import java.security.MessageDigest

/**
 * Debug-only fixture seeder. Lets the dev verify the pattern UI with real cards on a device.
 * Idempotent — re-running clears the box first so the dev gets a fresh, well-formed corpus.
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
            boxStore.boxFor(TagEntity::class.java).removeAll()
            boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()

            data class SeedEntry(val text: String, val durationMs: Long)
            val seedEntries = listOf(
                SeedEntry("crashed after standup, wired until 2am", 18_000L),
                SeedEntry("tuesday meeting again, same concrete shoes", 22_000L),
                SeedEntry("wrote that doc in one sitting, surprising", 15_000L),
                SeedEntry("wired until 2am, can't tell if good or bad", 27_000L),
                SeedEntry("another tuesday, another aftermath", 12_000L),
                SeedEntry("shipped the thing, immediate crash", 20_000L),
                SeedEntry("decided to rewrite the migration, third time this week", 28_000L),
                SeedEntry("rewrote it again, this version is the one", 19_000L),
                SeedEntry("tuesday standup landed harder than expected", 24_000L),
                SeedEntry("audit cycle hit; reviewed everything twice", 16_000L),
                SeedEntry("concrete shoes on the morning standup", 11_000L),
                SeedEntry("crashed at 3pm, no warning, just gone", 25_000L),
            )
            val baseMs = System.currentTimeMillis() - DAY_MS * ENTRY_COUNT
            val entries = seedEntries.mapIndexed { idx, seed ->
                EntryEntity(
                    markdownFilename = "debug-seed-$idx.md",
                    entryText = seed.text,
                    timestampEpochMs = baseMs + idx * DAY_MS,
                    durationMs = seed.durationMs,
                    extractionStatus = ExtractionStatus.COMPLETED,
                ).also {
                    // put first so ObjectBox initializes the lateinit ToMany<TagEntity> field
                    // before MarkdownEntryStore.write() iterates entry.tags
                    boxStore.boxFor(EntryEntity::class.java).put(it)
                    markdownStore.write(it)
                }
            }

            // Two ACTIVE patterns wired to disjoint entry slices so the list has multiple cards
            // and the detail screen has visibly-different source lists.
            seedPattern(
                patternStore,
                SeedPattern(
                    signature = "tuesday-meeting-aftermath",
                    title = "Tuesday Meetings",
                    templateLabel = "Crashed",
                    callout = "Fourth entry mentions Tuesday meetings. State before: cruising. After: crashed.",
                    supporting = listOf(entries[1], entries[4], entries[8], entries[10]),
                ),
            )
            seedPattern(
                patternStore,
                SeedPattern(
                    signature = "decision-spiral-migrations",
                    title = "Migration Rewrites",
                    templateLabel = "Nonstop Spiral",
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
