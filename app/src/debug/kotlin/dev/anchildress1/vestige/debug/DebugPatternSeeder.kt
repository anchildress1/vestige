package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import io.objectbox.BoxStore
import java.time.Instant

object DebugPatternSeeder {

    // The full corpus is seeded — DEMO_ENTRIES + backlog narrative + vocab-drift (incl. positives).
    // Exposed so the on-device tuning harness and tests share the exact count.
    val SEED_COUNT: Int get() = corpus().size

    // Vocab-drift fixtures share one duration; the prose carries the variation, not the timing.
    private const val VOCAB_DRIFT_DURATION_MS = 14_000L

    /** One demo entry. Public so the on-device tuning harness can share this exact corpus. */
    data class SeedEntry(val text: String, val timestamp: Instant, val durationMs: Long)

    fun seed(boxStore: BoxStore) {
        boxStore.runInTx {
            boxStore.boxFor(EntryEntity::class.java).removeAll()
            boxStore.boxFor(TagEntity::class.java).removeAll()
            boxStore.boxFor(PatternEntity::class.java).removeAll()
            boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()

            corpus().sortedBy { it.timestamp }.forEachIndexed { idx, seed ->
                boxStore.boxFor(EntryEntity::class.java).put(
                    EntryEntity(
                        markdownFilename = "debug-seed-$idx.md",
                        entryText = seed.text,
                        timestampEpochMs = seed.timestamp.toEpochMilli(),
                        durationMs = seed.durationMs,
                        extractionStatus = ExtractionStatus.PENDING,
                    ),
                )
            }
        }
    }

    // [seed] sorts the merged corpus by timestamp before persisting, so the three source lists are
    // just groupings — the timeline the demo shows is the interleaved chronological order, not the
    // list order. Timestamps are hand-spread so same-archetype entries rarely land back-to-back.
    private fun corpus(): List<SeedEntry> = DEMO_ENTRIES + backlogNarrative() + vocabDriftEntries()

    /**
     * Archetype spread — aftermath ×4 (clears the ≥3 template-recurrence floor), decision-spiral ×2,
     * stalled ×2, goblin-hours ×1, tunnel-exit ×1. The stalled-archetype prose is deliberately
     * keyword-free so the run measures whether the lenses + labeler recover the archetype from
     * natural resistance/paralysis language rather than a planted phrase.
     */
    @Suppress("MagicNumber")
    val DEMO_ENTRIES: List<SeedEntry> = listOf(
        SeedEntry(
            "I was completely fine going into the standup but I crashed hard in about twenty minutes. " +
                "I couldn't get back to the doc for the rest of the day. Then I was somehow wired until 2am. " +
                "That's the whole cycle in one day.",
            Instant.parse("2026-05-02T13:55:00Z"),
            18_000L,
        ),
        SeedEntry(
            "Another Tuesday and the same pattern as always. The meeting ends and I just kind of decompress " +
                "for two hours whether I want to or not. Doesn't matter how much coffee I had beforehand. " +
                "Body just decides it's done and that's that.",
            Instant.parse("2026-05-06T18:42:00Z"),
            12_000L,
        ),
        SeedEntry(
            "Tuesday standup hit harder than expected today. Nothing dramatic was said, " +
                "just the usual check-in, but something about the framing left me completely flat afterward. " +
                "Couldn't do anything useful for the rest of the morning. " +
                "Ate lunch just to have something to do.",
            Instant.parse("2026-05-13T20:17:00Z"),
            24_000L,
        ),
        SeedEntry(
            "Crashed at 3pm completely out of nowhere. No warning, no buildup, just suddenly couldn't think. " +
                "I was functional an hour earlier and then just gone. " +
                "Had to give up on the rest of the afternoon. " +
                "I don't know what happened.",
            Instant.parse("2026-05-09T23:13:00Z"),
            25_000L,
        ),
        SeedEntry(
            "I decided to rewrite the migration again tonight. This is the third time this week I've " +
                "restarted it with completely different reasoning each time. " +
                "I keep convincing myself the new approach is obviously better. " +
                "I think I might just be spinning and calling it progress.",
            Instant.parse("2026-05-22T16:33:00Z"),
            28_000L,
        ),
        SeedEntry(
            "I rewrote the whole doc again and this time it actually feels right. " +
                "But I said that last time too so I don't fully trust myself on this. " +
                "Different structure at least. I'm committing to this version even if it costs me another day.",
            Instant.parse("2026-05-11T11:05:00Z"),
            19_000L,
        ),
        SeedEntry(
            "I've been staring at the same first line for an hour. I know exactly what needs to happen " +
                "and I cannot make myself start it. Every time I reach for it something in me just refuses. " +
                "Nothing is blocking me except me.",
            Instant.parse("2026-05-01T19:47:00Z"),
            22_000L,
        ),
        SeedEntry(
            "Opened the doc, closed it, opened it again. Four times now. The work isn't hard, " +
                "I just can't get my hands to move on it. " +
                "It's like there's a wall right at the start and I keep bouncing off it.",
            Instant.parse("2026-05-09T09:40:00Z"),
            11_000L,
        ),
        SeedEntry(
            "Still awake at 2am, not anxious exactly, just can't seem to land. " +
                "Brain keeps spinning on things that genuinely don't need to be thought about right now. " +
                "I don't even know if this is productive or just restless. Hard to tell the difference tonight.",
            Instant.parse("2026-05-08T02:13:00Z"),
            27_000L,
        ),
        SeedEntry(
            "Actually got the whole doc done in one sitting today and I didn't expect that at all. " +
                "I kept waiting for the stall to kick in but it never did. " +
                "Weird but I'll take it. Not sure what was different.",
            Instant.parse("2026-05-15T10:24:00Z"),
            15_000L,
        ),
    )

