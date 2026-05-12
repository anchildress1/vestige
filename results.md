# Full instrumented test suite — 2026-05-12

**Device:** Galaxy S24 Ultra (SM-S928U, Android 16)
**Branch:** `phase-2/stt-d-evidence` @ `a1156b9` (HEAD after the addendum-collapse commit; STT-D implementation byte-identical to `b621a8d`)
**Runner:** `scripts/run-full-android-test-suite.sh` (not committed)
**Logs:** `docs/stt-results/full-suite-2026-05-12/<class>.{gradle,logcat}.raw.log` (logcat filtered to `Vestige*` + `TfliteEmbedding` + `TestRunner` tags)

> **Status: complete.** All 11 classes ran + STT-C re-ran on GPU.
> **Outcome: 11 PASS, 1 FAIL (CPU original — superseded by the GPU rerun).** The CPU STT-C FAIL is an infrastructure failure (device-disconnect mid-run); the GPU rerun closed it out cleanly in 33m 21s with all 18 entries (the corpus is 15 + 3 distractors, not 15) × 3 runs internally tag-stable.

## Headline

| # | Class | Verdict | Wall clock | Headline evidence |
|---|---|---|---|---|
| 1 | `SttAAudioPlumbingTest` | ✅ PASS | 53s | AudioFile + TempWAV paths transcribe; AudioBytes path emits `INTERNAL: Failed to initialize miniaudio decoder, error code: -10` (known) |
| 2 | `LiteRtLmTextSmokeTest` | ✅ PASS | 21s | `generateText` 1748ms after 11119ms engine init |
| 3 | `LiteRtLmStreamingTextSmokeTest` | ✅ PASS | 20s | 4 chunks, first chunk 1693ms, total 2062ms, joined `"Autumn follows summer."` (22c) |
| 4 | `PersonaToneSmokeTest` | ✅ PASS | 79s | All 3 personas produce distinct follow-ups; gen times 19–21s |
| 5 | `PerCapturePersonaSmokeTest` | ✅ PASS | 153s | WITNESS / HARDASS / EDITOR distinct follow-ups; per-call 42.1 / 42.1 / 43.2s |
| 6 | `GoblinHoursAddendumSmokeTest` | ✅ PASS | 113s | `night` vs `day` prompts: 100c vs 96c (delta -4), 44.5s vs 42.8s |
| 7 | `PatternEngineSmokeTest` | ✅ PASS | 873s (14m 33s) | 6 entries, 18/18 lens parse, **`template_recurrence(4)` pattern emitted**. Omitted from future suite runs — see §7 for stats. |
| 8 | `SttCTagStabilityTest` (CPU original) | ⚠️ FAIL (infrastructure) | 5407s (90m 4s) | 11/15 entries (of 18 total) fully tested + 100% tag-stable; device disconnected from ADB after C3 run 3, never reached D1–D3 or X1–X3 |
| 8b | `SttCTagStabilityTest` (GPU rerun) | ✅ PASS | 2001s (33m 21s) | **All 18 entries × 3 runs GPU-stable**, 17/18 entries at 3/3 lens parse (B3 stable-partial at 2/3 × 3). Closes out D1–D3 + X1–X3 that CPU never reached |
| 9 | `SttDLensDivergenceTest` | ✅ PASS | 635s (10m 35s) | 11/15 meaningful, 4 Skeptical flags, A4 + B2 reachable, B3 partial — **byte-identical to reruns #1 + #2** (3rd consecutive identical verdict) |
| 10 | `EmbeddingGemmaSmokeTest` | ✅ PASS | 10s | `semanticallyRelatedEntries_haveCosineSimilarityAboveSixTenths` — embedder init 448ms, 2 embed calls (~920ms each), cosine ≥ 0.6 |
| 11 | `SttEEmbeddingComparisonTest` | ✅ PASS | 26s | **Hybrid retrieval beat tag-only baseline on 3/4 queries** (pass threshold ≥2) — see §11 for the per-query table |

## Per-class detail

### 1. SttAAudioPlumbingTest — ✅ PASS (53s)

Tests three input pathways for the multimodal model: file path, in-memory bytes, temp WAV.

| Pathway | Result | Note |
|---|---|---|
| `AudioFile` | OK | Full transcription: *"Stand up ran long again. I was fine before it, then completely flattened by 11 in the launch dock and just stared at it."* |
| `AudioBytes` | FAILED | `LiteRtLmJniException: Failed to call nativeSendMessage: INTERNAL: Failed to initialize miniaudio decoder, error code: -10` — known limitation, the test class accepts this state |
| `TempWAV` | OK | Transcription matches the AudioFile path (with `11:00` normalization difference, 1-char) |

Engine init: 11852ms. First `sendMessageContents`: 16864ms (parts=2, reply=120c). Second: 15450ms.

### 2. LiteRtLmTextSmokeTest — ✅ PASS (21s)

Pure text generation through `LiteRtLmEngine` — sanity-checks the runtime is loadable and produces tokens.

- Engine init: 11119ms
- `generateText`: 1748ms (prompt=32c, reply=2c)

### 3. LiteRtLmStreamingTextSmokeTest — ✅ PASS (20s)

Streaming-mode generation.

- Engine init: 10658ms
- `streamText`: 2045ms (prompt=68c, emitted=22c)
- 4 chunks; first chunk 1693ms; total 2062ms; joined preview: *"Autumn follows summer."*

### 4. PersonaToneSmokeTest — ✅ PASS (79s)

Three persona prompts (WITNESS / HARDASS / EDITOR) over an identical entry transcript. Validates persona-distinct follow-ups.

| Persona | Gen time | Reply chars | Follow-up |
|---|---|---|---|
| WITNESS | 19226ms | 173 | *"You said I'd send the doc by two. What was the last action taken on the doc before it remained open?"* |
| HARDASS | 21059ms | 120 | *"You are still renaming the doc. Send it by when?"* |
| EDITOR | 21297ms | 122 | *"'Still open' — which specific file? Use the name."* |

Engine init: 10562ms. All three follow-ups distinct, on-prompt for their persona.

### 5. PerCapturePersonaSmokeTest — ✅ PASS (153s)

Audio-in version of the persona test — one fresh `CaptureSession` per persona against
`/data/local/tmp/stt-b/stt-b1.wav`.

| Persona | Foreground call | Reply chars | Follow-up |
|---|---|---|---|
| WITNESS | 42205ms | 266 | *"You said completely flattened by 11 in the launch doc. What was on the launch doc you stared at?"* |
| HARDASS | 42149ms | 269 | *"You are still staring at the launch doc. What's the next observable action on that doc by when?"* |
| EDITOR | 43206ms | 278 | *"You said 'fine' and 'flattened' in relation to the same period. Which one describes the state at eleven?"* |

Engine init: 16084ms. All three distinct.

### 6. GoblinHoursAddendumSmokeTest — ✅ PASS (113s)

Tests the goblin-hours prompt addendum — same audio, two persona-prompts (`night` vs `day`).

- `night` follow-up: 100c, 44543ms — *"You said completely flattened by eleven in the launch doc..."*
- `day` follow-up: 96c, 42786ms — *"You said completely flattened by 11 in the launch doc..."*
- Delta: -4 chars; both follow-ups parse, distinct in casing of "eleven" vs "11"

### 7. PatternEngineSmokeTest — ✅ PASS (873s / 14m 33s)

