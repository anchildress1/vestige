# Sample Data Scenarios — Stop-and-test fixtures

Prepared validation transcripts for Vestige stop-and-test points STT-C (tag consistency, Phase 2), STT-D (3-lens convergence differs, Phase 2), and STT-E (embeddings vs tag-only, Phase 3). These are dev fixtures, not fake user preload, onboarding content, or demo confetti.

Use the same IDs in logs, screenshots, and result tables. Paste as typed entries unless a gate says to read the transcript aloud.

## Rules

- Keep expected outputs as evidence targets, not exact model prose.
- Preserve the wording. The weird phrasing is the point; vocabulary drift is not a typo farm.
- Personas affect foreground follow-up wording only. Background extraction must use the same lens prompts regardless of Witness / Hardass / Editor.
- Pattern claims must cite counts, dates, snippets, tags, or field evidence.
- No therapy, wellness, mood scoring, diagnosis, gratitude, streaks, badges, or mascot nonsense. The bar is low and somehow still useful.

## Stop-and-Test Use Matrix

| Stop-and-test | Fixture use | Pass signal |
|---|---|---|
| **STT-A** (Phase 1) — Audio chunking | Read the chunk-boundary script as one >30s capture. Force the marked split. | Final transcript preserves the crossed sentence, no duplicated or dropped meaning. |
| **STT-B** (Phase 2) — Multi-turn foreground | Historical only; current foreground is single-turn per ADR-018. | Transcript and one follow-up return cleanly. |
| **STT-C** (Phase 2) — Tag extraction | Run corpus entries A1-D3 plus X1-X3. | 10+ varied entries parse cleanly; recurring tags are stable enough for retrieval and pattern counting. |
| **STT-D** (Phase 2) — Multi-lens divergence | Run A1, A4, B1, B2, C2, D1 through three independent background lens calls. | At least 30% of tested entries show meaningful field-level divergence, not just wording garnish. |
| **STT-E** (Phase 3) — EmbeddingGemma drift | Compare tag-only retrieval vs tag+embedding in the STT-E section. | Embeddings rank semantic aftermath entries above literal keyword distractors in a way a judge can see. |
| Tool-call reliability *(PRD Open Question — blocking; not an STT)* | Optional: use B1-B3 as trigger text for a single stub pattern function. | If tool calls are flaky, cut the agentic beat. Do not make a shrine to maybe. |

## STT-A — Audio Chunk-Boundary Script

Read as one long capture. Force a 30-second split at `[CUT]`.

> "At 10:58 I left standup normal and by 11:04 I was doing the freeze thing. I opened the Q3 launch doc, got as far as the risk section, then [CUT] stared at the same sentence for twelve minutes. I said I would send Nora a clean outline by two, but the only thing I did was rename the file three times."

Expected evidence:
- Combined transcript keeps `risk section` connected to `stared at the same sentence`
- Commitment survives: `send Nora a clean outline by two`
- No duplicate phrase around the cut; no missing object after `risk section`
- If this fails, try ADR-001's overlap fallback before pretending the chunker is fine. It isn't.

## STT-B — Multi-Turn Foreground Smoke

Run the same 4 turns under Witness, Hardass, and Editor. Expected follow-ups may differ in bite, not in task.

1. "Standup ran long again. I was fine before it, then completely flattened by 11. Opened the launch doc and just stared at it."
2. "Five hours of sleep, coffee late, no food yet. The doc was the thing I was supposed to finish before the roadmap call."
3. "I got as far as the risk section, then kept rereading the same sentence. Nora asked for the outline by two."
4. "I said I would send Nora a clean outline by two. Right now the file is open and I am renaming it instead of writing."

Expected evidence:
- Prior-turn references: `standup`, `launch doc`, `risk section`, `Nora`, `outline by two`
- Follow-up style: asks for specific context or next observable action
- Forbidden style: "how did that make you feel", diagnosis, motivation theory, pep talk

## Manual Voice Recording Script — Back-to-back capture

Use these when recording entries through the app one after another. These preserve the same pattern pressure as the canonical corpus, but the voice is closer to the actual demo user instead of fixture-file taxidermy.

This sequence is for content patterns, not calendar-relative patterns. Back-to-back recordings share today's timestamp, so they cannot honestly prove "previous two Tuesdays" or "first of the month" behavior. Use harness timestamps for those.

