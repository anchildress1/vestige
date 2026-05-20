package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.TagEntity
import io.objectbox.BoxStore
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Debug-only fixture seeder. Lets the dev verify the pattern UI with real cards on a device.
 * Idempotent — re-running clears the box first so the dev gets a fresh, well-formed corpus.
 */
object DebugPatternSeeder {

    private data class SeedEntry(val text: String, val timestamp: Instant, val durationMs: Long)

    private data class SeedPattern(
        val signature: String,
        val title: String,
        val templateLabel: String,
        val callout: String,
        val supporting: List<EntryEntity>,
    )

    @Suppress("MagicNumber") // Fixture timestamps + corpus shape are deliberately concrete.
    fun seed(filesDir: File, boxStore: BoxStore, patternStore: PatternStore) {
        File(filesDir, "entries").deleteRecursively()
        boxStore.runInTx {
            boxStore.boxFor(EntryEntity::class.java).removeAll()
            boxStore.boxFor(PatternEntity::class.java).removeAll()
            boxStore.boxFor(TagEntity::class.java).removeAll()
            boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()

            val entries = seedEntries().mapIndexed { idx, seed ->
                EntryEntity(
                    markdownFilename = "debug-seed-$idx.md",
                    entryText = seed.text,
                    timestampEpochMs = seed.timestamp.toEpochMilli(),
                    durationMs = seed.durationMs,
                    extractionStatus = ExtractionStatus.COMPLETED,
                ).also { boxStore.boxFor(EntryEntity::class.java).put(it) }
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

    @Suppress("MagicNumber") // Fixture timestamps + durations are deliberately concrete.
    private fun seedEntries() = listOf(
        SeedEntry("crashed after standup, wired until 2am", Instant.parse("2026-05-07T18:42:00Z"), 18_000L),
        SeedEntry("tuesday meeting again, same concrete shoes", Instant.parse("2026-05-05T14:10:00Z"), 22_000L),
        SeedEntry("wrote that doc in one sitting, surprising", Instant.parse("2026-05-08T10:24:00Z"), 15_000L),
        SeedEntry("wired until 2am, can't tell if good or bad", Instant.parse("2026-05-09T06:13:00Z"), 27_000L),
        SeedEntry("another tuesday, another aftermath", Instant.parse("2026-05-12T15:30:00Z"), 12_000L),
        SeedEntry("shipped the thing, immediate crash", Instant.parse("2026-05-13T21:08:00Z"), 20_000L),
        SeedEntry(
            "decided to rewrite the migration, third time this week",
            Instant.parse("2026-05-14T16:45:00Z"),
            28_000L,
        ),
        SeedEntry("rewrote it again, this version is the one", Instant.parse("2026-05-16T11:05:00Z"), 19_000L),
        SeedEntry("tuesday standup landed harder than expected", Instant.parse("2026-05-19T13:55:00Z"), 24_000L),
        SeedEntry("audit cycle hit; reviewed everything twice", Instant.parse("2026-05-18T19:22:00Z"), 16_000L),
        SeedEntry("concrete shoes on the morning standup", Instant.parse("2026-05-19T08:40:00Z"), 11_000L),
        SeedEntry("crashed at 3pm, no warning, just gone", Instant.parse("2026-05-20T19:00:00Z"), 25_000L),
    )

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
}