    // Post-completion / audit narrative — part of the seeded corpus.
    @Suppress("MagicNumber")
    private fun backlogNarrative(): List<SeedEntry> = listOf(
        SeedEntry(
            "I shipped the big feature this afternoon and then immediately hit a wall. " +
                "I couldn't start anything else for like two hours. I just sat there staring at the next ticket. " +
                "I don't know why completing things does this to me but it happens every single time.",
            Instant.parse("2026-05-18T19:07:00Z"),
            20_000L,
        ),
        SeedEntry(
            "Our audit cycle started today and I reviewed everything twice before sending anything. " +
                "That kind of second-guessing slows everything down to a crawl. " +
                "It took me twice as long as it should have and I'm still not confident it was right. " +
                "That's the worst combination.",
            Instant.parse("2026-05-20T11:22:00Z"),
            16_000L,
        ),
    )

    // Vocab-drift corpus: uniform duration, stored as dense (prose, timestamp) pairs so the
    // repeated SeedEntry/Instant.parse boilerplate collapses into a single mapping. Part of the
    // seeded corpus. Timestamps are deliberately off-the-hour to read like real captures.
    @Suppress("LongMethod")
    private fun vocabDriftEntries(): List<SeedEntry> = listOf(
        Pair(
            "I hit a wall today — I'm exhausted again in a way that feels different from just tired. " +
                "Everything gave up at once around 2pm. It wasn't dramatic, I just suddenly had nothing left.",
            "2026-05-01T08:14:00Z",
        ),
        Pair(
            "I was drained by mid-morning and I don't even know why. My eyes won't focus on anything. " +
                "I tried to push through it but that just made everything worse. Then I just stopped completely.",
            "2026-05-03T21:08:00Z",
        ),
        Pair(
            "I was wiped out before noon today. There was no energy left for anything, " +
                "not even the stuff I wanted to do. " +
                "I kept telling myself five more minutes but I never moved.",
            "2026-05-07T07:19:00Z",
        ),
        Pair(
            "I've been running on empty for days. The only thing left is fumes at this point. " +
                "I got the basics done but barely. There's nothing left to pull from.",
            "2026-05-08T11:26:00Z",
        ),
        Pair(
            "I'm completely depleted today. My body feels heavier than it did yesterday and " +
                "yesterday already felt heavy enough. " +
                "I sat down to start the report and stared at it for twenty minutes before just giving up.",
            "2026-05-08T17:52:00Z",
        ),
        Pair(
            "Drained. I'm just drained. Not tired, not sleepy, not worn out. Totally drained. " +
                "Like something pulled the plug around noon and I spent the rest of the day " +
                "waiting for it to come back.",
            "2026-05-13T12:37:00Z",
        ),
        Pair(
            "I was exhausted by 10am and that's new for me. I've been running behind my own capacity for weeks " +
                "but this is the first time I ran out before lunch. " +
                "That felt like a line being crossed I did not authorize.",
            "2026-05-22T13:41:00Z",
        ),
        Pair(
            "I've been sluggish all day with a brain fog " +
                "that makes everything take three times longer than it should. " +
                "I kept losing my place in the middle of sentences.",
            "2026-05-03T10:22:00Z",
        ),
        Pair(
            "Maybe I'm burnt out and my attention is just skating across " +
                "everything without actually landing anywhere. " +
                "I'd start reading something and be three paragraphs in and have no idea what I was looking at.",
            "2026-05-12T15:33:00Z",
        ),
        Pair(
            "Tonight I'm wired again and I really don't know which is worse. " +
                "My body wants sleep, but my brain refuses. " +
                "Lying down doesn't help. Guess I'm just running on the wrong frequency.",
            "2026-05-05T14:12:00Z",
        ),
        Pair(
            "I can't sleep, can't focus, like both tanks are empty at the same time. I don't know how that works " +
                "but here I am after 1am, fully depleted and fully awake. Completely at war with myself.",
            "2026-05-17T01:19:00Z",
        ),
        // Positives — a counterweight to the exhaustion drift so the corpus isn't all doom.
        // Distinct upbeat tone words (locked-in / clear / good / sharp) form their own cluster.
        Pair(
            "Locked in for three hours and didn't notice a single one of them go by. " +
                "I looked up and the whole thing was just done. " +
                "I don't get days like this often so I'm writing it down.",
            "2026-05-10T15:23:00Z",
        ),
        Pair(
            "Clear today. No fog, no bouncing off the start. " +
                "I opened the doc and the words were already there. " +
                "It felt almost suspicious after the week I've had.",
            "2026-05-14T16:45:00Z",
        ),
        Pair(
            "Genuinely good day. I got through the whole list with energy to spare " +
                "and still went for a walk after. " +
                "Logging it so future me knows it's possible.",
            "2026-05-19T14:52:00Z",
        ),
        Pair(
            "Sharp this morning in a way I didn't earn. Everything I touched worked on the first try. " +
                "I rode it until it wore off around 3 and that was fine.",
            "2026-05-20T18:30:00Z",
        ),
    ).map { (text, timestamp) -> SeedEntry(text, Instant.parse(timestamp), VOCAB_DRIFT_DURATION_MS) }
}