### Meeting crash run

1. "Standup ran long again. I was fine before it, then by eleven my brain had fully left the building. Opened the launch doc and just stared at it like that was a strategy."
2. "Another Tuesday meeting, same bullshit. Went in normal, came out with lead in my limbs. Lost the next hour rearranging tabs like a professional idiot."
3. "Roadmap call ended and I did the little post-meeting corpse routine. Three tabs open, no motion, launch doc still sitting there judging me."

Expected evidence:
- Group-work event followed by attention / movement collapse
- Vocabulary drift: `left the building`, `lead in my limbs`, `corpse routine`
- Should strengthen a Crashed / post-meeting aftermath pattern once background analysis lands

### Invoice stall run

4. "Invoice email is still sitting there. I opened it twice and performed the sacred ritual of closing it immediately."
5. "I said I would send the invoice today. Instead I reorganized the desktop. Bold strategy. Absolutely not the work."
6. "Invoice again. No mystery here. I know what to do. The cursor is just sitting there like it pays rent."

Expected evidence:
- Repeated stuck task: `invoice`
- Explicit commitment in entry 5
- Should strengthen Stalled / commitment recurrence without moralizing or avoidance diagnosis

### Decision loop run

7. "Task app decision is still looping. Same three criteria, new spreadsheet, no decision. Very cool use of a human lifespan."
8. "I keep changing the weights and pretending that is progress. It is not progress. It is spreadsheet cosplay."
9. "Made another comparison table. Shockingly, the table did not turn into a decision through exposure therapy."

Expected evidence:
- Repeated comparison loop: `criteria`, `spreadsheet`, `weights`, `comparison table`
- Should strengthen Decision spiral without explaining the user's psychology

### Optional keyword traps

10. "The battery died on my keyboard during the meeting."
11. "I crashed the test app after changing the ObjectBox entity."
12. "Stalled came up in a song lyric."

Expected evidence:
- These share keywords with the meeting-crash run but should not support the cognitive-aftereffect pattern.
- If they do, the resolver is blending keywords instead of source meaning.

## Core Corpus

Set fixture timestamps manually in the harness. Preserve local hour and weekday when listed.

### Scenario A — Tuesday Meeting Crashed

Purpose: tag consistency, recurring Crashed pattern, STT-D divergence, STT-E vocabulary drift.

A1. Tuesday 11:15 — "Standup ran long again. I was fine before it, then completely flattened by 11. Opened the doc and just stared at it."

A2. Tuesday 12:10 — "Tuesday meeting. Same thing. Went in normal, came out with lead in my limbs. Ate late because I lost the plot."

A3. Thursday 15:40 — "After the roadmap call I did the little post-meeting corpse routine. Three tabs open, zero movement."

A4. Friday 10:25 — "Not tired exactly. More like the battery died after the sync."

A5. Monday 16:20 — "After the client review I went hollow. Couldn't start the next thing."

A6. Wednesday 14:05 — "The planning call left static in my head. Technically awake, operationally not."

Expected evidence:
- Related event terms: `standup`, `meeting`, `roadmap call`, `sync`, `client review`, `planning call`
- Energy/attention drop after a group work event
- Vocabulary drift: `flattened`, `specific`, `corpse routine`, `battery died`, `hollow`, `static`
- Likely labels: Crashed for A1-A6 unless the model has a better sourced reason

### Scenario B — Invoice Stalled

Purpose: task paralysis label, commitment tracking, STT-D literal vs inferential split.

B1. "Invoice email is still sitting there. I opened it twice and did the sacred ritual of closing it immediately."

B2. "Said I would send the invoice today. Instead I reorganized the desktop. Bold strategy."

B3. "Invoice again. No mystery. I know what to do. The cursor just sits there like it pays rent."

Expected evidence:
- Stuck task: `invoice email`
- Repeated commitment around sending invoice, especially B2
- Likely label: Stalled
- Forbidden output: avoidance diagnosis, moralizing, mood score

### Scenario C — Decision Spiral

Purpose: loop detection without motivation interpretation.

C1. "Spent 40 minutes choosing between the two task apps. Same three criteria, new spreadsheet, no decision."

