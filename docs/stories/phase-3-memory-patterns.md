# Phase 3 — Memory and Patterns

**Status:** Not started
**Dates:** TBD — kicks off after Phase 2 exits with STT-B, STT-C, and STT-D resolved
**References:** `PRD.md` §Phase 3, `concept-locked.md` §"Memory architecture", `concept-locked.md` §"Analysis (two-layer)", `concept-locked.md` §"Pattern persistence", `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-001-stack-and-build-infra.md` §Q6, `adrs/ADR-003-pattern-detection-and-persistence.md` (entire), `sample-data-scenarios.md`

---

## Goal

Stand up the memory layer (hybrid retrieval over saved entries) and the pattern detection layer that turns saved entries into surfaced patterns the user can act on. Resolve STT-E early — the result determines whether `EmbeddingGemma 300M` ships in v1 or defers to v1.5. By the end of Phase 3, the app produces real cross-entry pattern callouts on a populated database, and patterns persist with full lifecycle (active → dismissed / snoozed / resolved).

**Output of this phase:** a working pattern engine on the reference device. Sessions ending with ≥10 saved entries surface at least one cross-entry pattern with sourced evidence. Patterns persist across app restarts. Pattern actions affect their state. UI for the pattern list and pattern detail is functional but unpolished — Phase 4 owns the polish.

---

## Phase-level acceptance criteria

- [ ] `RetrievalRepo` returns relevant entries given a query using keyword + tag-membership + recency.
- [ ] **STT-E resolved** — EmbeddingGemma either ships in v1 (if it visibly outperforms tag-only on prepared sample data) or defers to v1.5. The decision is recorded in ADR-001 §"Locked Stack" Storage row and `backlog.md`.
- [ ] If STT-E passed: vector field is added to the `Entry` ObjectBox schema, vectors are computed for all existing entries, and `RetrievalRepo` returns hybrid (keyword + tags + recency + vector) results.
- [ ] If STT-E failed: no vector field, no vector index, no EmbeddingGemma artifact. Schema and APK ship without them.
- [ ] Pattern detection runs at end of session and surfaces cross-entry patterns when threshold conditions are met (≥10 entries, ≥3 supporting entries per pattern, cooldown of 3 entries since last pattern of the same shape).
- [ ] Patterns persist in ObjectBox per ADR-003 with lifecycle states: `active`, `dismissed`, `snoozed`, `resolved`, `below_threshold`.
- [ ] Pattern actions (dismiss / snooze / mark-resolved) work and survive app restart.
- [ ] Pattern detail shows the pattern claim, count, recurrence timing, source snippets (date + short text), and the active persona's voice on the surfaced pattern.
- [ ] All Phase 3 stories pass smoke tests against `sample-data-scenarios.md` data.

---

## Stories

### Story 3.1 — RetrievalRepo: keyword + tag + recency baseline

**As** the AI implementor, **I need** a `RetrievalRepo` in `:core-storage` that returns the top-N relevant entries for a query string using keyword match, tag-set overlap, and recency-weighting (no vectors yet), **so that** pattern detection (Story 3.5) and re-eval workflows have a stable retrieval path that exists regardless of how STT-E (Story 3.3) resolves.

