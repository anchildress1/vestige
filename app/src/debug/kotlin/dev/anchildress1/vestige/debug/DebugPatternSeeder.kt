package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EmbeddingClustering
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.PatternStore
import dev.anchildress1.vestige.storage.TagEntity
import dev.anchildress1.vestige.storage.VocabClusterLabeler
import dev.anchildress1.vestige.storage.VocabClustersCodec
import dev.anchildress1.vestige.storage.vocabPatternIdentity
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

            seedVocabDrift(boxStore, patternStore, VOCAB_BASE_TIMESTAMP.toEpochMilli())
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

    /**
     * 23 entries describing the same underlying state ("tired") across three vocabulary
     * clusters. Synthetic embeddings put each cluster on its own axis so the on-device clustering
     * pipeline produces three groups deterministically. The pattern row is pre-stamped with the
     * encoded clusters so the Vocab Drift screen renders before the next save runs the
     * orchestrator's second pass.
     */
    @Suppress("MagicNumber") // Fixture cluster sizes + vector centroids are deliberately fixed.
    private fun seedVocabDrift(
        boxStore: BoxStore,
        patternStore: PatternStore,
        baseMs: Long,
    ) {
        val tiredEntries = persistVocabEntries(
            boxStore = boxStore,
            baseMs = baseMs,
            cluster0 = VOCAB_DRIFT_EXHAUSTION,
            cluster1 = VOCAB_DRIFT_FOG,
            cluster2 = VOCAB_DRIFT_WIRED_TIRED,
        )

        // Use the detector's canonical signature so a real detection pass updates the seeded row
        // instead of inserting a duplicate with a content-addressable mismatch.
        val identity = vocabPatternIdentity("tired")
        val pattern = PatternEntity(
            patternId = identity.patternId,
            kind = PatternKind.VOCAB_FREQUENCY,
            signatureJson = identity.signatureJson,
            title = "Tired",
            templateLabel = null,
            firstSeenTimestamp = tiredEntries.minOf { it.timestampEpochMs },
            lastSeenTimestamp = tiredEntries.maxOf { it.timestampEpochMs },
            state = PatternState.ACTIVE,
            stateChangedTimestamp = System.currentTimeMillis(),
            latestCalloutText = "'tired' shows up across ${tiredEntries.size} entries in three distinct framings.",
        )
        patternStore.put(pattern)
        val saved = patternStore.findByPatternId(pattern.patternId)
            ?: error("debug seed: vocab-drift pattern did not persist")
        saved.supportingEntries.addAll(tiredEntries)
        // Pre-stamp so the screen renders before the next save runs the orchestrator's pass.
        val clusters = EmbeddingClustering.cluster(tiredEntries)
        check(clusters.isNotEmpty()) {
            "debug seed: vocab-drift clustering produced no clusters — synthetic vectors broke"
        }
        saved.vocabClustersJson = VocabClustersCodec.encode(
            clusters = clusters.map { VocabClusterLabeler.label(it, rootToken = "tired") },
            evidenceHash = VocabClustersCodec.evidenceHashOf(tiredEntries.map { it.id }),
        )
        patternStore.put(saved)
    }

    @Suppress("LongParameterList")
    private fun persistVocabEntries(
        boxStore: BoxStore,
        baseMs: Long,
        cluster0: List<String>,
        cluster1: List<String>,
        cluster2: List<String>,
    ): List<EntryEntity> {
        val box = boxStore.boxFor(EntryEntity::class.java)
        val results = mutableListOf<EntryEntity>()
        var indexWithinDay = 0
        val triplets = listOf(cluster0 to 0, cluster1 to 1, cluster2 to 2)
        triplets.forEach { (texts, axis) ->
            texts.forEach { text ->
                val entry = EntryEntity(
                    markdownFilename = "debug-vocab-${results.size}.md",
                    entryText = text,
                    timestampEpochMs = baseMs + indexWithinDay * VOCAB_ENTRY_STEP_MS,
                    durationMs = VOCAB_ENTRY_DURATION_MS,
                    extractionStatus = ExtractionStatus.COMPLETED,
                )
                entry.vector = syntheticVector(axis)
                box.put(entry)
                results.add(entry)
                indexWithinDay += 1
            }
        }
        return results
    }

    private fun syntheticVector(axis: Int): FloatArray {
        val v = FloatArray(VECTOR_DIMENSIONS)
        // One-hot on the cluster's axis with a tiny perturbation on a non-axis index — that
        // lets the cosine metric separate the three groups cleanly without bit-identical members.
        v[axis * VECTOR_AXIS_STRIDE] = VECTOR_AXIS_PRIMARY
        v[(axis * VECTOR_AXIS_STRIDE + 1) % VECTOR_DIMENSIONS] = VECTOR_AXIS_PERTURB
        return v
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

    // Byte-mask + `String.format` avoids `java.util.HexFormat` (API 34+); the explicit
    // `toInt() and BYTE_MASK` defeats the sign-extension bug that the naked
    // `"%02x".format(byte)` shorthand triggers on negative digest bytes.
    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and BYTE_MASK)
        }

    private val VOCAB_BASE_TIMESTAMP: Instant = Instant.parse("2026-05-01T12:00:00Z")

    /** Unsigned-byte mask for sign-safe `byte.toInt() and BYTE_MASK` hex formatting. */
    private const val BYTE_MASK: Int = 0xff

    /** Matches [dev.anchildress1.vestige.storage.EntryEntity.EMBEDDING_DIMENSIONS]. */
    private const val VECTOR_DIMENSIONS: Int = 768

    /** Spacing between cluster centroids so each axis lands distinctly in 768-d space. */
    private const val VECTOR_AXIS_STRIDE: Int = 200

    private const val VOCAB_ENTRY_STEP_MS: Long = 6L * 60 * 60 * 1000 // 6 hrs between entries
    private const val VOCAB_ENTRY_DURATION_MS: Long = 14_000L
    private const val VECTOR_AXIS_PRIMARY: Float = 1.0f
    private const val VECTOR_AXIS_PERTURB: Float = 0.01f

    private val VOCAB_DRIFT_EXHAUSTION: List<String> = listOf(
        "exhausted again, every limb gave up at once",
        "drained to the bone, eyes won't focus",
        "wiped out, no energy left for anything",
        "running on empty, fumes only",
        "depleted, body feels heavier than yesterday",
        "drained, drained, drained, can't write it any other way",
        "exhausted by 10am, that's a new floor",
        "wiped, the kind that ignores caffeine",
    )

    private val VOCAB_DRIFT_FOG: List<String> = listOf(
        "sluggish, the brain fog is back",
        "foggy, can't string two sentences together",
        "burnt out, attention skating across everything",
        "brain fog, the cursor blinks faster than I think",
        "sluggish, every task takes twice as long",
        "foggy and slow, mind moving through molasses",
        "burnt out, the screen looks blurry from inside out",
        "brain fog, started three sentences, finished none",
    )

    private val VOCAB_DRIFT_WIRED_TIRED: List<String> = listOf(
        "wired-tired, body wants sleep, brain refuses",
        "anxious-tired, lying down doesn't count as rest",
        "can't sleep, exhausted but the static won't quit",
        "amped but exhausted, my body and brain disagree",
        "wired-tired again, third night in a row",
        "anxious-tired, eyes closed, chest racing",
        "can't sleep, can't focus, both tanks empty",
    )
}
