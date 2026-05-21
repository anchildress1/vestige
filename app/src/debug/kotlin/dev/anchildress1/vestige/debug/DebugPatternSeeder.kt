package dev.anchildress1.vestige.debug

import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.storage.CalloutCooldownEntity
import dev.anchildress1.vestige.storage.EntryEntity
import dev.anchildress1.vestige.storage.PatternEntity
import dev.anchildress1.vestige.storage.TagEntity
import io.objectbox.BoxStore
import java.time.Instant

object DebugPatternSeeder {

    // Full corpus by default; lower only for limited local testing. A full refresh reloads all.
    internal const val ACTIVE_SEED_COUNT = 35

    private data class SeedEntry(val text: String, val timestamp: Instant, val durationMs: Long)

    @Suppress("MagicNumber", "LongMethod") // Fixture timestamps + corpus shape are deliberately concrete.
    fun seed(boxStore: BoxStore) {
        val entries = seedEntries()
        check(ACTIVE_SEED_COUNT <= entries.size) {
            "ACTIVE_SEED_COUNT=$ACTIVE_SEED_COUNT exceeds seed corpus size=${entries.size}"
        }
        boxStore.runInTx {
            boxStore.boxFor(EntryEntity::class.java).removeAll()
            boxStore.boxFor(TagEntity::class.java).removeAll()
            boxStore.boxFor(PatternEntity::class.java).removeAll()
            boxStore.boxFor(CalloutCooldownEntity::class.java).removeAll()

            entries.take(ACTIVE_SEED_COUNT).forEachIndexed { idx, seed ->
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

    // Fixture corpus: timestamps + prose are deliberately concrete.
    @Suppress("MagicNumber", "LongMethod")
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
        SeedEntry(
            "I hit the wall hard today — exhausted again in a way that felt different from just being tired. " +
                "Every limb gave up at once somewhere around 2pm. Not dramatic, just suddenly nothing left.",
            Instant.parse("2026-05-01T12:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Drained to the bone by mid-morning and I don't even know why. Eyes won't focus on anything. " +
                "I tried to push through it and just made everything worse. Had to stop completely.",
            Instant.parse("2026-05-01T18:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Wiped out before noon. There was no energy left for anything, not even the stuff I wanted to do. " +
                "I kept telling myself five more minutes and nothing happened.",
            Instant.parse("2026-05-02T00:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Running on empty and I've been running on empty for days. Fumes only at this point. " +
                "I got the basics done but barely. There was nothing left at the end of it.",
            Instant.parse("2026-05-02T06:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Completely depleted today. My body feels heavier than it did yesterday and " +
                "yesterday already felt heavy. " +
                "I sat down to start and stared at it for twenty minutes before giving up.",
            Instant.parse("2026-05-02T12:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Drained. Just drained. Not tired, not sleepy, not worn out — drained. " +
                "Like something pulled the plug around noon and I spent the rest of the day " +
                "waiting for it to come back.",
            Instant.parse("2026-05-02T18:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Exhausted by 10am and that's a new floor for me. I've been running behind my own capacity for weeks " +
                "but this is the first time I ran out before lunch. That felt like a line being crossed.",
            Instant.parse("2026-05-03T00:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Wiped and it's the kind that ignores caffeine. Had two coffees before noon " +
                "and felt nothing from either. " +
                "Body decided to stop being functional before I had any say in it.",
            Instant.parse("2026-05-03T06:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Sluggish all day with that brain fog that makes everything take three times longer than it should. " +
                "I kept losing my place in the middle of sentences. It was back, same as before.",
            Instant.parse("2026-05-03T12:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Foggy in a way I can't push through. Couldn't string two sentences together without losing the thread. " +
                "Sat with the document open for an hour and wrote maybe thirty usable words.",
            Instant.parse("2026-05-03T18:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Burnt out and my attention just skating across everything without landing anywhere. " +
                "I'd start reading something and be three paragraphs in with zero retention. " +
                "Tried resetting four times.",
            Instant.parse("2026-05-04T00:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Brain fog today. The cursor was blinking faster than I could think, which is how I know it's bad. " +
                "I'm slower than the default blink rate. Ended up closing everything and going for a walk.",
            Instant.parse("2026-05-04T06:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Sluggish in a way that made every task take twice as long. Simple things felt hard. " +
                "I kept re-reading the same paragraph to figure out what I was supposed to do next.",
            Instant.parse("2026-05-04T12:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Foggy and slow all day, mind moving through something that felt like molasses. " +
                "Not in a dramatic way. Just everything requiring more effort than it should. " +
                "I got through it but barely.",
            Instant.parse("2026-05-04T18:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Burnt out and the screen looked blurry even though my eyes were fine. It was coming from inside. " +
                "That's my signal that I need to stop but I kept going anyway. Bad call.",
            Instant.parse("2026-05-05T00:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Brain fog. Started three separate sentences and finished none of them. I know what I'm trying to say " +
                "but the path from that to words just isn't there right now. Closing the doc.",
            Instant.parse("2026-05-05T06:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Wired-tired again tonight and I don't know which is worse. Body wants sleep, brain just refuses. " +
                "Lying down doesn't help. Not anxious about anything specific, just running at the wrong frequency.",
            Instant.parse("2026-05-05T12:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Anxious-tired is the only way I can describe what this is. Lying down doesn't count as rest " +
                "when my brain is still processing everything. Slept but woke up like I hadn't slept at all.",
            Instant.parse("2026-05-05T18:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Can't sleep but I'm genuinely exhausted. The static won't quit even when I'm completely flat. " +
                "I've been horizontal for an hour and nothing is happening. Brain won't stop, body gave up.",
            Instant.parse("2026-05-06T00:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Amped but exhausted and my body and brain are completely disagreeing about what state I'm in. " +
                "Body says stop, brain says go. They've been sending opposite signals since about 8pm.",
            Instant.parse("2026-05-06T06:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Wired-tired again and it's the third night in a row. I keep expecting it to flip into actual sleep " +
                "but it doesn't. I just lie there staring at the ceiling processing nothing useful.",
            Instant.parse("2026-05-06T12:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Anxious-tired with eyes closed and chest racing even though nothing is happening. " +
                "No reason for it. I just can't get below a certain level of activation no matter how tired I am.",
            Instant.parse("2026-05-06T18:00:00Z"),
            14_000L,
        ),
        SeedEntry(
            "Can't sleep, can't focus, both tanks empty at the same time. I don't know how that works " +
                "but here I am at 1am, fully depleted and fully awake. Completely contradictory.",
            Instant.parse("2026-05-07T00:00:00Z"),
            14_000L,
        ),
    )
}