C2. "Task app decision still looping. I keep changing the weights and pretending that is progress."

C3. "Made another comparison table. It did not become a decision through exposure therapy."

Expected evidence:
- Repeated comparison language: `criteria`, `spreadsheet`, `weights`, `comparison table`
- Likely label: Decision spiral
- Observation can mention loop mechanics; it must not explain the user's psychology

### Scenario D — Goblin Hours

Purpose: time-of-day label and shorter follow-up cadence.

D1. 03:12 — "3:12am. I am rearranging the notes app again instead of sleeping. This is not a system, it's a small administrative haunting."

D2. 02:48 — "2:48am and I'm deciding whether to rebuild the folder structure. No one requested this."

D3. 04:07 — "4:07am. I found a naming convention problem in the archive and treated it like national infrastructure."

Expected evidence:
- Local time between midnight and 5am
- Admin/reorganization loop
- Likely label: Goblin Hours
- Follow-up should be shorter and specific; no sleep hygiene lecture

### Scenario X — Literal Keyword Distractors

Purpose: make STT-E honest by including entries that share words but not meaning.

X1. "The battery died on my keyboard during the meeting."

X2. "I crashed the test app after changing the ObjectBox entity."

X3. "Stalled came up in a song lyric."

Expected evidence:
- These may match keywords, but they are not cognitive aftermath entries.
- Good retrieval ranks them below A1-A6 for the aftermath query.

## STT-D — Multi-Lens Divergence Evaluation

Run A1, A4, B1, B2, C2, and D1 as independent three-call background extractions: Literal, Inferential, Skeptical.

Meaningful divergence counts when at least one of these happens:
- A field changes confidence: consensus vs candidate vs ambiguous vs consensus_with_conflict.
- Skeptical flags a contradiction or missing detail that changes how the field should be stored.
- Literal refuses an inference that Inferential makes, and the resolver records that difference.

Wording variation does not count. Three lenses saying the same thing in different hats is theater, and not even good theater.

Expected pressure points:
- A1: Literal should catch standup/doc/stare; Inferential may label post-meeting crash; Skeptical may flag `fine before` vs `flattened by 11` as a state shift, not a contradiction.
- A4: `Not tired exactly` vs `battery died` should pressure `tags` (state words) and `vocabulary` (felt tone); Skeptical should avoid saving `tired` as consensus without a conflict marker.
- B1: Literal sees invoice/email/opened/closed; Inferential may identify task paralysis; Skeptical should note no explicit commitment yet.
- B2: Commitment should be stronger than B1 because `Said I would send` is explicit.
- C2: Inferential can label Decision spiral; Skeptical should note the actual options are missing.
- D1: Literal has 3:12am and notes app; Inferential can label Goblin Hours; Skeptical should not invent a sleep-cause explanation.

Pass condition:
- At least 2 of these 6 entries show meaningful divergence.
- Resolver output remains usable: no field should become ambiguous just because the model changed adjectives.
- If all lenses agree on all six entries, ADR-002 has not earned its inference cost. Stop and replan.

## STT-E / Scenario E — EmbeddingGemma Vocabulary-Drift Comparison

Seed A1-A6 and X1-X3. Run the same query through both retrieval modes:

> "Show entries like the post-meeting crash even when I used different words."

Compare:
- Baseline: keyword + extracted tags + recency
- Candidate: keyword + extracted tags + recency + EmbeddingGemma similarity

Relevant IDs:
- A1, A2, A3, A4, A5, A6

Distractor IDs:
- X1, X2, X3

Record:
- Top 5 IDs for each mode
- Any distractor in the top 3
- Missed relevant entries from A1-A6
- One screenshot or log table suitable for the technical walkthrough

Pass condition:
- Embedding mode places at least 4 relevant A-entries in the top 5.
- No more than 1 X distractor appears before the fourth relevant A-entry.
- The difference from baseline is visible without explaining vector math for 90 seconds. If tag-only already looks just as good, cut EmbeddingGemma to v1.5 and enjoy the saved 200 MB.

---

## Demo Set

Seeded into the reference device for recording via `DebugPatternSeeder` (`app/src/debug/kotlin/dev/anchildress1/vestige/debug/DebugPatternSeeder.kt`). Debug-only — not present in the release APK. Distinct from the STT corpus above; do not mix.