End-to-end Phase-3 stack: 6 corpus entries → background 3-lens extraction → convergence resolver → template assignment → pattern detector.

- Backend: CPU (E4B FP16)
- Engine init: 16.3 s
- Corpus: 6 entries (A1, A2, A3, D1, D2, D3)
- Mean per-entry extract: **141 s**; range 139.3–144.0 s
- Lens parse rate: **18/18 (100%) on attempt 1** — no retries, no parse failures

**Per-lens timing:**

| Lens | n | Mean | Min | Max |
|---|---|---|---|---|
| LITERAL | 6 | 47.6 s | 44.7 s | 48.9 s |
| INFERENTIAL | 6 | 45.2 s | 42.5 s | 46.7 s |
| SKEPTICAL | 6 | 48.5 s | 45.9 s | 53.8 s |

**Template assignment + pattern detection (the demo beat):**

| Entry | Template |
|---|---|
| A1 | `audit` |
| A2 | `audit` |
| A3 | `audit` |
| D1 | `goblin-hours` |
| D2 | `decision-spiral` |
| D3 | `audit` |

→ Pattern detector emitted **`template_recurrence(4)`** — recognized 4 entries share the `audit` template and surfaced it as a recurrence claim. Full Phase-3 stack end-to-end on real data.

**Omitted from future suite runs** (`scripts/run-full-android-test-suite.sh` patched). The 14m 33s wall clock is the single largest cost in the suite, and these stats are the captured demo evidence — re-enable on demand when there's a reason to re-validate the full Phase-3 stack.

### 8. SttCTagStabilityTest — ⚠️ FAIL at gradle level (90m 4s)

**The test FAILED gradle-side because the device disconnected from ADB after C3 run 3 completed at 14:27:58. The 11 entries that did finish were 100% tag-stable across 3 runs each — every (entry × 3 runs) produced 3/3 lens parse, attempts=1, identical tag sets.**

Per CLAUDE.md memory: test-pattern failure is not the same as underlying-capability failure. The gradle verdict is FAIL because the JUnit test never reached the final assertion (`assertTrue(allStable, …)`). The cause was a host-side `device 'R5CWC1VKLEJ' not found` ADB error — the device disconnected after ~96 min of sustained CPU inference. Plausible reasons: thermal throttle, USB suspend, doze. Re-run on GPU (~25–30 min) is the cheap path to a clean verdict.

Manifest: `/data/local/tmp/stt-c-manifest.txt`. Default `runsPerEntry=3`. Backend: CPU. Started 12:58:36. Last successful run: C3 run 3 at 14:27:58. Total wall clock to gradle FAIL: 5407s (90m 4s).

**Final verdict on what was tested on CPU: 11 of 18 entries fully completed (A1–A6, B1–B3, C1–C3 = 33 runs = 99 model calls). Tag-stability held on all 11 — 100% across every (entry × run × lens) of the tested subset.** D1–D3 + X1–X3 (the distractor cohort) were never reached on CPU.

### 8b. SttCTagStabilityTest — GPU rerun — ✅ PASS (33m 21s)

Reran on GPU with `-PinferenceBackend=gpu` to close out D1–D3 + X1–X3. Started 15:14:11, finished 15:47:36, gradle wall clock **2001 s (33m 21s)** — **~3× faster than the failed CPU run** and finished cleanly.

**Verdict: PASS.** All 18 entries × 3 runs internally tag-stable on GPU. Gradle `BUILD SUCCESSFUL`, JUnit `1 tests / 0 failed / 0 skipped`.

| Entry | Runs (s) | Lens parse | GPU tags |
|---|---|---|---|
| A1 | 31.0 / 30.2 / 33.2 | 3/3 × 3 | `standup, flattened` |
| A2 | 28.6 / 26.9 / 29.4 | 3/3 × 3 | `meeting, late-night` |
| A3 | 38.1 / 31.5 / 34.2 | 3/3 × 3 | `roadmap-call, post-meeting-routine, tabs-open, zero-movement` |
| A4 | 40.4 / 37.5 / 39.2 | 3/3 × 3 | `battery-yanked` |
| A5 | 35.4 / 36.3 / 35.2 | 3/3 × 3 | `client-review, hollow` |
| A6 | 32.2 / 33.8 / 33.0 | 3/3 × 3 | `planning-call, static, awake` |
| B1 | 37.8 / 37.7 / 37.8 | 3/3 × 3 | `invoice-email` |
| B2 | 47.6 / 48.4 / 48.7 | 3/3 × 3 | `invoice, desktop-reorganization` |
| B3 | 45.2 / 46.3 / 41.9 | **2/3** × 3 | `invoice, cursor` (same lens drops out every run — deterministic partial-parse) |
| C1 | 40.9 / 39.5 / 42.0 | 3/3 × 3 | `spreadsheet, task-app-selection` |
| C2 | 38.0 / 37.9 / 38.2 | 3/3 × 3 | `task-app, decision-loop` |
| C3 | 34.3 / 33.8 / 36.7 | 3/3 × 3 | `comparison-table` |
| D1 | 35.1 / 35.2 / 38.2 | 3/3 × 3 | `late-night, notes-app` |
| D2 | 36.2 / 36.5 / 37.4 | 3/3 × 3 | `late-night, decision-loop` |
| D3 | 35.9 / 35.7 / 35.4 | 3/3 × 3 | `4:07am, archive, naming-convention` |
| X1 | 32.8 / 35.7 / 34.2 | 3/3 × 3 | `meeting, battery-died` |
| X2 | 36.9 / 36.1 / 37.0 | 3/3 × 3 | `crashed, test-app, objectbox-entity` |
| X3 | 32.4 / 32.9 / 32.3 | 3/3 × 3 | `song-lyric` |

