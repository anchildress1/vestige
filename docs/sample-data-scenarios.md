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
- A field changes confidence: canonical vs candidate vs ambiguous vs canonical_with_conflict.
- Skeptical flags a contradiction or missing detail that changes how the field should be stored.
- Literal refuses an inference that Inferential makes, and the resolver records that difference.

Wording variation does not count. Three lenses saying the same thing in different hats is theater, and not even good theater.

Expected pressure points:
- A1: Literal should catch standup/doc/stare; Inferential may label post-meeting crash; Skeptical may flag `fine before` vs `flattened by 11` as a state shift, not a contradiction.
- A4: `Not tired exactly` vs `battery died` should pressure `energy_descriptor`; Skeptical should avoid saving `tired` as canonical without a conflict marker.
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

Story 5.1 / Story 5.4 — seeded into the reference device for recording. Distinct from the STT corpus above; do not mix IDs. Loaded via the dev-only data loader; not present in the release APK.

**Anchor pattern:** post-people-drain Crashed. Six entries across work and personal contexts using different vocabulary for the same crash shape. This is the demo's primary pattern callout moment and the embedding-advantage beat in the technical walkthrough.

**Dates:** Apr 21 – May 15 (one month, realistic cadence).

### DEMO-05

| Field | Value |
|---|---|
| ID | DEMO-05 |
| Timestamp | May 1 (Thu) 11:40 |
| Text | "After the all-hands I did the hollow routine. Coffee went cold on the desk. Everything I was going to do right after evaporated. Three tabs open. I know what they're for. Still open." |
| template_label | Crashed |
| tags | `all-hands`, `meeting`, `work`, `hollow-routine`, `tabs` |
| energy_descriptor | `hollow` |
| stated_commitment | null |
| entry_observations | 1. Post-meeting crash — work context this time. Vocabulary: "hollow routine" implies this is a recurring shape the user has named themselves. 2. Specific evidence: cold coffee, three tabs, zero execution. |
| pattern contribution | Crashed cluster #3. First work-context entry in the cluster — broadens the pattern beyond personal social events. |

### DEMO-06

| Field | Value |
|---|---|
| ID | DEMO-06 |
| Timestamp | May 3 (Sat) 12:00 |
| Text | "Said I would drop the package off today. Drive past UPS on my route. Spent twenty minutes googling whether the thing is even worth returning. It is. Label is still on the counter." |
| template_label | Stalled |
| tags | `return-label`, `package`, `UPS`, `commitment`, `googling` |
| energy_descriptor | null |
| stated_commitment | "drop the return package off today" |
| entry_observations | 1. Explicit commitment logged: "said I would drop it off today." 2. Avoidance behavior: researched the validity of the task instead of doing it. Commitment connects to DEMO-04. |
| pattern contribution | Stalled #2. Stated commitment makes this the STT-D B2 analog — stronger signal than DEMO-04. |

### DEMO-07

| Field | Value |
|---|---|
| ID | DEMO-07 |
| Timestamp | May 5 (Mon) 21:00 |
| Text | "Spent an hour and a half comparing couches. Dimensions, reviews, lead time, return policy. Made a spreadsheet. Did not buy a couch. Twelve rows." |
| template_label | Decision spiral |
| tags | `couch`, `spreadsheet`, `comparing`, `decision`, `twelve-rows` |
| energy_descriptor | null |
| stated_commitment | null |
| entry_observations | 1. Decision loop: 90 minutes, spreadsheet, 12 options, no decision. 2. Vocabulary: the user measures their own loop ("twelve rows") without explaining why they can't choose. |
| pattern contribution | Decision spiral standalone. |

### Demo Set — expected pattern callout

When DEMO-12 saves, the pattern engine should detect the Crashed cluster and fire a pattern callout appended to DEMO-12's per-entry observation. Example Witness callout:

> "Witness also noticed: sixth entry with a post-people energy drop — Apr 21, Apr 26, May 1, May 6, May 10, May 15. Events vary (dinner, lunch, all-hands, crowd, call). Shape doesn't."

The callout must cite dates or counts. It must not say "you might be feeling drained" or offer any motivation theory.

### Demo Set — embedding-advantage beat

For the technical walkthrough's retrieval comparison, query: `"post-social crash, different words each time"`.

Tag-only baseline is expected to miss DEMO-08 ("signal dropped", "couldn't reboot") and possibly DEMO-10 ("sat in the car", "did nothing useful") because keyword overlap with the crash cluster is low.

Hybrid (tag + embedding) is expected to surface both. This is the observable embedding-advantage moment — show the two result lists side by side and point to the entries that moved.

> **No template-label fixture.** A draft STT-F template-label smoke test was removed 2026-05-17: template assignment is structurally always `AUDIT` on realistic input (root cause: `backlog.md` §`archetype-template-labeling`). Any fixture that produces a non-AUDIT label only does so by feeding the exact internal trigger vocabulary — a fake test. Story 4.16 shipped the UI yank (2026-05-19); a real fixture is gated on the v1.5 redesign or confirmed prompt-tightening results on `fix/prompt-tightening-smoke-tests`.