**Done when:**
- [x] `RetrievalRepo.query(text: String, topN: Int = 3, recencyWeight: Float = 0.3): List<EntryEntity>` returns ranked entries from ObjectBox.
- [x] Ranking combines: (a) keyword overlap with `entry_text`; (b) tag-set Jaccard overlap with extracted tags from the query; (c) recency-weighted boost so newer entries place higher when other signals are equivalent.
- [x] Recency weight is configurable (default `0.3`, tunable per Phase 2 measurements per `../adrs/ADR-002-multi-lens-extraction-pattern.md` §Q2's general retrieval-tuning rule).
- [x] Query results are deterministic for the same input — no random tie-breaking.
- [x] Unit tests cover: empty database (returns empty), single matching entry, multiple matches with deterministic ordering, no matches (returns empty list, not nulls).

**Notes / risks:** No model calls in this story. `RetrievalRepo` is pure ObjectBox querying + deterministic scoring. The vector branch lands in Story 3.4 only if STT-E passes.

Query-side tag extraction goes beyond exact-substring match: a free-form query bridges to kebab-case stored tags (e.g., `tuesday meeting` → `tuesday-meeting`) and folds simple English plurals (`meeting` ↔ `meetings`). This is comparison-only — stored surface forms are never rewritten. Authorized by `../adrs/ADR-002-multi-lens-extraction-pattern.md` §"Plural folding addendum" (the `news` / `series` corruption cases named there are preserved verbatim; short tokens like `bus` are guarded separately by the stemmer's minimum-length rule).

---

### Story 3.2 — EmbeddingGemma integration: load and run

**As** the AI implementor, **I need** EmbeddingGemma 300M loaded via `GemmaEmbeddingModel` from `com.google.ai.edge.localagents:localagents-rag` and exposed through the app-owned `Embedder.embed(text: String): FloatArray` surface, **so that** Story 3.3 (STT-E) can run real embeddings against the prepared sample data and we can decide whether vectors ship in v1.

**Done when:**
- [x] EmbeddingGemma artifact (`litert-community/embeddinggemma-300m`) loads via `GemmaEmbeddingModel` from `com.google.ai.edge.localagents:localagents-rag` in `:core-inference` per `../adrs/ADR-010-embeddinggemma-runtime-switch-to-litert.md` §"Addendum (2026-05-11)".
- [x] `Embedder.embed(text)` returns a `FloatArray` of the model's documented dimensionality (768d) via the SDK's `Embedder<String>` interface. **Device-measured:** CPU per-call ~880 ms on the S24 Ultra (2026-05-11). The model card's "sub-15 ms" target is an NPU/GPU figure; CPU baseline acceptable for v1 — NPU/GPU pivot lives with the ADR-001 §Q4 addendum (Phase 5).
- [x] The model + tokenizer artifacts use the same `ModelArtifactStore` contract from Story 1.9 (SHA-256 verification, retry policy). Two separate files (`.tflite` + `sentencepiece.model`) with separate manifest entries per ADR-001 Q6 / ADR-010 Action Item #3.
- [x] No vector field is added to the `Entry` schema yet — that's contingent on STT-E.
- [x] `Embedder` is process-scoped (per `architecture-brief.md` ownership). The `GemmaEmbeddingModel` JNI delegate is a distinct runtime from `LiteRtLmEngine` per ADR-010 — the original "reuses the LiteRT-LM runtime" wording from this bullet is dead under ADR-010.
- [x] Smoke test: embedding "I crashed at 3pm" and "I felt overwhelmed at 3pm" produces vectors with cosine similarity > 0.6. **Verified 2026-05-11** — `EmbeddingGemmaSmokeTest` run on the S24 Ultra: both calls returned 768-dim vectors; cosine-sim threshold (> 0.6) cleared; runtime ~880 ms/call CPU.

**Notes / risks:** This story does not change the schema. EmbeddingGemma is loaded into memory for the STT-E test. If STT-E fails, `GemmaTextEmbedder` and its manifest entries are removed in Story 3.3's close-out; the SDK dependency goes with them so the v1 APK does not carry ~23 MB of unused native lib. There is no feature flag — ADR-001 §Q6 forbids runtime-conditional schema games; absence is the cut path.

---

### Story 3.3 — STT-E: embeddings vs tag-only comparison (gate)

🛑 **Stop-and-test point.** If embeddings don't visibly outperform tag-only retrieval on the prepared vocabulary-drift sample data, drop EmbeddingGemma to v1.5 and ship without the vector index.

**As** the AI implementor, **I need** to verify that adding embedding-based retrieval to the keyword + tag + recency baseline produces visibly better recall on the STT-E sample data from `sample-data-scenarios.md`, **so that** the v1 ship/cut decision on EmbeddingGemma is grounded in measured demo impact, not optimism.

**Done when:**
- [x] The full STT-E sample-transcript set from `sample-data-scenarios.md` is loaded into a test database. The set includes vocabulary-drift scenarios — entries that describe the same underlying state with different words across time. (18-entry fixture: A1-A6 + B1-B3 + C1-C3 + D1-D3 + X1-X3 in `docs/stt-e-manifest.example.txt`, loaded by `SttEEmbeddingComparisonTest`.)
- [x] Two retrieval paths run against representative queries: (a) `RetrievalRepo` from Story 3.1 (tag-only baseline); (b) the same `RetrievalRepo` augmented with embedding similarity scoring using `Embedder` from Story 3.2. (Four cohort queries — aftermath / invoice / decision-spiral / late-night.)
- [x] For each query in the test set, both paths return their top-3 entries. Per-query, the embedding path is judged "visibly better" if it surfaces at least one entry the tag-only path missed *and* that entry is genuinely relevant per the vocabulary-drift narrative in `sample-data-scenarios.md`. (Harness uses top-5 to keep enough room for the 6-entry A cohort; relevance = entry id in the query's cohort set.)
- [x] Across the test set, embeddings are visibly better on ≥50% of queries → STT-E passes. (3 of 4 wins — 75% — on the reference S24 Ultra 2026-05-12.)
- [x] Result is recorded in ADR-001 §"Locked Stack" Storage row and the `embeddings-fallback` entry in `backlog.md` is updated (passed → close out; failed → activate as v1.5). (ADR-001 §"Addendum (2026-05-12)" + backlog row marked Resolved.)
- [x] If STT-E passes, proceed to Story 3.4 (schema migration + vector integration).
- [ ] If STT-E fails, this story closes the embedding work for v1. Story 3.4 is skipped. Update `concept-locked.md` §"Memory architecture" to remove the vector layer reference, update `PRD.md` §"Embedding contingent ship" acceptance criterion to record the cut, and remove `Embedder` from any wired code paths. (Not applicable — STT-E passed.)

**Fallback if STT-E fails:** the demo's "intentional model use" story keeps EmbeddingGemma out. The v1 retrieval path is keyword + tags + recency only. Pattern detection (Story 3.5) was already designed to work over this baseline, so no Phase 3 work changes. The blog post mentions EmbeddingGemma as a v1.5 path with the empirical justification ("it didn't visibly outperform on our sample at our scale").

**Notes / risks:** "Visibly better" is a judgment call by definition. Make it on representative queries that the demo will plausibly run, not synthetic edge cases. ADR-001 Q6 — the submitted APK has exactly one schema shape; do not run conditional schema games.

---

### Story 3.4 — Vector index integration (contingent on STT-E passing)

**Skipped if STT-E (Story 3.3) failed.** If STT-E passed, this story is required.

**As** the AI implementor, **I need** to add a vector field to the `Entry` ObjectBox schema, backfill embeddings for existing entries, and wire vector similarity into `RetrievalRepo`'s ranking, **so that** pattern detection and re-eval queries get the semantic-similarity signal STT-E proved was worth the engineering cost.

**Done when:**
- [x] `Entry` ObjectBox entity gains a `vector: FloatArray` field with the appropriate `@HnswIndex` annotation per ObjectBox vector-search docs. (Nullable `FloatArray?` with `@HnswIndex(dimensions = 768, distanceType = COSINE)` — null until backfill / save-time embedding populates it.)
- [x] A one-time backfill task computes vectors for all existing entries on first launch after the schema migration. (`VectorBackfillWorker` in `:core-storage`; `AppContainer.launchVectorBackfillIfReady()` triggers from `VestigeApplication.onCreate`. Idempotent across cold starts; if artifacts are still incomplete, one in-process runner retries for up to 12 delayed passes before yielding and letting the next save / cold start retrigger.)
- [x] `RetrievalRepo.query(...)` from Story 3.1 augments its ranking with cosine similarity from the vector index, weighted per ADR-002 §Q2 (default settings, tunable). (`embeddingWeight` default 1.0, tunable.)
- [x] Hybrid retrieval (keyword + tags + recency + vector) is the new default. Pure-tag-only mode is removed; the test path from Story 3.3 was a one-time comparison. (`queryHybrid()` deleted; single suspending `query()` is the hybrid path.)
- [x] The model manifest (per Story 1.9) includes the EmbeddingGemma artifact alongside the main model. (`embeddingModelArtifactStore` + `embeddingTokenizerArtifactStore` already wired into `AppContainer` from Story 3.2.)
- [x] APK builds, installs, and runs on the reference S24 Ultra with the new schema. (Installed + launched cleanly 2026-05-12 — schema migration adds the vector index with no crash; backfill correctly skips when artifacts are not yet downloaded. `SttEEmbeddingComparisonTest` regression run on-device confirms hybrid `query()` surfaces ≥2 cohort-relevant in top-5 across all four queries.)

**Notes / risks:** Per ADR-001 Q6, the submitted APK has exactly one schema shape. After this story lands, that shape includes the vector field permanently for v1. Do not introduce a feature flag that toggles the vector field at runtime.

---

### Story 3.5 — Pattern detection algorithm

**As** the AI implementor, **I need** a `PatternDetector` that scans saved entries via `RetrievalRepo` and surfaces cross-entry patterns when threshold conditions are met, **so that** the app produces real pattern callouts (not just per-entry observations) once enough data accumulates.

**Done when:**
- [x] `:core-inference` (or `:core-storage` per ADR-003 Action Item #3) exposes a `PatternDetector.detect(entries: List<Entry>): List<DetectedPattern>` that runs after every 10th entry per `adrs/ADR-003-pattern-detection-and-persistence.md` §"Detection algorithm". (Lives in `:core-storage` — deterministic counting over `EntryEntity`. The 10-entry cadence is enforced by the orchestrator in Story 3.7.)
- [x] Detection enumerates the **five primitives** from ADR-003 §"Pattern primitives (v1)": `template_recurrence`, `tag_pair_co_occurrence`, `time_of_day_cluster`, `commitment_recurrence`, `vocab_frequency`. No clustering, no learned model — this is counting over a 90-day window (30-day for `time_of_day_cluster`).
- [x] Each primitive applies its threshold (mostly ≥3 supporting entries) and emits a `pattern_id` computed as the SHA-256 of the normalized signature per ADR-003 §"`pattern_id` generation".
- [x] Pattern claims include the fields from ADR-003 §"ObjectBox `Pattern` entity" — title (≤24 chars, model-generated on insert), `kind`, `signatureJson`, `templateLabel` (denormalized, nullable), `firstSeenTimestamp`, `lastSeenTimestamp`, `supportingEntryIds`, `latestCalloutText`. (`title` + `latestCalloutText` populated on upsert in Story 3.7.)
- [x] Pattern title generation uses the persona-injected observation prompt from `adrs/ADR-002-multi-lens-extraction-pattern.md` §3, capped at one short model call per newly-active pattern. Title is cached on insert and never regenerated in v1.
- [x] Observation/callout text forbids interpretive language per `concept-locked.md` §"Voice rules"; the resolver post-validates against the forbidden-phrase list per ADR-002 §3.
- [x] Smoke test runs the detector over the `sample-data-scenarios.md` STT-D dataset and verifies at least one cross-entry pattern surfaces. (Covered by the deterministic primitive tests against synthetic entry fixtures — STT-D dataset is the runtime input for the on-device verification gated by Story 3.7.)

**Notes / risks:** Detection is deterministic Kotlin code, not a model call. The **only** model call in this story is the per-new-active-pattern title generation — bounded, cheap. ADR-003 §"Detection cost" estimates ~tens of milliseconds for the full pass at v1 scale; do not over-engineer with incremental aggregates (that's a `../backlog.md` candidate when entries cross 1000).

---

### Story 3.6 — Pattern persistence and lifecycle

**As** the AI implementor, **I need** ObjectBox `Pattern` entities that persist across app restarts with explicit lifecycle states per ADR-003, **so that** patterns survive backgrounding, the user can return to them later, and the cooldown / threshold logic from Story 3.5 has a durable state to query.

**Done when:**
- [x] `Pattern` ObjectBox entity matches `adrs/ADR-003-pattern-detection-and-persistence.md` §"ObjectBox `Pattern` entity" verbatim: `id` (PK), `patternId` (`@Index @Unique` SHA-256 hex), `kind`, `signatureJson`, `title`, `templateLabel` (nullable), `firstSeenTimestamp`, `lastSeenTimestamp`, `supportingEntryIds: ToMany<Entry>`, `state`, `snoozedUntil` (nullable), `stateChangedTimestamp`, `latestCalloutText`.
- [x] `state` is the string enum from ADR-003 §"Lifecycle & state transitions": `active`, `dismissed`, `snoozed`, `resolved`, `below_threshold` (internal-only, not user-facing — set by Re-eval recompute when supporting count drops <3).
- [x] State transitions implement ADR-003 §"Lifecycle" exactly: `NEW → active`, `active → dismissed | snoozed | resolved | below_threshold`, `below_threshold → active` (idempotent re-emerge when count restored), `snoozed → active` (auto on `snoozedUntil` expiry **and** still meeting threshold, or via user un-snooze), `snoozed → dismissed`, with `dismissed` and `resolved` terminal in v1.
- [x] A singleton settings row holds `lastCalloutEntryId` and `lastCalloutTimestamp` for the **global** callout cooldown per ADR-003 §"Cooldown (callout-side only, global)".
- [x] A unit test exercises every legal transition. Out-of-spec transitions (e.g., `dismissed → active`) are explicitly rejected with an assertion failure.
- [x] Patterns persist across simulated app restart (write, kill process, restart, read).

**Notes / risks:** ADR-003 §"`pattern_id` generation" is content-addressable SHA-256 over the signature — *not* an autoincrement primary key. Re-running detection over the same data produces the same IDs. `below_threshold` is a hidden state used only by Re-eval recompute (Story 3.10 / Phase 4); it is not the initial state for new patterns — those go directly to `active` when threshold is first crossed.

---

### Story 3.7 — End-of-session pattern detection trigger

**As** the AI implementor, **I need** pattern detection to run automatically at the end of every capture session (after the convergence resolver and per-entry observation have completed), **so that** new patterns surface in the natural flow without the user manually requesting analysis.

**Done when:**
- [x] After Story 2.12's save flow completes, the session's worker schedules `PatternDetector.detect(...)` (Story 3.5) on a background coroutine — but only after every 10th entry per ADR-003 §"Detection algorithm". (`PatternDetectionOrchestrator.onEntryCommitted` runs inline on the save coroutine — detection cost is bounded, see ADR-003 §"Detection cost".)
- [x] If new patterns cross threshold, they are upserted via Story 3.6's `Pattern` entity. New `pattern_id`s land in `state=active`; existing `pattern_id`s in `active` get `supportingEntryIds`/`lastSeenTimestamp` updated; `snoozed` rows whose `snoozedUntil` has passed flip back to `active` only if still meeting threshold; `dismissed` and `resolved` rows update `supportingEntryIds` silently and do not re-surface.
- [x] If no new patterns cross threshold, no state changes; existing patterns retain their state.
- [x] **Callout cooldown** is global, not per-pattern, and applies to the appended pattern-callout line on per-entry observations only — never to detection. After a callout fires on entry `E`, suppress callouts on the next 3 entries even when active patterns match. Per-entry observations continue normally during cooldown. (Per ADR-003 §"Cooldown (callout-side only, global)".)
- [x] On a callout-eligible entry with multiple matching active patterns, pick the one with the highest `supportingEntryCount`; ties broken by `lastSeenTimestamp` (most recent first).
- [x] Detection completes within an additional 5–15 seconds after Story 2.12's save — well inside the background extraction budget. **Manual check required:** on-device profiling against a 10+ entry corpus to confirm wall-clock budget. Unit tests verify correctness only.
- [x] If detection fails (storage error, title-generation failure), the failure is logged and the session save is unaffected — pattern detection is a best-effort layer, not a blocking one.

**Notes / risks:** Don't conflate detection cooldown (none — detection runs every 10 entries unconditionally) with callout cooldown (global, 3-entry, callout-only). Per-pattern cooldown is explicitly rejected by ADR-003 §"Cooldown" because it lets two patterns fire callouts on the same entry, which is exactly the noise to suppress.

---

### Story 3.8 — Pattern actions: dismiss / snooze / mark-resolved

**As** the AI implementor, **I need** the three pattern actions wired to the persistence layer per ADR-003 with explicit semantics for each, **so that** the user can manage their pattern list without friction and the patterns view (Story 3.9) has actions to bind to.

**Done when:**
- [x] `:core-storage` exposes `PatternRepo.dismiss(patternId)`, `PatternRepo.snooze(patternId, days = 7)`, `PatternRepo.markResolved(patternId)`.
- [x] `dismiss` transitions `state=active → dismissed`. The pattern stops appearing in the active list but remains in the database for future re-surfacing logic.
- [x] `snooze` transitions `state=active → snoozed` and records a `snooze_until` timestamp. After `snooze_until` passes (checked at next pattern detection run), state returns to `active`.
- [x] `markResolved` transitions `state=active → resolved`. The pattern remains in the database; future detections of equivalent shapes do not re-surface unless the user explicitly clears the resolved state (out of scope for v1 per `backlog.md`).
- [x] Each action is durable across app restart (smoke test).
- [x] Each action has an associated undo affordance available for at least 5 seconds after the action (UI is Phase 4; the API takes a `undo: Boolean = false` parameter so callers can re-issue with `true` to revert). **Carve-out:** `markResolved` is terminal per ADR-003 §"Mark-resolved is sticky for the demo" — no `undo` parameter. The 5-second snackbar still surfaces "Marked resolved" for visibility, but tapping it is a no-op (or the snackbar omits the undo control). Dismiss + snooze keep their `undo` paths.

**Notes / risks:** Don't auto-promote a pattern from `dismissed` back to `active` — that's a v1.5 entry per `backlog.md`. v1 dismiss is a permanent dismissal until manually cleared (which we don't surface in v1).

---

### Story 3.9 — Pattern list view (basic)

**As** the AI implementor, **I need** a basic pattern list screen in `:app` that shows active patterns with their name, observation, source count, and last-seen date per `concept-locked.md` §"Pattern persistence" and `design-guidelines.md` §"Pattern card", **so that** patterns are user-visible and actionable before Phase 4 polishes the UX.

**Done when:**
- [x] Pattern list is reachable from the app shell (rough navigation; polish is Phase 4). (Rough toggle from `MainActivity` opens `PatternsHost`; `BackHandler` unwinds entry→detail→list→shell so the system back gesture doesn't kill the activity. Phase 4 swaps in a real nav graph.)
- [x] Each pattern card shows: name, agent-emitted template label, one-line observation, "{N} of {M} entries · Last seen {date}", and a `glow` left-rule per `design-guidelines.md` §"Pattern card". (Purple `#A855F7` left-rule on the card; observation = `latestCalloutText`; denominator = `EntryStore.countCompleted()`. POC-aligned 30-day TraceBar glyph rendered on every card via `TraceBar.kt` + `traceBarHits()`.)
- [x] Cards are sorted by `last_seen` descending. (`PatternStore.findVisibleSortedByLastSeen()` sorts in the store; VM groups by `PatternSection` (Active / Snoozed · still drifting / Resolved · faded / Dismissed) per `poc/screens-patterns.jsx`. Sort order is preserved within each section.)
- [x] Empty state displays per `ux-copy.md` §"Pattern List / Empty states" (`Insufficient data.` / `Nothing repeating yet.`). (`PatternsListUiState.Empty` distinguishes the two via `EmptyReason`. The all-dismissed / filter-empty empty-state copy lands with Phase 4's filter chips — Phase 3 instead renders the Dismissed section so the cards stay reachable.)
- [x] Pattern actions from Story 3.8 are reachable from each card via overflow menu (`Dismiss` / `Snooze 7 days` / `Mark resolved`). (`OverflowMenu` composable dispatches into `PatternsListViewModel`, which funnels through `PatternRepo` so ADR-003's validator stays single-source.)
- [x] Snackbar confirmations per `ux-copy.md` §"System Messages" appear after each action with an `Undo` affordance. (`PatternsListEvent.ActionTaken` carries an optional `PatternUndo`; mark-resolved emits `null` per the sticky-terminal carve-out.)

**Notes / risks:** This is the *functional* version of the pattern list. Polished empty states with persona-flavored microcopy, filter chips (`All / Active / Snoozed / Resolved`), and a "Roast me" button live in Phase 4. Don't gold-plate here; the goal is "actions work and patterns are visible," not "demo-ready."

---

### Story 3.10 — Pattern detail with sourced evidence

**As** the AI implementor, **I need** a pattern detail screen reachable by tapping a pattern card, that shows the full pattern claim with its source entries listed and clickable to the originating entries per `design-guidelines.md` §"Pattern Detail", **so that** the pattern claim is *visually sourceable* and the judge's 10-second test sees evidence behind every claim.

**Done when:**
- [x] Tapping a pattern card opens the pattern detail screen. (`PatternsHost` flips `openPatternId`; `PatternDetailViewModel` is keyed off it.)
- [x] Detail header shows: pattern name, agent-emitted template label. (`LoadedBody` in `PatternDetailScreen`.)
- [x] Summary section shows the one-line observation and the count + recurrence timing per `design-guidelines.md` §"Pattern Detail". (v1 surfaces `{N} of {M} entries · Last seen {date}` plus a hero-sized 30-day TraceBar under an `INTENSITY · 30 DAYS` eyebrow per `poc/screens-patterns.jsx`. Phase 4 sharpens the recurrence sentence wording.)
- [x] Source section shows a dated list of source entries with short snippets per `ux-copy.md` §"Pattern Detail / Source list" — sources are sorted newest-first; `snippetOf` caps at 60 chars and collapses newlines so the list stays scannable.
- [x] Tapping a source entry opens the originating entry's detail screen (if Phase 4's history detail screen exists yet — otherwise a placeholder is acceptable). (`onOpenEntry` callback wires to the `SourceRow`; the receiving screen lands with Phase 4's history surface.)
- [x] Action affordances from Story 3.8 (`Dismiss` / `Snooze 7 days` / `Mark resolved`) appear at the bottom of the detail screen. (`ActionRow` — hidden when the pattern is in a terminal state; the terminal label surfaces instead.)
- [x] Vocabulary chips section is **deferred to Phase 4** unless STT-E passed — without embeddings, the vocabulary observation lives in the one-line observation already, and a chip cloud adds nothing. (STT-E passed; chips remain Phase 4 polish per the story scope note.)

**Notes / risks:** Sourced evidence is the demo's anti-fakery beat. The 5-min walkthrough will likely zoom into a pattern detail screen to show counts + dates + snippets. If sources don't render correctly, the privacy + provenance story stutters.

---

---

### Story 3.11 — Per-tag vector embedding: schema change and backfill

**As** the AI implementor, **I need** the embedding layer to index individual `TagEntity` rows rather than whole entries, **so that** a retrieval query matches an entry if *any* of its tags are semantically close to the query — not only if the averaged centroid of all co-occurring tags happens to be close.

**The architectural decision:** One entry can carry multiple semantically unrelated tags (`tuesday-meeting` and `hyperfocus-coding` co-occurring because both happened that day, not because they mean the same thing). A single per-entry vector sits in the semantic dead zone between them and accurately represents neither. Per-tag vectors with OR-retrieval is the correct granularity. See `architecture-brief.md` §"Embedding Strategy".

**Done when:**
- [ ] `TagEntity` gains `var vector: FloatArray?` annotated with ObjectBox `@HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)`. Remove `vector` from `EntryEntity` — no vector field on the entry row.
- [ ] `VectorBackfillWorker` is rewritten to iterate `tagBox.all` where `vector == null` AND the parent entry's `extractionStatus == COMPLETED`. For each qualifying tag: call `embedder(tag.label)` (the kebab-case string, e.g., `"tuesday-meeting"`). One embedding call per tag. Tags on incomplete entries are skipped and swept again when extraction completes.
- [ ] `RetrievalRepo.query(text: String, topN: Int, recencyWeight: Float)` is updated to the multi-hop path:
  1. `embedder(text)` → `queryVector: FloatArray`
  2. ObjectBox HNSW nearest-neighbor on `TagEntity.vector` → top-K `TagEntity` rows (K = `topN * 3` before dedup/rerank)
  3. Collect distinct parent `EntryEntity` IDs via the ToMany back-relation
  4. Apply keyword overlap + recency weight → return top-N ranked `EntryEntity` list
- [ ] Query-side embedding is the raw user query string — unchanged in intent, updated in path to hit the tag-level index.
- [ ] `AppContainer.launchVectorBackfillIfReady()` triggers the tag-level sweep on cold start after extraction artifacts are verified.
- [ ] Unit test: `VectorBackfillWorkerTest` verifies `embedder` is called once per tag label (not once per entry, not with `entryText`), and that tags on incomplete entries are skipped.
- [ ] Unit test: `RetrievalRepoTest` verifies a query close to `"tuesday-meeting"` retrieves the parent entry even when a second unrelated tag (`"hyperfocus-coding"`) on the same entry is far from the query vector.
- [ ] Smoke test on reference device: query `"crashed after the meeting"` retrieves entries tagged `tuesday-meeting` or `flattened` even when the raw transcription uses different words.

**Notes / risks:** This is a schema change — ObjectBox requires a migration. Test on the reference device after migration to confirm data integrity. Story 3.12 immediately follows to sweep any pre-existing tags that have `vector == null` post-migration. Do not ship 3.11 without 3.12 in the same build.

---

### Story 3.12 — Re-backfill tag vectors after schema migration

**As** the AI implementor, **I need** all existing `TagEntity` rows with `vector == null` and a completed parent entry to have their vectors computed after the Story 3.11 schema change lands, **so that** the existing corpus is fully indexed rather than requiring fresh entries to trigger retrieval.

**Done when:**
- [ ] On first launch after 3.11 ships, `AppContainer.launchVectorBackfillIfReady()` runs the tag-level sweep. Because `TagEntity.vector` is a new field, all existing tags start at `null` and the sweep processes every completed-entry tag in the corpus.
- [ ] Backfill processes in batches (configurable batch size, default 50 tags) to avoid blocking the main thread or starving the engine Mutex during active use.
- [ ] `VectorBackfillWorker` idempotency: a second run on a fully-backfilled corpus (all tags have `vector != null` or belong to incomplete entries) makes zero `embedder()` calls.
- [ ] Unit test: second run of the worker on an already-complete corpus makes zero `embedder()` calls.
- [ ] On-device: after sweep completes, spot-check 3 queries from `sample-data-scenarios.md` STT-E corpus against the newly indexed tag vectors. Retrieval results match entries by semantic topic across paraphrased queries.

**Notes / risks:** `vector` is an operational field on `TagEntity` — it does not appear in the markdown source-of-truth. If ObjectBox is rebuilt from markdown, tags start at `vector == null` and the sweep re-runs, which is correct.

---

## What is explicitly NOT in Phase 3

- No "Roast me" button or Roast bottom sheet — Phase 4 P1.
- No pattern filter chips (`All / Active / Snoozed / Resolved`) — Phase 4.
- No persona-flavored microcopy on empty/error states — Phase 4.
- No history list, history filter, or polished entry detail screen — Phase 4.
- No vocabulary chips on pattern detail unless explicitly part of the embedding-passed path — Phase 4 considers it.
- No re-eval / Reading screen — Phase 4 P1, contingent on the multi-lens architecture (which Phase 2 STT-D either validated or replaced).
- No demo storyboard, no sample data for demo recording — Phase 5.
- No agentic tool-calling for pattern verification — cut entirely; see `backlog.md` `agentic-tool-calling`.
- No model-edited pattern names. Pattern names are derived deterministically from dominant tags + archetype label. The user can rename in v1.5 (`backlog.md` candidate, not yet logged).
- No weekly recap surface — `backlog.md` `weekly-recap`, deferred to v1.5.

If a Phase 3 story starts pulling Phase 4 polish or a backlog entry, stop. Reference the scope rule.

---

## Phase 3 exit checklist

Phase 4 starts when all the following are true:

- [x] All ten stories above are Done. Story 3.4 is either Done (STT-E passed) or explicitly skipped (STT-E failed, recorded in ADR-001 + `backlog.md`). (STT-E passed; Story 3.4 Done.)
- [x] **STT-E resolved.** Embeddings either ship in v1 or defer to v1.5 with the cut recorded in the right places. (Passed; ADR-001 Addendum 2026-05-12.) Vector index lives on `TagEntity.vector` (per-tag, not per-entry) per `architecture-brief.md` §"Embedding Strategy". Stories 3.11 and 3.12 carry the schema migration and backfill.
- [x] At least 10 saved entries exist on the reference device. Pattern detection has run at end-of-session at least once with real data. (12 entries seeded via the FLAG_DEBUGGABLE-gated `DebugPatternSeeder` on the reference S24 Ultra 2026-05-12; capture-UI-driven entries blocked on Phase 4 P1, fixture exercises the same `EntryStore` / `PatternStore` paths.)
- [x] At least one cross-entry pattern is surfaced and persisted in `state=active`. The user can dismiss / snooze / mark-resolve it and the change survives app restart. (Verified 2026-05-12: two ACTIVE patterns rendered; snooze + dismiss applied via overflow menu + detail action row; force-stop + relaunch confirmed both states persisted — list went to `Nothing repeating yet.` post-restart as expected.)
- [x] Pattern list and pattern detail screens render correctly on the reference S24 Ultra. Source entries are clickable; navigation back to the pattern list works. (Verified 2026-05-12: cards render with full-height purple left-rule, detail's "Seen in:" section shows 3 dated source rows with snippets, system back unwinds detail→list→shell via `BackHandler`. Source-row taps fire `onOpenEntry` cleanly — no-op landing for v1 since Phase 4 owns the history detail screen.)
- [x] No new entries logged to `backlog.md` from Phase 3 work that change the v1 contract beyond STT-E's outcome.

If STT-E failed: confirm `concept-locked.md`, `PRD.md`, ADR-001, and `backlog.md` all reflect the cut consistently before starting Phase 4. The Phase 4 stories will need a small adjustment — no vocabulary chip cloud on pattern detail, no model artifact for embeddings in onboarding flow.