Mean per-run latency: **36.3 s** (vs ~148 s on CPU = 4.1× speedup). Total model calls completed: 159 of 162 attempted (98.1% parse rate; B3's deterministic partial-parse accounts for the 3 misses).

**Cross-backend observation (not part of the test's pass criterion).** STT-C asserts *per-backend* stability, which holds 100% on both CPU and GPU independently. Across backends, 6 of 12 commonly-tested entries produce identical tag sets and 6 diverge:

| Entry | CPU tags | GPU tags | Match? |
|---|---|---|---|
| A1 | `standup, doc, flattened` | `standup, flattened` | ❌ GPU drops `doc` |
| A2 | `meeting, late-night` | `meeting, late-night` | ✅ |
| A3 | `post-meeting, corpse-routine, tabs-open, zero-movement, roadmap-call` | `roadmap-call, post-meeting-routine, tabs-open, zero-movement` | ❌ GPU collapses `post-meeting + corpse-routine` → `post-meeting-routine`, drops `corpse-routine` |
| A4 | `sync, battery-drain` | `battery-yanked` | ❌ significantly different |
| A5 | `client-review, hollow` | `client-review, hollow` | ✅ |
| A6 | `planning-call, static, awake, not-awake` | `planning-call, static, awake` | ❌ GPU drops `not-awake` (the weird negation tag) |
| B1 | `invoice-email, closing-task` | `invoice-email` | ❌ GPU drops `closing-task` |
| B2 | `invoice, desktop-reorganization` | `invoice, desktop-reorganization` | ✅ |
| B3 | `invoice, cursor` (3/3) | `invoice, cursor` (2/3) | ✅ tags, ❌ parse rate |
| C1 | `task-app, decision-loop` | `spreadsheet, task-app-selection` | ❌ entirely different framing |
| C2 | `task-app, decision-loop` | `task-app, decision-loop` | ✅ |
| C3 | `comparison-table` | `comparison-table` | ✅ |

Same model, same prompt, same `seed=42` greedy sampler. The divergence is **backend-level numerical drift** (FP16 CPU vs FP16 GPU OpenCL) producing different argmax selections at points where the top-1 and top-2 token logits are close. The test passes because the rubric is per-backend reproducibility, not cross-backend equivalence — which is the right gate, since the production app pins one backend.

| Entry | Parse (per run) | Stable | Mean latency | Range (per run) | Per-run latencies (ms) | Tags |
|---|---|---|---|---|---|---|
| A1 | 3/3 × 3 | ✅ | 151.9 s | 150.5–154.5 s | 154544, 150536, 150607 | `standup, doc, flattened` |
| A2 | 3/3 × 3 | ✅ | 146.3 s | 145.7–147.3 s | 145863, 147302, 145675 | `meeting, late-night` |
| A3 | 3/3 × 3 | ✅ | 147.6 s | 145.4–151.4 s | 151395, 146056, 145351 | `post-meeting, corpse-routine, tabs-open, zero-movement, roadmap-call` |
| A4 | 3/3 × 3 | ✅ | 153.1 s | 150.8–154.3 s | 150797, 154343, 154120 | `sync, battery-drain` |
| A5 | 3/3 × 3 | ✅ | 147.0 s | 145.6–149.3 s | 149341, 145623, 146044 | `client-review, hollow` |
| A6 | 3/3 × 3 | ✅ | 152.2 s | 150.7–153.6 s | 153550, 152340, 150687 | `planning-call, static, awake, not-awake` |
| B1 | 3/3 × 3 | ✅ | 144.4 s | 139.7–147.6 s | 147615, 146035, 139653 | `invoice-email, closing-task` |
| B2 | 3/3 × 3 | ✅ | 158.5 s | 150.1–165.8 s | 150106, 159735, 165751 | `invoice, desktop-reorganization` |
| B3 | 3/3 × 3 | ✅ | 149.2 s | 147.3–150.7 s | 150693, 147337, 149662 | `invoice, cursor` |
| C1 | 3/3 × 3 | ✅ | 153.5 s | 146.9–158.8 s | 154862, 158757, 146940 | `task-app, decision-loop` |
| C2 | 3/3 × 3 | ✅ | 137.3 s | 137.1–137.7 s | 137055, 137682, 137249 | `task-app, decision-loop` |
| C3 | 3/3 × 3 | ✅ | 138.1 s | 135.9–142.3 s | 135934, 135935, 142334 | `comparison-table` |
| D1 | ❌ never reached | — | — | — | — | — |
| D2 | ❌ never reached | — | — | — | — | — |
| D3 | ❌ never reached | — | — | — | — | — |

Per-run latency: min 135.9 s (C3, run 1), max 165.8 s (B2, run 3), mean across 33 completed runs ≈ **148 s**. **Test FAILED at gradle level — device disconnected from ADB shortly after C3 run 3 finished.**

### 9. SttDLensDivergenceTest — ⏳ pending (third invocation of the suite-run)

Pre-suite results (from the two dedicated back-to-back reruns earlier today, commits 55959c4 + a1156b9):

| Factor | Cut | Rerun #1 | Rerun #2 |
|---|---|---|---|
| Meaningful divergence | ≥ 50% | 11/15 = 73% | 11/15 = 73% |
| `canonical_with_conflict` reachable | ≥ 2 | A4 + B2 | A4 + B2 |
| Parse stability | ≥ 90%, 0 timeouts | 44/45, 0 | 44/45, 0 |
| Run-to-run consistency | Jaccard ≥ 0.75, flag Δ ≤ 1 | **Jaccard 1.0, Δ 0** | |

The suite-run is the third invocation of this test — placeholder pending log capture; expected to reproduce the same verdict (greedy + seed=42 is deterministic on this engine).

### 10. EmbeddingGemmaSmokeTest — ✅ PASS (10s)

Test: `semanticallyRelatedEntries_haveCosineSimilarityAboveSixTenths`. Loads `embeddinggemma-300M_seq512_mixed-precision.tflite` + `sentencepiece.model`, embeds two semantically-related texts, asserts cosine similarity ≥ 0.6.

- Embedder init: 448ms (`Initializing embedder` → `Finished initializing embedder`)
- Embedding call 1: ~920ms (`Getting embeddings` → `Finished getting embeddings`)
- Embedding call 2: ~922ms
- Test wall clock (JUnit `started` → `finished`): 2320ms
- Total gradle wall clock: 10s (includes APK install + cleanup)
- JUnit: 1 test, 0 failed, 0 ignored
- Cosine value itself is not logged on success path — only on assertion-failure. Test PASSED, so cosine ≥ 0.6 held.

### 11. SttEEmbeddingComparisonTest — ✅ PASS (26s)

Seeds the 18-entry STT-E corpus with real EmbeddingGemma vectors, then compares **tag-only baseline** vs **hybrid (tag + vector)** retrieval across four scenario queries.

| Query | Relevant cohort | Cohort size |
|---|---|---|
| Q_aftermath | A1–A6 | 6 |
| Q_invoice | B1–B3 | 3 |
| Q_decision | C1–C3 | 3 |
| Q_lateNight | D1–D3 | 3 |

Per query: top-5 retrieved. Hybrid "wins" iff `hybrid_relevant_count >= baseline_relevant_count` AND `hybrid_novel_relevant_ids` is non-empty.

Pass threshold: ≥ 2 of 4 hybrid wins; per-query relevance floor `MIN_RELEVANT = 2`.

**Final results:**

| Query | Baseline top-5 | Baseline relevant | Hybrid top-5 | Hybrid relevant | Novel relevant | Hybrid win? |
|---|---|---|---|---|---|---|
| Q_aftermath | `[A3, X2, D3, B3, A5]` | 2/6 | `[X2, A3, A2, D3, A1]` | **3/6** | `[A2, A1]` | ✅ |
| Q_invoice | `[B3, B2, B1, A5, D3]` | 3/3 | `[B2, B1, B3, A1, A5]` | 3/3 | `[]` | ❌ (no novel) |
| Q_decision | `[C1, A5, D3, B3, D2]` | 1/3 | `[C1, C2, A1, D2, A5]` | **2/3** | `[C2]` | ✅ |
| Q_lateNight | `[D1, B2, B1, A1, A5]` | 1/3 | `[D1, B2, A1, B1, D2]` | **2/3** | `[D2]` | ✅ |

**Final win-rate: 3/4 queries hybrid won (cut: ≥ 2). Pass with margin of 1.**

Key observations:

- **Q_aftermath**: tag-only baseline could only find 2 of 6 A-cohort entries (A3 + A5). Hybrid surfaced A1 + A2 by semantic similarity that tag-only missed — exactly the demo beat ("Show entries like the post-meeting crash even when I used different words").
- **Q_invoice**: tag-only baseline already nailed all 3 B-cohort entries (B1 + B2 + B3 in top 5). Hybrid tied on relevant count but surfaced no novel — counted as a non-win per the strict rule.
- **Q_decision + Q_lateNight**: tag-only found only 1/3 of the cohort. Hybrid added one more in each case (C2 and D2 respectively).
- Total of 4 novel relevant entries surfaced by hybrid that tag-only missed: A1, A2, C2, D2.

Per-query timing: 17.3s (Q_aftermath seeding + retrieval), 0.9s (Q_invoice), 0.9s (Q_decision), 0.9s (Q_lateNight) — first query carries the bulk of seeding cost (18 entries × 1 embed = 18 embed calls); subsequent queries are pure retrieval against the populated repo.

## Notes on the runner script

`scripts/run-full-android-test-suite.sh` is currently untracked. It's the methodology
artifact that produced this evidence; deciding whether it should be committed is a
follow-up — explicitly NOT done in this current capture per your instruction.

**SUMMARY.tsv `notes` column is unreliable for this suite-run.** The original regex
matched the literal `skipped` substring against gradle's streaming line
`Tests N/M completed. (0 skipped) (0 failed)` — so every clean PASS gets falsely tagged
`skipped:assumeTrue`. Independently confirmed for `PatternEngineSmokeTest`:

- gradle log: `Finished 1 tests on SM-S928U - 16` + `BUILD SUCCESSFUL in 14m 32s`
- logcat: 0× `AssumptionViolatedException`, 18× `generateText completed` (6 entries × 3 lenses, no retries)
- the only `Skipping` strings in the logcat are Android OS chatter (WorkManager, Finsky, NearbySharing)

Same pattern holds for every other completed row above (gradle `Finished 1 tests` + zero
assumption-violation, real model calls in logcat). The notes column is fixed in the script
source going forward, but the in-flight suite still uses the old definition — so STT-C / STT-D /
EmbeddingGemma / STT-E rows will also carry the misleading note when they land. Verdicts in this
file are anchored to gradle's `BUILD SUCCESSFUL` + `Finished` lines, not to the noisy column.

## Final tally

- [x] STT-C log landed — gradle FAIL (device disconnect), 11/15 entries tested, 100% tag-stable on the subset
- [x] STT-D suite-run landed — PASS, **third consecutive byte-identical verdict** to reruns #1 + #2
- [x] EmbeddingGemma smoke landed — PASS, cosine ≥ 0.6 held
- [x] STT-E landed — PASS, **3/4 hybrid wins** with 4 novel-relevant entries surfaced (A1, A2, C2, D2)

## Suggested follow-up (not done)

- **STT-D suite-run archive.** Update the rubric §"Addendum (sixth)" with a third row confirming the third identical verdict, OR leave the two-rerun archive alone since it's already conclusive. Your call.
- **Cross-backend tag divergence is a real finding.** STT-C tests per-backend stability, which holds. The cross-backend half-match rate (6/12 commonly tested) is not currently a gated behaviour and likely fine for v1 since production pins one backend, but worth filing in `backlog.md` if you ever want a portability claim. Not done here.

---

# Raw appendix — every metric squeezed from logcat

Generated 2026-05-12 ~14:27. **No calculations applied — these are the raw values emitted by the running test, in time order.** Aggregations (means, ranges, win-rates) elsewhere in this file derive from these tables; this section is the provenance.

Conventions for the table values column:
- ms values are integers, already in milliseconds
- char counts are character counts of the emitted token strings
- `compose_tokens~` is the model's approximate tokenizer estimate, not a measured count
- Timestamps are local Pacific time (logcat-default) on the test device

## SttCTagStabilityTest — raw lens-level rows

105 rows so far (35 fully completed runs + 0 partial; C3 run 3 + D1–D3 still pending). One row per (entry, run, lens). Every row carries the per-lens latency, the underlying `generateText` call timing + char counts, the prompt-composer chars + token estimate, the convergence resolver tags for the run, and the per-run total.

| ts (lens_done) | entry | run | lens | parsed | attempts | lens_elapsed_ms | gen_ms | prompt_chars | reply_chars | compose_chars | compose_tokens~ | history | run_total_ms | run_lenses_parsed | run_tags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 05-12 12:59:51.895 | A1 | 1 | LITERAL | True | 1 | 51304 | 51298 | 7674 | 223 | 7674 | 1919 | 0 | 154544 | 3/3 | `standup,doc,flattened` |
| 05-12 13:00:37.606 | A1 | 1 | INFERENTIAL | True | 1 | 45711 | 45708 | 8219 | 206 | 8219 | 2055 | 0 | 154544 | 3/3 | `standup,doc,flattened` |
| 05-12 13:01:35.124 | A1 | 1 | SKEPTICAL | True | 1 | 57517 | 57472 | 8165 | 401 | 8165 | 2042 | 0 | 154544 | 3/3 | `standup,doc,flattened` |
| 05-12 13:02:23.452 | A1 | 2 | LITERAL | True | 1 | 48314 | 48302 | 7674 | 223 | 7674 | 1919 | 0 | 150536 | 3/3 | `standup,doc,flattened` |
| 05-12 13:03:09.202 | A1 | 2 | INFERENTIAL | True | 1 | 45750 | 45747 | 8219 | 206 | 8219 | 2055 | 0 | 150536 | 3/3 | `standup,doc,flattened` |
| 05-12 13:04:05.673 | A1 | 2 | SKEPTICAL | True | 1 | 56470 | 56462 | 8165 | 401 | 8165 | 2042 | 0 | 150536 | 3/3 | `standup,doc,flattened` |
| 05-12 13:04:53.938 | A1 | 3 | LITERAL | True | 1 | 48263 | 48260 | 7674 | 223 | 7674 | 1919 | 0 | 150607 | 3/3 | `standup,doc,flattened` |
| 05-12 13:05:39.554 | A1 | 3 | INFERENTIAL | True | 1 | 45615 | 45612 | 8219 | 206 | 8219 | 2055 | 0 | 150607 | 3/3 | `standup,doc,flattened` |
| 05-12 13:06:36.281 | A1 | 3 | SKEPTICAL | True | 1 | 56726 | 56721 | 8165 | 401 | 8165 | 2042 | 0 | 150607 | 3/3 | `standup,doc,flattened` |
| 05-12 13:07:24.861 | A2 | 1 | LITERAL | True | 1 | 48576 | 48570 | 7672 | 226 | 7672 | 1918 | 0 | 145863 | 3/3 | `meeting,late-night` |
| 05-12 13:08:12.585 | A2 | 1 | INFERENTIAL | True | 1 | 47724 | 47720 | 8217 | 207 | 8217 | 2055 | 0 | 145863 | 3/3 | `meeting,late-night` |
| 05-12 13:09:02.143 | A2 | 1 | SKEPTICAL | True | 1 | 49557 | 49548 | 8163 | 332 | 8163 | 2041 | 0 | 145863 | 3/3 | `meeting,late-night` |
| 05-12 13:09:52.993 | A2 | 2 | LITERAL | True | 1 | 50840 | 50829 | 7672 | 226 | 7672 | 1918 | 0 | 147302 | 3/3 | `meeting,late-night` |
| 05-12 13:10:40.097 | A2 | 2 | INFERENTIAL | True | 1 | 47104 | 47101 | 8217 | 207 | 8217 | 2055 | 0 | 147302 | 3/3 | `meeting,late-night` |
| 05-12 13:11:29.453 | A2 | 2 | SKEPTICAL | True | 1 | 49356 | 49353 | 8163 | 332 | 8163 | 2041 | 0 | 147302 | 3/3 | `meeting,late-night` |
| 05-12 13:12:18.917 | A2 | 3 | LITERAL | True | 1 | 49459 | 49457 | 7672 | 226 | 7672 | 1918 | 0 | 145675 | 3/3 | `meeting,late-night` |
| 05-12 13:13:06.206 | A2 | 3 | INFERENTIAL | True | 1 | 47288 | 47286 | 8217 | 207 | 8217 | 2055 | 0 | 145675 | 3/3 | `meeting,late-night` |
| 05-12 13:13:55.132 | A2 | 3 | SKEPTICAL | True | 1 | 48925 | 48922 | 8163 | 332 | 8163 | 2041 | 0 | 145675 | 3/3 | `meeting,late-night` |
| 05-12 13:14:48.057 | A3 | 1 | LITERAL | True | 1 | 52922 | 52919 | 7658 | 250 | 7658 | 1915 | 0 | 151395 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:15:37.695 | A3 | 1 | INFERENTIAL | True | 1 | 49637 | 49633 | 8203 | 264 | 8203 | 2051 | 0 | 151395 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:16:26.529 | A3 | 1 | SKEPTICAL | True | 1 | 48833 | 48830 | 8149 | 264 | 8149 | 2038 | 0 | 151395 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:17:17.173 | A3 | 2 | LITERAL | True | 1 | 50641 | 50635 | 7658 | 250 | 7658 | 1915 | 0 | 146056 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:18:04.298 | A3 | 2 | INFERENTIAL | True | 1 | 47124 | 47122 | 8203 | 264 | 8203 | 2051 | 0 | 146056 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:18:52.587 | A3 | 2 | SKEPTICAL | True | 1 | 48288 | 48285 | 8149 | 264 | 8149 | 2038 | 0 | 146056 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:19:40.843 | A3 | 3 | LITERAL | True | 1 | 48254 | 48251 | 7658 | 250 | 7658 | 1915 | 0 | 145351 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:20:29.020 | A3 | 3 | INFERENTIAL | True | 1 | 48176 | 48173 | 8203 | 264 | 8203 | 2051 | 0 | 145351 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:21:17.939 | A3 | 3 | SKEPTICAL | True | 1 | 48919 | 48916 | 8149 | 264 | 8149 | 2038 | 0 | 145351 | 3/3 | `post-meeting,corpse-routine,tabs-open,zero-movement,roadmap-call` |
| 05-12 13:22:05.876 | A4 | 1 | LITERAL | True | 1 | 47930 | 47927 | 7625 | 212 | 7625 | 1907 | 0 | 150797 | 3/3 | `sync,battery-drain` |
| 05-12 13:22:52.792 | A4 | 1 | INFERENTIAL | True | 1 | 46916 | 46913 | 8170 | 199 | 8170 | 2043 | 0 | 150797 | 3/3 | `sync,battery-drain` |
| 05-12 13:23:48.742 | A4 | 1 | SKEPTICAL | True | 1 | 55950 | 55947 | 8116 | 556 | 8116 | 2029 | 0 | 150797 | 3/3 | `sync,battery-drain` |
| 05-12 13:24:38.698 | A4 | 2 | LITERAL | True | 1 | 49947 | 49937 | 7625 | 212 | 7625 | 1907 | 0 | 154343 | 3/3 | `sync,battery-drain` |
| 05-12 13:25:27.400 | A4 | 2 | INFERENTIAL | True | 1 | 48701 | 48691 | 8170 | 199 | 8170 | 2043 | 0 | 154343 | 3/3 | `sync,battery-drain` |
| 05-12 13:26:23.091 | A4 | 2 | SKEPTICAL | True | 1 | 55690 | 55680 | 8116 | 556 | 8116 | 2029 | 0 | 154343 | 3/3 | `sync,battery-drain` |
| 05-12 13:27:13.241 | A4 | 3 | LITERAL | True | 1 | 50144 | 50132 | 7625 | 212 | 7625 | 1907 | 0 | 154120 | 3/3 | `sync,battery-drain` |
| 05-12 13:27:59.615 | A4 | 3 | INFERENTIAL | True | 1 | 46374 | 46366 | 8170 | 199 | 8170 | 2043 | 0 | 154120 | 3/3 | `sync,battery-drain` |
| 05-12 13:28:57.214 | A4 | 3 | SKEPTICAL | True | 1 | 57597 | 57579 | 8116 | 556 | 8116 | 2029 | 0 | 154120 | 3/3 | `sync,battery-drain` |
| 05-12 13:29:48.866 | A5 | 1 | LITERAL | True | 1 | 51645 | 51642 | 7627 | 217 | 7627 | 1907 | 0 | 149341 | 3/3 | `client-review,hollow` |
| 05-12 13:30:37.438 | A5 | 1 | INFERENTIAL | True | 1 | 48571 | 48567 | 8172 | 213 | 8172 | 2043 | 0 | 149341 | 3/3 | `client-review,hollow` |
| 05-12 13:31:26.561 | A5 | 1 | SKEPTICAL | True | 1 | 49123 | 49117 | 8118 | 212 | 8118 | 2030 | 0 | 149341 | 3/3 | `client-review,hollow` |
| 05-12 13:32:16.163 | A5 | 2 | LITERAL | True | 1 | 49597 | 49594 | 7627 | 217 | 7627 | 1907 | 0 | 145623 | 3/3 | `client-review,hollow` |
| 05-12 13:33:04.507 | A5 | 2 | INFERENTIAL | True | 1 | 48344 | 48341 | 8172 | 213 | 8172 | 2043 | 0 | 145623 | 3/3 | `client-review,hollow` |
| 05-12 13:33:52.188 | A5 | 2 | SKEPTICAL | True | 1 | 47680 | 47677 | 8118 | 212 | 8118 | 2030 | 0 | 145623 | 3/3 | `client-review,hollow` |
| 05-12 13:34:41.872 | A5 | 3 | LITERAL | True | 1 | 49681 | 49679 | 7627 | 217 | 7627 | 1907 | 0 | 146044 | 3/3 | `client-review,hollow` |
| 05-12 13:35:29.539 | A5 | 3 | INFERENTIAL | True | 1 | 47666 | 47662 | 8172 | 213 | 8172 | 2043 | 0 | 146044 | 3/3 | `client-review,hollow` |
| 05-12 13:36:18.229 | A5 | 3 | SKEPTICAL | True | 1 | 48690 | 48680 | 8118 | 212 | 8118 | 2030 | 0 | 146044 | 3/3 | `client-review,hollow` |
| 05-12 13:37:10.370 | A6 | 1 | LITERAL | True | 1 | 52128 | 52114 | 7637 | 235 | 7637 | 1910 | 0 | 153550 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:38:01.878 | A6 | 1 | INFERENTIAL | True | 1 | 51508 | 51500 | 8182 | 235 | 8182 | 2046 | 0 | 153550 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:38:51.791 | A6 | 1 | SKEPTICAL | True | 1 | 49912 | 49904 | 8128 | 234 | 8128 | 2032 | 0 | 153550 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:39:42.867 | A6 | 2 | LITERAL | True | 1 | 51074 | 51069 | 7637 | 235 | 7637 | 1910 | 0 | 152340 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:40:34.886 | A6 | 2 | INFERENTIAL | True | 1 | 52018 | 52010 | 8182 | 235 | 8182 | 2046 | 0 | 152340 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:41:24.133 | A6 | 2 | SKEPTICAL | True | 1 | 49246 | 49235 | 8128 | 234 | 8128 | 2032 | 0 | 152340 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:42:14.819 | A6 | 3 | LITERAL | True | 1 | 50680 | 50672 | 7637 | 235 | 7637 | 1910 | 0 | 150687 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:43:05.747 | A6 | 3 | INFERENTIAL | True | 1 | 50927 | 50915 | 8182 | 235 | 8182 | 2046 | 0 | 150687 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:43:54.825 | A6 | 3 | SKEPTICAL | True | 1 | 49077 | 49074 | 8128 | 234 | 8128 | 2032 | 0 | 150687 | 3/3 | `planning-call,static,awake,not-awake` |
| 05-12 13:44:44.986 | B1 | 1 | LITERAL | True | 1 | 50153 | 50143 | 7666 | 219 | 7666 | 1917 | 0 | 147615 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:45:33.795 | B1 | 1 | INFERENTIAL | True | 1 | 48808 | 48798 | 8211 | 215 | 8211 | 2053 | 0 | 147615 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:46:22.441 | B1 | 1 | SKEPTICAL | True | 1 | 48645 | 48633 | 8157 | 215 | 8157 | 2040 | 0 | 147615 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:47:12.726 | B1 | 2 | LITERAL | True | 1 | 50270 | 50257 | 7666 | 219 | 7666 | 1917 | 0 | 146035 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:48:00.842 | B1 | 2 | INFERENTIAL | True | 1 | 48115 | 48101 | 8211 | 215 | 8211 | 2053 | 0 | 146035 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:48:48.487 | B1 | 2 | SKEPTICAL | True | 1 | 47644 | 47636 | 8157 | 215 | 8157 | 2040 | 0 | 146035 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:49:37.758 | B1 | 3 | LITERAL | True | 1 | 49261 | 49248 | 7666 | 219 | 7666 | 1917 | 0 | 139653 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:50:23.220 | B1 | 3 | INFERENTIAL | True | 1 | 45461 | 45451 | 8211 | 215 | 8211 | 2053 | 0 | 139653 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:51:08.147 | B1 | 3 | SKEPTICAL | True | 1 | 44926 | 44920 | 8157 | 215 | 8157 | 2040 | 0 | 139653 | 3/3 | `invoice-email,closing-task` |
| 05-12 13:51:56.063 | B2 | 1 | LITERAL | True | 1 | 47911 | 47904 | 7644 | 293 | 7644 | 1911 | 0 | 150106 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:52:44.970 | B2 | 1 | INFERENTIAL | True | 1 | 48907 | 48904 | 8189 | 288 | 8189 | 2048 | 0 | 150106 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:53:38.256 | B2 | 1 | SKEPTICAL | True | 1 | 53285 | 53282 | 8135 | 493 | 8135 | 2034 | 0 | 150106 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:54:28.322 | B2 | 2 | LITERAL | True | 1 | 50060 | 50057 | 7644 | 293 | 7644 | 1911 | 0 | 159735 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:55:19.071 | B2 | 2 | INFERENTIAL | True | 1 | 50748 | 50745 | 8189 | 288 | 8189 | 2048 | 0 | 159735 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:56:17.996 | B2 | 2 | SKEPTICAL | True | 1 | 58925 | 58921 | 8135 | 493 | 8135 | 2034 | 0 | 159735 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:57:13.827 | B2 | 3 | LITERAL | True | 1 | 55824 | 55820 | 7644 | 293 | 7644 | 1911 | 0 | 165751 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:58:06.982 | B2 | 3 | INFERENTIAL | True | 1 | 53155 | 53152 | 8189 | 288 | 8189 | 2048 | 0 | 165751 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:59:03.753 | B2 | 3 | SKEPTICAL | True | 1 | 56770 | 56767 | 8135 | 493 | 8135 | 2034 | 0 | 165751 | 3/3 | `invoice,desktop-reorganization` |
| 05-12 13:59:55.475 | B3 | 1 | LITERAL | True | 1 | 51714 | 51704 | 7649 | 207 | 7649 | 1913 | 0 | 150693 | 3/3 | `invoice,cursor` |
| 05-12 14:00:45.810 | B3 | 1 | INFERENTIAL | True | 1 | 50335 | 50328 | 8194 | 207 | 8194 | 2049 | 0 | 150693 | 3/3 | `invoice,cursor` |
| 05-12 14:01:34.452 | B3 | 1 | SKEPTICAL | True | 1 | 48641 | 48635 | 8140 | 203 | 8140 | 2035 | 0 | 150693 | 3/3 | `invoice,cursor` |
| 05-12 14:02:24.577 | B3 | 2 | LITERAL | True | 1 | 50122 | 50114 | 7649 | 207 | 7649 | 1913 | 0 | 147337 | 3/3 | `invoice,cursor` |
| 05-12 14:03:13.648 | B3 | 2 | INFERENTIAL | True | 1 | 49070 | 49059 | 8194 | 207 | 8194 | 2049 | 0 | 147337 | 3/3 | `invoice,cursor` |
| 05-12 14:04:01.787 | B3 | 2 | SKEPTICAL | True | 1 | 48138 | 48118 | 8140 | 203 | 8140 | 2035 | 0 | 147337 | 3/3 | `invoice,cursor` |
| 05-12 14:04:51.320 | B3 | 3 | LITERAL | True | 1 | 49520 | 49507 | 7649 | 207 | 7649 | 1913 | 0 | 149662 | 3/3 | `invoice,cursor` |
| 05-12 14:05:43.062 | B3 | 3 | INFERENTIAL | True | 1 | 51741 | 51730 | 8194 | 207 | 8194 | 2049 | 0 | 149662 | 3/3 | `invoice,cursor` |
| 05-12 14:06:31.456 | B3 | 3 | SKEPTICAL | True | 1 | 48393 | 48382 | 8140 | 203 | 8140 | 2035 | 0 | 149662 | 3/3 | `invoice,cursor` |
| 05-12 14:07:21.409 | C1 | 1 | LITERAL | True | 1 | 49941 | 49931 | 7661 | 215 | 7661 | 1916 | 0 | 154862 | 3/3 | `task-app,decision-loop` |
| 05-12 14:08:09.698 | C1 | 1 | INFERENTIAL | True | 1 | 48288 | 48280 | 8206 | 211 | 8206 | 2052 | 0 | 154862 | 3/3 | `task-app,decision-loop` |
| 05-12 14:09:06.327 | C1 | 1 | SKEPTICAL | True | 1 | 56629 | 56620 | 8152 | 404 | 8152 | 2038 | 0 | 154862 | 3/3 | `task-app,decision-loop` |
| 05-12 14:09:58.223 | C1 | 2 | LITERAL | True | 1 | 51888 | 51876 | 7661 | 215 | 7661 | 1916 | 0 | 158757 | 3/3 | `task-app,decision-loop` |
| 05-12 14:10:47.086 | C1 | 2 | INFERENTIAL | True | 1 | 48862 | 48850 | 8206 | 211 | 8206 | 2052 | 0 | 158757 | 3/3 | `task-app,decision-loop` |
| 05-12 14:11:45.088 | C1 | 2 | SKEPTICAL | True | 1 | 58001 | 57994 | 8152 | 404 | 8152 | 2038 | 0 | 158757 | 3/3 | `task-app,decision-loop` |
| 05-12 14:12:34.013 | C1 | 3 | LITERAL | True | 1 | 48918 | 48909 | 7661 | 215 | 7661 | 1916 | 0 | 146940 | 3/3 | `task-app,decision-loop` |
| 05-12 14:13:19.293 | C1 | 3 | INFERENTIAL | True | 1 | 45278 | 45263 | 8206 | 211 | 8206 | 2052 | 0 | 146940 | 3/3 | `task-app,decision-loop` |
| 05-12 14:14:12.030 | C1 | 3 | SKEPTICAL | True | 1 | 52737 | 52722 | 8152 | 404 | 8152 | 2038 | 0 | 146940 | 3/3 | `task-app,decision-loop` |
| 05-12 14:14:58.787 | C2 | 1 | LITERAL | True | 1 | 46746 | 46738 | 7651 | 215 | 7651 | 1913 | 0 | 137055 | 3/3 | `task-app,decision-loop` |
| 05-12 14:15:43.905 | C2 | 1 | INFERENTIAL | True | 1 | 45117 | 45105 | 8196 | 211 | 8196 | 2049 | 0 | 137055 | 3/3 | `task-app,decision-loop` |
| 05-12 14:16:29.092 | C2 | 1 | SKEPTICAL | True | 1 | 45186 | 45170 | 8142 | 211 | 8142 | 2036 | 0 | 137055 | 3/3 | `task-app,decision-loop` |
| 05-12 14:17:16.107 | C2 | 2 | LITERAL | True | 1 | 47004 | 46991 | 7651 | 215 | 7651 | 1913 | 0 | 137682 | 3/3 | `task-app,decision-loop` |
| 05-12 14:18:01.382 | C2 | 2 | INFERENTIAL | True | 1 | 45273 | 45259 | 8196 | 211 | 8196 | 2049 | 0 | 137682 | 3/3 | `task-app,decision-loop` |
| 05-12 14:18:46.784 | C2 | 2 | SKEPTICAL | True | 1 | 45401 | 45390 | 8142 | 211 | 8142 | 2036 | 0 | 137682 | 3/3 | `task-app,decision-loop` |
| 05-12 14:19:33.738 | C2 | 3 | LITERAL | True | 1 | 46952 | 46949 | 7651 | 215 | 7651 | 1913 | 0 | 137249 | 3/3 | `task-app,decision-loop` |
| 05-12 14:20:18.823 | C2 | 3 | INFERENTIAL | True | 1 | 45085 | 45082 | 8196 | 211 | 8196 | 2049 | 0 | 137249 | 3/3 | `task-app,decision-loop` |
| 05-12 14:21:04.035 | C2 | 3 | SKEPTICAL | True | 1 | 45211 | 45208 | 8142 | 211 | 8142 | 2036 | 0 | 137249 | 3/3 | `task-app,decision-loop` |
| 05-12 14:21:50.447 | C3 | 1 | LITERAL | True | 1 | 46405 | 46395 | 7643 | 206 | 7643 | 1911 | 0 | 135934 | 3/3 | `comparison-table` |
| 05-12 14:22:34.957 | C3 | 1 | INFERENTIAL | True | 1 | 44509 | 44498 | 8188 | 202 | 8188 | 2047 | 0 | 135934 | 3/3 | `comparison-table` |
| 05-12 14:23:19.971 | C3 | 1 | SKEPTICAL | True | 1 | 45014 | 45003 | 8134 | 202 | 8134 | 2034 | 0 | 135934 | 3/3 | `comparison-table` |
| 05-12 14:24:06.603 | C3 | 2 | LITERAL | True | 1 | 46621 | 46608 | 7643 | 206 | 7643 | 1911 | 0 | 135935 | 3/3 | `comparison-table` |
| 05-12 14:24:51.463 | C3 | 2 | INFERENTIAL | True | 1 | 44860 | 44853 | 8188 | 202 | 8188 | 2047 | 0 | 135935 | 3/3 | `comparison-table` |
| 05-12 14:25:35.915 | C3 | 2 | SKEPTICAL | True | 1 | 44451 | 44443 | 8134 | 202 | 8134 | 2034 | 0 | 135935 | 3/3 | `comparison-table` |

## SttAAudioPlumbingTest — raw events (7)

| label | values |
|---|---|
| config | 05-12 12:36:50.382 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · CPU · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:37:02.241 · 11852 |
| send | 05-12 12:37:19.146 · 16864 · 2 · 120 |
| send | 05-12 12:37:34.734 · 15450 · 2 · 123 |
| pathway | 05-12 12:37:34.743 · AudioFile · OK ·  — "Stand up ran long again. I was fine before it, then completely flattened by 11 in the launch dock and just stared at it." · "Stand up ran long again. I was fine before it, then completely flattened by 11 i… |
| pathway | 05-12 12:37:34.743 · AudioBytes · FAILED ·  — LiteRtLmJniException: Failed to call nativeSendMessage: INTERNAL: Failed to initialize miniaudio decoder, error code: -10 · LiteRtLmJniException: Failed to call nativeSendMessage: INTERNAL: Failed to i… |
| pathway | 05-12 12:37:34.743 · TempWAV · OK ·  — "Stand up ran long again. I was fine before it, then completely flattened by 11:00 in the launch dock and just stared at " · "Stand up ran long again. I was fine before it, then completely flattened by 11:00 … |

## LiteRtLmTextSmokeTest — raw events (3)

| label | values |
|---|---|
| config | 05-12 12:37:43.411 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · off · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:37:54.545 · 11119 |
| gen | 05-12 12:37:56.295 · 1748 · 32 · 2 |

## LiteRtLmStreamingTextSmokeTest — raw events (8)

| label | values |
|---|---|
| config | 05-12 12:38:03.315 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · off · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:38:13.980 · 10658 |
| stream | 05-12 12:38:16.041 · 2045 · 68 · 22 |
| chunk | 05-12 12:38:16.046 · 0 · 6 · Autumn |
| chunk | 05-12 12:38:16.046 · 1 · 8 ·  follows |
| chunk | 05-12 12:38:16.046 · 2 · 7 ·  summer |
| chunk | 05-12 12:38:16.046 · 3 · 1 · . |
| summary | 05-12 12:38:16.046 · Cpu · 4 · 1693 · 2062 · 22 |

## PersonaToneSmokeTest — raw events (8)

| label | values |
|---|---|
| config | 05-12 12:38:23.265 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · off · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:38:33.839 · 10562 |
| gen | 05-12 12:38:53.071 · 19226 · 3377 · 173 |
| gen | 05-12 12:39:14.132 · 21059 · 4007 · 120 |
| gen | 05-12 12:39:35.430 · 21297 · 3559 · 122 |
| persona | 05-12 12:39:35.431 · WITNESS |
| persona | 05-12 12:39:35.431 · HARDASS |
| persona | 05-12 12:39:35.431 · EDITOR |

## PerCapturePersonaSmokeTest — raw events (11)

| label | values |
|---|---|
| config | 05-12 12:39:43.736 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · CPU · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:39:59.851 · 16084 |
| send | 05-12 12:40:42.085 · 42126 · 2 · 266 |
| fg | 05-12 12:40:42.086 · WITNESS · 42205 · 266 |
| persona_followup | 05-12 12:40:42.097 · WITNESS · 42205 |
| send | 05-12 12:41:24.247 · 42143 · 2 · 269 |
| fg | 05-12 12:41:24.248 · HARDASS · 42149 · 269 |
| persona_followup | 05-12 12:41:24.248 · HARDASS · 42149 |
| send | 05-12 12:42:07.450 · 43194 · 2 · 278 |
| fg | 05-12 12:42:07.456 · EDITOR · 43206 · 278 |
| persona_followup | 05-12 12:42:07.457 · EDITOR · 43206 |

## GoblinHoursAddendumSmokeTest — raw events (7)

| label | values |
|---|---|
| config | 05-12 12:42:16.566 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · CPU · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:42:33.433 · 16838 |
| send | 05-12 12:43:17.984 · 44489 · 2 · 274 |
| fg | 05-12 12:43:17.985 · WITNESS · 44543 · 274 |
| send | 05-12 12:44:00.778 · 42780 · 2 · 266 |
| fg | 05-12 12:44:00.779 · WITNESS · 42786 · 266 |
| summary | 05-12 12:44:00.779 · 100 · 96 · -4 |

## PatternEngineSmokeTest — raw events (70)

| label | values |
|---|---|
| p3start | 05-12 12:44:09.534 · Cpu · 6 |
| config | 05-12 12:44:09.538 · /data/local/tmp/gemma-4-E4B-it.litertlm · CPU · off · off · 4096 · topK=1,topP=1.0,temp=0.0,seed=42 |
| init | 05-12 12:44:25.861 · 16304 |
| compose | 05-12 12:44:25.896 · LITERAL · 7674 · 1919 · 0 |
| gen | 05-12 12:45:10.566 · 44670 · 7674 · 223 |
| lens_done | 05-12 12:45:10.567 · LITERAL · true · 1 · 44676 |
| compose | 05-12 12:45:10.573 · INFERENTIAL · 8219 · 2055 · 0 |
| gen | 05-12 12:45:53.076 · 42502 · 8219 · 206 |
| lens_done | 05-12 12:45:53.077 · INFERENTIAL · true · 1 · 42509 |
| compose | 05-12 12:45:53.098 · SKEPTICAL · 8165 · 2042 · 0 |
| gen | 05-12 12:46:46.882 · 53783 · 8165 · 401 |
| lens_done | 05-12 12:46:46.890 · SKEPTICAL · true · 1 · 53813 |
| extract_done | 05-12 12:46:46.898 · 3 · 3 · 141007 |
| entry_done | 05-12 12:46:46.920 · A1 · audit · 141007 |
| compose | 05-12 12:46:46.941 · LITERAL · 7672 · 1918 · 0 |
| gen | 05-12 12:47:34.237 · 47295 · 7672 · 226 |
| lens_done | 05-12 12:47:34.239 · LITERAL · true · 1 · 47304 |
| compose | 05-12 12:47:34.244 · INFERENTIAL · 8217 · 2055 · 0 |
| gen | 05-12 12:48:18.521 · 44276 · 8217 · 207 |
| lens_done | 05-12 12:48:18.522 · INFERENTIAL · true · 1 · 44282 |
| compose | 05-12 12:48:18.524 · SKEPTICAL · 8163 · 2041 · 0 |
| gen | 05-12 12:49:06.437 · 47912 · 8163 · 332 |
| lens_done | 05-12 12:49:06.438 · SKEPTICAL · true · 1 · 47915 |
| extract_done | 05-12 12:49:06.438 · 3 · 3 · 139503 |
| entry_done | 05-12 12:49:06.455 · A2 · audit · 139503 |
| compose | 05-12 12:49:06.477 · LITERAL · 7658 · 1915 · 0 |
| gen | 05-12 12:49:54.986 · 48508 · 7658 · 250 |
| lens_done | 05-12 12:49:54.991 · LITERAL · true · 1 · 48519 |
| compose | 05-12 12:49:55.003 · INFERENTIAL · 8203 · 2051 · 0 |
| gen | 05-12 12:50:41.656 · 46651 · 8203 · 264 |
| lens_done | 05-12 12:50:41.663 · INFERENTIAL · true · 1 · 46670 |
| compose | 05-12 12:50:41.673 · SKEPTICAL · 8149 · 2038 · 0 |
| gen | 05-12 12:51:29.475 · 47801 · 8149 · 264 |
| lens_done | 05-12 12:51:29.478 · SKEPTICAL · true · 1 · 47815 |
| extract_done | 05-12 12:51:29.482 · 3 · 3 · 143009 |
| entry_done | 05-12 12:51:29.491 · A3 · audit · 143009 |
| compose | 05-12 12:51:29.501 · LITERAL · 7683 · 1921 · 0 |
| gen | 05-12 12:52:18.434 · 48931 · 7683 · 228 |
| lens_done | 05-12 12:52:18.435 · LITERAL · true · 1 · 48937 |
| compose | 05-12 12:52:18.437 · INFERENTIAL · 8228 · 2057 · 0 |
| gen | 05-12 12:53:04.722 · 46284 · 8228 · 223 |
| lens_done | 05-12 12:53:04.723 · INFERENTIAL · true · 1 · 46287 |
| compose | 05-12 12:53:04.725 · SKEPTICAL · 8174 · 2044 · 0 |
| gen | 05-12 12:53:53.502 · 48777 · 8174 · 224 |
| lens_done | 05-12 12:53:53.503 · SKEPTICAL · true · 1 · 48780 |
| extract_done | 05-12 12:53:53.504 · 3 · 3 · 144007 |
| entry_done | 05-12 12:53:53.518 · D1 · goblin-hours · 144007 |
| compose | 05-12 12:53:53.532 · LITERAL · 7645 · 1912 · 0 |
| gen | 05-12 12:54:41.289 · 47755 · 7645 · 217 |
| lens_done | 05-12 12:54:41.294 · LITERAL · true · 1 · 47768 |
| compose | 05-12 12:54:41.306 · INFERENTIAL · 8190 · 2048 · 0 |
| gen | 05-12 12:55:26.940 · 45633 · 8190 · 213 |
| lens_done | 05-12 12:55:26.941 · INFERENTIAL · true · 1 · 45646 |
| compose | 05-12 12:55:26.943 · SKEPTICAL · 8136 · 2034 · 0 |
| gen | 05-12 12:56:12.869 · 45926 · 8136 · 216 |
| lens_done | 05-12 12:56:12.871 · SKEPTICAL · true · 1 · 45930 |
| extract_done | 05-12 12:56:12.872 · 3 · 3 · 139347 |
| entry_done | 05-12 12:56:12.881 · D2 · decision-spiral · 139347 |
| compose | 05-12 12:56:12.892 · LITERAL · 7661 · 1916 · 0 |
| gen | 05-12 12:57:01.003 · 48109 · 7661 · 225 |
| lens_done | 05-12 12:57:01.005 · LITERAL · true · 1 · 48119 |
| compose | 05-12 12:57:01.006 · INFERENTIAL · 8206 · 2052 · 0 |
| gen | 05-12 12:57:46.634 · 45627 · 8206 · 214 |
| lens_done | 05-12 12:57:46.635 · INFERENTIAL · true · 1 · 45630 |
| compose | 05-12 12:57:46.637 · SKEPTICAL · 8152 · 2038 · 0 |
| gen | 05-12 12:58:33.180 · 46540 · 8152 · 224 |
| lens_done | 05-12 12:58:33.180 · SKEPTICAL · true · 1 · 46545 |
| extract_done | 05-12 12:58:33.181 · 3 · 3 · 140296 |
| entry_done | 05-12 12:58:33.194 · D3 · audit · 140296 |
| pattern | 05-12 12:58:33.217 · 1 · 6 · template_recurrence(4) |

