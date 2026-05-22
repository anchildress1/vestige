package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import io.objectbox.BoxStore
import java.time.Instant

object DebugPatternSeeder {

    // Only the first ACTIVE_SEED_COUNT entries of the corpus are seeded. The active set is the
    // archetype-spread tuning batch ([DEMO_ENTRIES]); the rest are preserved for later rounds.
    const val ACTIVE_SEED_COUNT = 10

    // Vocab-drift fixtures share one duration; the prose carries the variation, not the timing.
    private const val VOCAB_DRIFT_DURATION_MS = 14_000L

    /** One demo entry. Public so the on-device tuning harness can share this exact corpus. */
    data class SeedEntry(val text: String, val timestamp: Instant, val durationMs: Long)

    fun seed(boxStore: BoxStore) {
        val corpus = corpus()
        check(ACTIVE_SEED_COUNT <= corpus.size) {
            "ACTIVE_SEED_COUNT=$ACTIVE_SEED_COUNT exceeds corpus size=${corpus.size}"
        }
        boxStore.runInTx {
            boxStore.boxFor(EntryEntity::class.java).removeAll()
            boxStore.boxFor(TagEntity::class.java).removeAll()
            boxStore.boxFor(PatternEntity::class.java).removeAll()
            boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()

            corpus.take(ACTIVE_SEED_COUNT).forEachIndexed { idx, seed ->
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

    private fun corpus(): List<SeedEntry> = DEMO_ENTRIES + backlogNarrative() + vocabDriftEntries()

    /**
     * Active demo set — what [seed] loads and the on-device tuning harness runs. Ten archetype-spread
     * entries: aftermath ×4 (clears the ≥3 template-recurrence floor), decision-spiral ×2,
     * stalled ×2, goblin-hours ×1, tunnel-exit ×1. The stalled-archetype prose is deliberately
     * keyword-free so the run measures whether the lenses + labeler recover the archetype from
     * natural resistance/paralysis language rather than a planted phrase.
     */
    @Suppress("MagicNumber")
    val DEMO_ENTRIES: List<SeedEntry> = listOf(
        SeedEntry(
            "I was completely fine going into the standup but crashed hard within about twenty minutes. " +
                "Couldn't get back to the doc for the rest of the day. Then somehow wired until 2am. " +
                "That's the whole cycle in one day.",
            Instant.parse("2026-05-07T18:42:00Z"),
            18_000L,
        ),
        SeedEntry(
            "Another Tuesday, same pattern as always. The meeting ends and I just kind of decompress " +
                "for two hours whether I want to or not. Doesn't matter how much coffee I had beforehand. " +
                "Body just decides it's done and that's that.",
            Instant.parse("2026-05-12T15:33:00Z"),
            12_000L,
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
            "Crashed at 3pm completely out of nowhere. No warning, no buildup, just suddenly couldn't think. " +
                "I was functional an hour earlier and then just gone. " +
                "Had to give up on the rest of the afternoon. " +
                "I don't know what happened.",
            Instant.parse("2026-05-20T19:07:00Z"),
            25_000L,
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
            "I've been staring at the same first line for an hour. I know exactly what needs to happen " +
                "and I cannot make myself start it. Every time I reach for it something in me just refuses. " +
                "Nothing is blocking me except me.",
            Instant.parse("2026-05-05T14:12:00Z"),
            22_000L,
        ),
        SeedEntry(
            "Opened the doc, closed it, opened it again. Four times now. The work isn't hard, " +
                "I just can't get my hands to move on it. " +
                "It's like there's a wall right at the start and I keep bouncing off it.",
            Instant.parse("2026-05-19T08:40:00Z"),
            11_000L,
        ),
        SeedEntry(
            "Still awake at 2am, not anxious exactly, just can't seem to land. " +
                "Brain keeps spinning on things that genuinely don't need to be thought about right now. " +
                "I don't even know if this is productive or just restless. Hard to tell the difference tonight.",
            Instant.parse("2026-05-09T02:13:00Z"),
            27_000L,
        ),
        SeedEntry(
            "Actually got the whole doc done in one sitting today and I didn't expect that at all. " +
                "I kept waiting for the stall to kick in but it never did. " +
                "Weird but I'll take it. Not sure what was different.",
            Instant.parse("2026-05-08T10:24:00Z"),
            15_000L,
        ),
    )

    // Preserved for later tuning rounds — not seeded while ACTIVE_SEED_COUNT = 10.
    @Suppress("MagicNumber")
    private fun backlogNarrative(): List<SeedEntry> = listOf(
        SeedEntry(
            "Shipped the feature this afternoon and then immediately hit a wall. " +
                "Couldn't start anything else for like two hours, just sat there staring at the next ticket. " +
                "I don't know why completing things does this to me but it happens every single time.",
            Instant.parse("2026-05-13T21:08:00Z"),
            20_000L,
        ),
        SeedEntry(
            "Audit cycle started today and I reviewed everything twice before sending anything. " +
                "That kind of second-guessing slows everything down to a crawl. " +
                "Took me twice as long as it should have and I'm still not confident it was right. " +
                "That's the worst combination.",
            Instant.parse("2026-05-18T19:22:00Z"),
            16_000L,
        ),
    )

    // Vocab-drift corpus: uniform duration, stored as dense (prose, timestamp) pairs so the
    // repeated SeedEntry/Instant.parse boilerplate collapses into a single mapping. Preserved for
    // later tuning rounds. Timestamps are deliberately off-the-hour to read like real captures.
    @Suppress("LongMethod")
    private fun vocabDriftEntries(): List<SeedEntry> = listOf(
        Pair(
            "I hit the wall hard today — exhausted again in a way that felt different from just being tired. " +
                "Every limb gave up at once somewhere around 2pm. Not dramatic, just suddenly nothing left.",
            "2026-05-01T12:37:00Z",
        ),
        Pair(
            "Drained to the bone by mid-morning and I don't even know why. Eyes won't focus on anything. " +
                "I tried to push through it and just made everything worse. Had to stop completely.",
            "2026-05-01T17:52:00Z",
        ),
        Pair(
            "Wiped out before noon. There was no energy left for anything, not even the stuff I wanted to do. " +
                "I kept telling myself five more minutes and nothing happened.",
            "2026-05-02T01:19:00Z",
        ),
        Pair(
            "Running on empty and I've been running on empty for days. Fumes only at this point. " +
                "I got the basics done but barely. There was nothing left at the end of it.",
            "2026-05-02T07:44:00Z",
        ),
        Pair(
            "Completely depleted today. My body feels heavier than it did yesterday and " +
                "yesterday already felt heavy. " +
                "I sat down to start and stared at it for twenty minutes before giving up.",
            "2026-05-02T11:26:00Z",
        ),
        Pair(
            "Drained. Just drained. Not tired, not sleepy, not worn out — drained. " +
                "Like something pulled the plug around noon and I spent the rest of the day " +
                "waiting for it to come back.",
            "2026-05-02T19:08:00Z",
        ),
        Pair(
            "Exhausted by 10am and that's a new floor for me. I've been running behind my own capacity for weeks " +
                "but this is the first time I ran out before lunch. That felt like a line being crossed.",
            "2026-05-03T00:41:00Z",
        ),
        Pair(
            "Wiped and it's the kind that ignores caffeine. Had two coffees before noon " +
                "and felt nothing from either. " +
                "Body decided to stop being functional before I had any say in it.",
            "2026-05-03T06:33:00Z",
        ),
        Pair(
            "Sluggish all day with that brain fog that makes everything take three times longer than it should. " +
                "I kept losing my place in the middle of sentences. It was back, same as before.",
            "2026-05-03T13:17:00Z",
        ),
        Pair(
            "Foggy in a way I can't push through. Couldn't string two sentences together without losing the thread. " +
                "Sat with the document open for an hour and wrote maybe thirty usable words.",
            "2026-05-03T18:49:00Z",
        ),
        Pair(
            "Burnt out and my attention just skating across everything without landing anywhere. " +
                "I'd start reading something and be three paragraphs in with zero retention. " +
                "Tried resetting four times.",
            "2026-05-04T02:04:00Z",
        ),
        Pair(
            "Brain fog today. The cursor was blinking faster than I could think, which is how I know it's bad. " +
                "I'm slower than the default blink rate. Ended up closing everything and going for a walk.",
            "2026-05-04T07:22:00Z",
        ),
        Pair(
            "Sluggish in a way that made every task take twice as long. Simple things felt hard. " +
                "I kept re-reading the same paragraph to figure out what I was supposed to do next.",
            "2026-05-04T12:51:00Z",
        ),
        Pair(
            "Foggy and slow all day, mind moving through something that felt like molasses. " +
                "Not in a dramatic way. Just everything requiring more effort than it should. " +
                "I got through it but barely.",
            "2026-05-04T17:38:00Z",
        ),
        Pair(
            "Burnt out and the screen looked blurry even though my eyes were fine. It was coming from inside. " +
                "That's my signal that I need to stop but I kept going anyway. Bad call.",
            "2026-05-05T01:06:00Z",
        ),
        Pair(
            "Brain fog. Started three separate sentences and finished none of them. I know what I'm trying to say " +
                "but the path from that to words just isn't there right now. Closing the doc.",
            "2026-05-05T06:47:00Z",
        ),
        Pair(
            "Wired again tonight and I don't know which is worse. Body wants sleep, brain just refuses. " +
                "Lying down doesn't help. Not anxious about anything specific, just running at the wrong frequency.",
            "2026-05-05T11:33:00Z",
        ),
        Pair(
            "Anxious is the only way I can describe what this is. Lying down doesn't count as rest " +
                "when my brain is still processing everything. Slept but woke up like I hadn't slept at all.",
            "2026-05-05T19:14:00Z",
        ),
        Pair(
            "Can't sleep but I'm genuinely exhausted. The static won't quit even when I'm completely flat. " +
                "I've been horizontal for an hour and nothing is happening. Brain won't stop, body gave up.",
            "2026-05-06T00:58:00Z",
        ),
        Pair(
            "Amped but exhausted and my body and brain are completely disagreeing about what state I'm in. " +
                "Body says stop, brain says go. They've been sending opposite signals since about 8pm.",
            "2026-05-06T05:42:00Z",
        ),
        Pair(
            "Wired again and it's the third night in a row. I keep expecting it to flip into actual sleep " +
                "but it doesn't. I just lie there staring at the ceiling processing nothing useful.",
            "2026-05-06T13:09:00Z",
        ),
        Pair(
            "Anxious with eyes closed and chest racing even though nothing is happening. " +
                "No reason for it. I just can't get below a certain level of activation no matter how tired I am.",
            "2026-05-06T18:27:00Z",
        ),
        Pair(
            "Can't sleep, can't focus, both tanks empty at the same time. I don't know how that works " +
                "but here I am at 1am, fully depleted and fully awake. Completely contradictory.",
            "2026-05-07T01:51:00Z",
        ),
    ).map { (text, timestamp) -> SeedEntry(text, Instant.parse(timestamp), VOCAB_DRIFT_DURATION_MS) }
}