The seeder writes **36 entries**, sorted chronologically before persist. Each lands as `markdownFilename = "debug-seed-$idx.md"` with `ExtractionStatus.PENDING` and a local wall-clock timestamp (device `systemDefault` zone, not UTC). **There is no `DEMO-NN` ID scheme** — entries are identified by their seed index and timestamp.

Extraction fields (`template_label`, `tags`, `vocabulary`, `stated_commitment`, observations) are **model-emitted at extraction time, not seeded.** The seeder supplies only prose, timestamp, and duration.

### Corpus groups

| Group | Count | What it demonstrates |
|---|---|---|
| `DEMO_ENTRIES` | 12 | Archetype spread + the headline recurrence. 4 Tuesday-afternoon standup/meeting crashes (04-28, 05-05, 05-12, 05-19, ~13:30–14:30); decision-spiral ×2 (05-22 migration rewrite, 05-11 doc rewrite); stalled ×2 (05-01, 05-09 — keyword-free resistance/paralysis prose, recovered from natural language not a planted phrase); goblin-hours ×1 (05-08 02:13); tunnel-exit ×1 (05-15 "got it done"); commitment-anchor ×1 (05-22 13:00 "deal with the backlog" — modal, deadline-free promise; Skeptical flags `commitment-without-anchor` → resolves CONSENSUS_WITH_CONFLICT). |
| `backlogNarrative` | 2 | 05-18 shipped-then-hit-a-wall; 05-20 audit-cycle double-checking. |
| `vocabDriftEntries` | 15 | 11 exhaustion entries with drifted vocabulary ("hit a wall" / "drained" / "wiped out" / "running on empty" / "fumes" / "depleted" / "burnt out" / "sluggish/brain fog" / "wired" / "exhausted" / "can't sleep can't focus") + 4 positives ("locked-in" / "clear" / "good" / "sharp"). All share a 14s duration; the prose carries the variation, not the timing. |
| `artifactRecurrenceEntries` | 4 | Negative control. Thursday-evening 18:30 (04-30, 05-07, 05-14, 05-21) sharing a weekday + time slot but unrelated end-of-day logistics (coffee filters, standing desk, landlord email, ramen). The model should NOT promote it to a cognitive recurrence. |
| `demoDreadEntries` | 3 | Saturday 10:00 (04-25, 05-02, 05-09) demo-dread priors so a live typed "I hate demos" capture can join the content cluster on stage. |

### Headline demo beat

The Tuesday-afternoon meeting-crash recurrence vs. the Thursday-evening negative control. The crashes share a weekday + time-of-day slot; the third forms the time-block pattern and the fourth extracts WITH it as a candidate, so the model validates a genuine recurrence. The Thursday-evening cluster occupies the same shape (same weekday + fixed time) but with no recurring cognitive state — the demo shows the model can tell "I'm tired after work" from "I always log at 5pm."

### Patterns that actually mint on-device

Verified 2026-05-23 — 5 patterns mint on this corpus:

- `TEMPORAL_RELATIVE` "Friday Evening Stalling"
- `TEMPORAL_RELATIVE` "Thursday Evening Routine" — the negative control DID mint, as a benign time-block (not promoted to a cognitive recurrence)
- `TEMPORAL_RELATIVE` "Saturday Morning Demos" — the dread cluster minted by time, not tone
- a Tuesday-afternoon time-block — the meeting crashes
- `TEMPLATE_RECURRENCE` "Stalled"

**Zero `VOCAB_FREQUENCY` (Vocab Drift) patterns.** The vocab-drift cluster is present in the corpus but does not currently mint a Vocab Drift pattern — the embedding cluster falls below the clustering threshold (tracked in `backlog.md` §`vocab-cluster-threshold`).

> **No template-label fixture.** A draft STT-F template-label smoke test was removed 2026-05-17: template assignment is structurally always `AUDIT` on realistic input (root cause: `backlog.md` §`archetype-template-labeling`). Any fixture that produces a non-AUDIT label only does so by feeding the exact internal trigger vocabulary — a fake test. Story 4.16 shipped the UI yank (2026-05-19); a real fixture is gated on the v1.5 redesign or confirmed prompt-tightening results on `fix/prompt-tightening-smoke-tests`.
