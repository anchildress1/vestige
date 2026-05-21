package dev.anchildress1.vestige.debug

import android.util.Log
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.PatternKind
import dev.anchildress1.vestige.model.PatternState
import dev.anchildress1.vestige.model.TemplateLabel
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
        val legacyEntriesDir = File(filesDir, "entries")
        if (legacyEntriesDir.exists() && !legacyEntriesDir.deleteRecursively()) {
            Log.w(TAG, "Failed to clear legacy entries dir before seed — leftover markdown may confuse demo state")
        }
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
                    templateLabel = TemplateLabel.AFTERMATH.serial,
                    callout = "Fourth entry mentions Tuesday meetings. State before: cruising. After: crashed.",
                    supporting = listOf(entries[1], entries[4], entries[8], entries[10]),
                ),
            )
            seedPattern(
                patternStore,
                SeedPattern(
                    signature = "decision-spiral-migrations",
                    title = "Migration Rewrites",
                    templateLabel = TemplateLabel.DECISION_SPIRAL.serial,
                    callout = "Three decisions to rewrite the migration in one week. " +
                        "Pattern: rewriting beats committing.",
                    supporting = listOf(entries[6], entries[7], entries[2]),
                ),
            )

            seedVocabDrift(boxStore, patternStore, VOCAB_BASE_TIMESTAMP.toEpochMilli())
        }
    }

    @Suppress("MagicNumber", "LongMethod") // Fixture timestamps + corpus shape are deliberately concrete.
    private fun seedEntries() = listOf(
        SeedEntry(
            "I was completely fine going into the standup but crashed hard within about twenty minutes. " +
                "Couldn't get back to the doc for the rest of the day. Then somehow wired until 2am. " +
                "That's the whole cycle in one day.",
            Instant.parse("2026-05-07T18:42:00Z"),
            18_000L,
        ),
        SeedEntry(
            "Every Tuesday meeting does the same thing to me. I go in okay and come out with " +
                "what I can only describe as concrete shoes. Everything feels heavier and slower " +
                "for the rest of the afternoon, and I never seem to account for it.",
            Instant.parse("2026-05-05T14:10:00Z"),
            22_000L,
        ),
        SeedEntry(
            "Actually got the whole doc done in one sitting today and I didn't expect that at all. " +
                "I kept waiting for the stall to kick in but it never did. " +
                "Weird but I'll take it. Not sure what was different.",
            Instant.parse("2026-05-08T10:24:00Z"),
            15_000L,
        ),
        SeedEntry(
            "Still awake at 2am, not anxious exactly, just can't seem to land. " +
                "Brain keeps spinning on things that genuinely don't need to be thought about right now. " +
                "I don't even know if this is productive or just restless. Hard to tell the difference tonight.",
            Instant.parse("2026-05-09T06:13:00Z"),
            27_000L,
        ),
        SeedEntry(
            "Another Tuesday, same pattern as always. The meeting ends and I just kind of decompress " +
                "for two hours whether I want to or not. Doesn't matter how much coffee I had beforehand. " +
                "Body just decides it's done and that's that.",
            Instant.parse("2026-05-12T15:30:00Z"),
            12_000L,
        ),
        SeedEntry(
            "Shipped the feature this afternoon and then immediately hit a wall. " +
                "Couldn't start anything else for like two hours, just sat there staring at the next ticket. " +
                "I don't know why completing things does this to me but it happens every single time.",
            Instant.parse("2026-05-13T21:08:00Z"),
            20_000L,
        ),
        SeedEntry(
            "Decided to rewrite the migration again tonight. This is the third time this week I've " +
                "restarted it with completely different reasoning each time. " +
                "I keep convincing myself the new approach is obviously better. " +
                "I think I might just be spinning and calling it progress.",
            Instant.parse("2026-05-14T16:45:00Z"),
            28_000L,
        ),
        SeedEntry(
            "Rewrote the whole thing again and this time it actually feels right. " +
                "But I said that last time too so I don't fully trust myself on this. " +
                "Different structure at least. I'm committing to this version even if it costs me another day.",
            Instant.parse("2026-05-16T11:05:00Z"),
            19_000L,
        ),
        SeedEntry(
            "Tuesday standup hit harder than expected today. Nothing dramatic was said, " +
                "just the usual check-in, but something about the framing left me completely flat afterward. " +
                "Couldn't do anything useful for the rest of the morning. " +
                "Ate lunch just to have something to do.",
            Instant.parse("2026-05-19T13:55:00Z"),
            24_000L,
        ),
        SeedEntry(
            "Audit cycle started today and I reviewed everything twice before sending anything. " +
                "That kind of second-guessing slows everything down to a crawl. " +
                "Took me twice as long as it should have and I'm still not confident it was right. " +
                "That's the worst combination.",
            Instant.parse("2026-05-18T19:22:00Z"),
            16_000L,
        ),
        SeedEntry(
            "Morning standup left me with that concrete shoes feeling again, same as last week. " +
                "Like someone quietly added weight to everything the moment the call ended. " +
                "Tried to get back into the work right away but I was moving in slow motion for the whole morning.",
            Instant.parse("2026-05-19T08:40:00Z"),
            11_000L,
        ),
        SeedEntry(
            "Crashed at 3pm completely out of nowhere. No warning, no buildup, just suddenly couldn't think. " +
                "I was functional an hour earlier and then just gone. " +
                "Had to give up on the rest of the afternoon. " +
                "I don't know what happened.",
            Instant.parse("2026-05-20T19:00:00Z"),
            25_000L,
        ),
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
            latestCalloutText = "\"tired\" spans ${tiredEntries.size} entries in three distinct framings.",
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

    private const val TAG = "VestigeDebugPatternSeeder"

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
        "I hit the wall hard today — exhausted again in a way that felt different from just being tired. " +
            "Every limb gave up at once somewhere around 2pm. Not dramatic, just suddenly nothing left.",
        "Drained to the bone by mid-morning and I don't even know why. Eyes won't focus on anything. " +
            "I tried to push through it and just made everything worse. Had to stop completely.",
        "Wiped out before noon. There was no energy left for anything, not even the stuff I wanted to do. " +
            "I kept telling myself five more minutes and nothing happened.",
        "Running on empty and I've been running on empty for days. Fumes only at this point. " +
            "I got the basics done but barely. There was nothing left at the end of it.",
        "Completely depleted today. My body feels heavier than it did yesterday and yesterday already felt heavy. " +
            "I sat down to start and stared at it for twenty minutes before giving up.",
        "Drained. Just drained. Not tired, not sleepy, not worn out — drained. " +
            "Like something pulled the plug around noon and I spent the rest of the day waiting for it to come back.",
        "Exhausted by 10am and that's a new floor for me. I've been running behind my own capacity for weeks " +
            "but this is the first time I ran out before lunch. That felt like a line being crossed.",
        "Wiped and it's the kind that ignores caffeine. Had two coffees before noon and felt nothing from either. " +
            "Body decided to stop being functional before I had any say in it.",
    )

    private val VOCAB_DRIFT_FOG: List<String> = listOf(
        "Sluggish all day with that brain fog that makes everything take three times longer than it should. " +
            "I kept losing my place in the middle of sentences. It was back, same as before.",
        "Foggy in a way I can't push through. Couldn't string two sentences together without losing the thread. " +
            "Sat with the document open for an hour and wrote maybe thirty usable words.",
        "Burnt out and my attention just skating across everything without landing anywhere. " +
            "I'd start reading something and be three paragraphs in with zero retention. Tried resetting four times.",
        "Brain fog today. The cursor was blinking faster than I could think, which is how I know it's bad. " +
            "I'm slower than the default blink rate. Ended up closing everything and going for a walk.",
        "Sluggish in a way that made every task take twice as long. Simple things felt hard. " +
            "I kept re-reading the same paragraph to figure out what I was supposed to do next.",
        "Foggy and slow all day, mind moving through something that felt like molasses. " +
            "Not in a dramatic way. Just everything requiring more effort than it should. I got through it but barely.",
        "Burnt out and the screen looked blurry even though my eyes were fine. It was coming from inside. " +
            "That's my signal that I need to stop but I kept going anyway. Bad call.",
        "Brain fog. Started three separate sentences and finished none of them. I know what I'm trying to say " +
            "but the path from that to words just isn't there right now. Closing the doc.",
    )

    private val VOCAB_DRIFT_WIRED_TIRED: List<String> = listOf(
        "Wired-tired again tonight and I don't know which is worse. Body wants sleep, brain just refuses. " +
            "Lying down doesn't help. Not anxious about anything specific, just running at the wrong frequency.",
        "Anxious-tired is the only way I can describe what this is. Lying down doesn't count as rest " +
            "when my brain is still processing everything. Slept but woke up like I hadn't slept at all.",
        "Can't sleep but I'm genuinely exhausted. The static won't quit even when I'm completely flat. " +
            "I've been horizontal for an hour and nothing is happening. Brain won't stop, body gave up.",
        "Amped but exhausted and my body and brain are completely disagreeing about what state I'm in. " +
            "Body says stop, brain says go. They've been sending opposite signals since about 8pm.",
        "Wired-tired again and it's the third night in a row. I keep expecting it to flip into actual sleep " +
            "but it doesn't. I just lie there staring at the ceiling processing nothing useful.",
        "Anxious-tired with eyes closed and chest racing even though nothing is happening. " +
            "No reason for it. I just can't get below a certain level of activation no matter how tired I am.",
        "Can't sleep, can't focus, both tanks empty at the same time. I don't know how that works " +
            "but here I am at 1am, fully depleted and fully awake. Completely contradictory.",
    )
}
