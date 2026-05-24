# Backlog

Deferred features. Source of truth for "this doesn't help us win, deferring to v1.5."

## Decision policy (apply on every feature request)

```
IF request matches an entry below          → defer, cite entry id
ELIF request fails demo-impact test        → defer, append new entry
ELSE                                       → escalate to user
```

`demo-impact-test`: visibly improves the 90s pitch OR the 5-min technical walkthrough.
`authoritative-spec`: `concept-locked.md`, `PRD.md`, `adrs/`.

## Tiers

```
v1.5  : ships post-submission, low-risk additions
v2    : larger scope, new dependencies, or product expansion
stt-N : conditional on stop-and-test outcome (see PRD §"Build philosophy: build first, test at failure zones")
```

## Index

| id | tier | area | why-deferred | unblock-condition |
|---|---|---|---|---|
| `archetype-fields` | v2 | schema | templates became labels in v1; `entry_text` carries the substance | v2 feature needs quantified-self granularity (e.g., "post-meeting crashes after <6h sleep") |
| `reeval-auto-promote` | v1.5 | extraction | v1 keeps user-in-the-loop for accept/reject of all changes | usage data shows convergence patterns; user trust earned across many re-evals |
| `cross-persona-aggregation` | v2 | extraction | personas are output-only (tone); 3-lens already handles convergence | future personas with analytical (not just tonal) differences |
| `user-handle` | v1.5 | identity | fails demo-impact test; v1 anonymity is on-brand | multi-device sync OR shared exports OR user base beyond owner |
| `tts-voice-output` | v2 | output | non-Gemma dep (~3d eng); AI voices flatten sarcasm; not in core demo | demo need surfaces (e.g., users can't read while in dump mode) |
| `audio-retention` | v2 | privacy | v1 default discard-after-extraction = strongest privacy claim | user reports of mis-transcription needing playback verification |
| `video-input` | v2 | input | 3-4d eng; visual-token RAM pressure tight on S24U; dilutes audio headline | demo scenario leveraging environmental context OR more RAM headroom |
| `hotword` | v2 | input | own eng project (Picovoice etc.); battery; permission ask; not in demo | retention data showing tap-to-record friction kills daily use |
| `multi-step-tool-chains` | v2 | extraction | local v1 reliability unproven; demo only needs one auditable single-step beat | proven local reliability on E4B (or successor) AND a real UX need beyond a single tool call |
| `weekly-recap` | v1.5 | patterns | on-demand Roast + per-entry observations cover the same need | retention signal that on-demand isn't enough; users need scheduled nudge |
| `ios-port` | v2 | platform | 17-day deadline, Android-only locked | Android v1 ships + iOS interest + bandwidth |
| `cloud-sync` | v2 | platform | privacy story is the differentiator; cloud compromises it | explicit opt-in encrypted sync (never default) |
| `auto-export` | v1.5 | data | manual export ships in v1; auto adds Settings + scheduling + perms | user reports of data loss because manual export wasn't done |
| `debug-reimport-provenance` | v1.5 | data | v1 export now includes markdown plus `vestige-export.json` with ObjectBox rows, links, vectors, cooldowns, and settings — enough for manual debugging. A real debug re-import still needs id remapping, ObjectBox schema metadata, app/build provenance, model artifact checksums, and prompt/schema hashes. Importer UX does not improve the 90s pitch or 5-min walkthrough | post-submission debugging needs restoring exported state into a dev build; define filename-based relation remapping and provenance fields before implementing importer |
| `multilingual` | v2 | localization | Gemma 4 audio is multilingual; v1 prompts are English-only | v2 release targeting non-English market |
| `notifications` | v1.5 | engagement | notification fatigue conflicts with anti-pushy brand | clear ADHD-specific use case for scheduled reminders |
| `light-theme` | v2 | design | dark mode is on-brand; visual system designed dark-only | user base explicitly requesting it |
| `calendar-health-correlation` | v2 | data | adds perms + integrations + new analytical surface; breaks v1 simplicity | v2 "data sources" expansion release |
| `pattern-charts` | v1.5 | patterns | pure polish; needs charting infrastructure; v1 patterns are textual | post-submission v2 polish window |
| `pattern-cadence-tuning` | v1.5 | patterns | **Added 2026-05-17 — ADR-014.** v1 ships ongoing pattern analysis at an every-3-entries default per ADR-014's lifecycle contract; optimal cadence is a usage-data question (every-3 may be too frequent for battery; every-10 may surface stale patterns) | post-v1 usage telemetry shows 3 is too frequent (battery cost) or too sparse (stale pattern view) |
| ~~`embeddings-fallback`~~ | — | — | **Resolved 2026-05-12 — STT-E passed.** Hybrid (tag + keyword + recency + EmbeddingGemma cosine) beat tag-only on 3 of 4 cohort queries against the 18-entry STT-E corpus on the reference S24 Ultra. EmbeddingGemma ships in v1 per ADR-001 §"Addendum (2026-05-12)". No v1.5 fallback remains. | n/a — closed |
| `mic-perm-resume-recheck` | v1.5 | permissions | Phase 1 shell checks mic permission once at startup via `rememberSaveable`; revoking in Settings and returning leaves UI showing stale "granted" state. Fix is a `LifecycleEventEffect(ON_RESUME)` re-check, but Phase 4 replaces the shell entirely | Phase 4 onboarding UX ships — wire into the real permission gate there |
| ~~`gpu-model-artifact`~~ | — | — | **Resolved 2026-05-10 — wrong premise.** Artifact was GPU-capable; manifest missed `<uses-native-library>` for `libOpenCL.so` + `libvndksupport.so` (Android 12+ namespace). Fix in `AndroidManifest.xml`. Latency record in ADR-001 §Q3 addendum. | n/a — closed |
| `multi-chunk-foreground` | **v1.5 — HIGH** | inference | **Promoted 2026-05-14** from stt-A tier after on-device STT-A round-trip verified end-to-end. v1 `AudioCapture` is hard-capped at 30 s and emits one `isFinal=true` chunk; `ForegroundInference.runForegroundCall` rejects non-final chunks. The >30 s orchestration (stripped-down transcription-only call per intermediate chunk + concatenated transcript-so-far injected on the final chunk per ADR-002 §"For >30s captures") is unwritten. Single-narrative recordings cover the demo's 90 s pitch + 5 min walkthrough; long-dump pathway is the most-asked-for follow-up | Post-submission v1.5 work — the audio cue at 28 s tells the user the cap is firing but does not address the underlying length limit. To reproduce the chunk-boundary fixture, record the `docs/sample-data-scenarios.md` §STT-A "Read as one long capture" script forcing a 30 s split at `[CUT]` (or pre-split into two halves), transcode to PCM_S16LE 16 kHz mono per the STT-A §Q4 device-test record, then drive both halves through whatever multi-chunk orchestration ships at that point. See detail block below |
| `long-capture-duration-format` | v1.5 | capture | Footer/history duration labels intentionally use raw seconds while voice entries are capped at 30 s; once multi-chunk capture lands, `242s` stops being acceptable UI and the compact `4m 02s` style from `docs/ux-copy.md` needs to become real formatting instead of mocked test data | `multi-chunk-foreground` ships or any other change allows completed captures to exceed 30 s |
| ~~`smart-turn-boundaries`~~ | — | — | **Collapsed 2026-05-09** when v1 scoped to single-turn-per-capture (after the STT-B prompt-stuffing pattern produced retention=0.0; the SDK's stateful Conversation path was not measured — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior")). With each tap of record producing a fresh `CaptureSession` and no prior-turn context threaded into the prompt, there is no session boundary to be smart about under v1. | A future revival of multi-turn (post-v1, exercising the SDK stateful path) would re-open this row before Phase 4 history UI lands |
| ~~`parallel-lens-execution-via-clone`~~ | — | — | **Retired 2026-05-16 — created by a mistake; premise then disproven on-device 2026-05-17.** This row only existed because the (now-**deleted**) ADR-009 wrongly concluded `litertlm-android:0.11.0` couldn't do concurrent multi-context inference. A direct AAR bytecode probe found `Engine.createSession()`/`createConversation()` on the pinned 0.11.0, which briefly looked like concurrent multi-context support. On-device STT-F then proved 0.11.0 enforces **one live session per Engine** — a second concurrent `createConversation` throws `FAILED_PRECONDITION` (`stt-results/stt-f-2026-05-17.md`). v1 ships **sequential** 3-lens on one serialized session; ADR-008 is **REVERSED 2026-05-17** (see ADR-008 §Addendum (2026-05-17)) and ADR-002's sequential rule stands. | n/a — retired (concurrent multi-context not SDK-supported; sequential is SDK-enforced) |
| ~~`retrieval-indexed-prefilter`~~ | — | — | **Resolved 2026-05-24 — obsolete.** Would have indexed `RetrievalRepo.query`'s in-memory `entryBox.all` + `tagBox.all` scan, but `RetrievalRepo` was cut 2026-05-24 (see `embedding-retrieval-surface`). No ranked-retrieval surface remains to prefilter. | n/a — closed (RetrievalRepo cut) |
| `vocab-cluster-threshold` | v1.5 | patterns | **Added 2026-05-23.** On the demo seed the `VOCAB_FREQUENCY` (Vocab Drift) pattern does **not** mint — verified on-device 2026-05-23: 5 patterns formed (4 `TEMPORAL_RELATIVE` + 1 `TEMPLATE_RECURRENCE`), **zero vocab**. `EmbeddingClustering` uses `DEFAULT_MAX_COSINE_DISTANCE = 0.30` calibrated on the identical-word "tired × 23" fixture; the real corpus is genuinely drifted multi-word prose embedded as a synthesis string (tags + observations + commitment topic, not the tone word), so exhaustion entries fragment below the `VOCAB_THRESHOLD = 4` cluster floor. Net: embeddings have **no visible surface** in the demo. **RESOLVED 2026-05-24:** repointing `buildEmbeddingText` to the tone word (feeling axis, `vectorSchemaVersion` 1→2) minted it with **no threshold change** — on-device EXTRACT=1 produced `clusterSizes=[6,4,…]` at the unchanged `maxCosine=0.30`, and `Drained Vocab Frequency` is now a live ACTIVE pattern. The 0.30 cut is correct for the tone axis. | — (resolved: axis fix; thresholds unchanged) |
| `embedding-retrieval-surface` | v1.5 | memory | **Added 2026-05-23.** `RetrievalRepo` (keyword + tag + recency + EmbeddingGemma cosine) is implemented and STT-E-validated (`stt-results/stt-e-2026-05-19.md` — hybrid beat tag-only 3/4) but **unwired on the live path**: its only caller `AppContainer.retrieveHistory` is itself never invoked, `CaptureViewModel` passes `retrievedHistory = emptyList()`, and the observation read's recurring context uses deterministic `TemporalHistoryRetrieval` (timestamp/weekday), not embeddings. The one live consumer of `EntryEntity.vector` is `EmbeddingClustering` (`VOCAB_FREQUENCY` / Vocab Drift), which already demonstrates embeddings — so ranked retrieval fails the demo-impact test as-is. Shipping it unwired is dead plumbing per AGENTS.md. **Resolved 2026-05-24 — CUT.** When `buildEmbeddingText` was repointed to the tone word ([[architecture-brief]] §Embedding Strategy addendum), the vector became the feeling axis, making content retrieval incoherent. `RetrievalRepo`, `AppContainer.retrieveHistory`, `RetrievalRepoTest`, and the STT-E gate (`SttEEmbeddingComparisonTest` + `SttEManifest`) were deleted. | — (resolved: cut) |
| ~~`backfill-on-artifact-complete`~~ | — | — | **Resolved 2026-05-12.** `AppContainer.launchVectorBackfillIfReady()` now keeps one bounded in-process drain loop alive: if backlog exists before artifacts are complete, it retries for up to 12 delayed passes (60 s budget at 5 s intervals), then exits cleanly. A later save or cold start retriggers the worker. No Phase 4 download-complete event hook is required for correctness. | n/a — closed |
| `pattern-auto-close` | v1.5 | patterns | PatternEngine detects and updates patterns forward but has no staleness scan; nothing currently transitions a pattern from active → Closed; demo scenario doesn't require auto-removal; v1 user actions are Skip, Drop, and Restart only (Closed is model-detected per ADR-011 UX direction) | post-v1 usage data shows patterns accumulating without removal; or Phase 5 UX audit surfaces the missing lifecycle transition |
| `discard-after-stop` | v2 | capture | **Out of scope per ADR-001 §Q8.** Once STOP fires, the foreground inference call is in flight and not cancellable. Q8 explicitly defers in-flight-call cancellation; streaming would reopen the contract and require a new ADR | A streaming inference path lands AND user research shows accidental-STOP as a real pain point |
| `patterns-stat-ribbon-header` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** POC pattern (`poc/patterns-final.png`) renders a `StatRibbon` of counters (`+{N} HITS THIS WK` / `{N} ACTIVE` / `{N} SNOOZED` / `{N} RESOLVED`) under the Patterns header. Adds visual density but no demo-blocking gap — current build's section dividers already convey active/snoozed/resolved counts adequately for the 90 s pitch | Post-submission polish window OR walkthrough script explicitly anchors on weekly-hit count |
| `pattern-card-bigstat-of-n` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** POC pattern cards render `BigStat` "of N" affordance at top-right (e.g. `4 / OF 12`). Cosmetic addition — pattern card still reads the entry count correctly via bottom-row metadata; the `BigStat` slot is decoration, not information the user lacks | Post-submission polish window |
| `pattern-detail-energy-stats` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** POC Pattern Detail (`poc/pattern-detail-final.png`) renders `StatRibbon` cells like `100% ON TUES` / `6W STREAK` / `~85m TO CRASH`. These require `EnergyDescriptor` data wiring per Story 2.13 — gated by upstream work, not by UI capacity | Story 2.13 `EnergyDescriptor` plumbing lands AND post-submission polish window |
| `pattern-detail-trace-peak` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** POC Pattern Detail trace strip renders a `▼` peak marker above the highest-intensity bar. Decorative — the bars themselves already convey the same information | Post-submission polish window |
| `goblin-hours-addendum-persona-aware` | v1.5 | inference | **Removed 2026-05-17.** The single shared `goblin-hours-addendum.txt` flattened persona divergence at the 3am capture beat — the one moment persona voice should be most distinct (full-suite test: near-zero night/day delta). The addendum, its `ForegroundInference` injection + `isGoblinHours()`/`GOBLIN_HOURS_*`, and the `zoneId` seam were **deleted entirely** for v1 (commit `69784a60`). The ADR-002 §625 plan to make it persona-aware (three voice-matched files) was **cancelled, not deferred** — see ADR-002 §Addendum (2026-05-17). This row is a speculative v1.5 marker only; the `TemplateLabel.GOBLIN_HOURS` label + `time_of_day_cluster` pattern are unaffected. | Speculative — only if post-v1 multi-turn/voice work revisits the foreground follow-up; a revival is a fresh design (no `isGoblinHours()` branch exists) |
| `tag-chip-primitive-split` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** Entry Detail tags (`battery-died`, `fell-out`, `meeting`) and Pattern Detail action buttons (`Drop`, `Skip`) both render via the `Pill` primitive, creating a passive-vs-interactive semantic conflict at the call sites. Real but marginal demo risk — tags sit in a clearly subordinate position (bottom of Entry Detail, smaller scale). New `Chip` primitive is new infrastructure for low demo lift | Post-submission infra work OR user feedback that tags are mistaken for actions |
| `recording-screen-density` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** Recording-screen layout (`Screenshot_20260516_220422_Vestige.png`) has ~50% dead vertical space between the chunk progress bar and the `LEVEL · LIVE` audio meter. Active surface during the 30 s record window — functional, just sparse. Reorder is cheap but not demo-blocking | Post-submission polish window |
| `settings-back-affordance` | v1.5 | design | **Deferred 2026-05-17 (Story 4.15 triage).** Settings ships without an explicit `← BACK` link — only predictive-back gesture exits the surface. Discoverability concern for non-Android-power users; not a 90 s pitch or 5 min walkthrough blocker (judge uses predictive-back or system gesture) | First-run usability data shows users stranded on Settings, or Settings is added to the demo walkthrough script |
| `mtp-latency-ab` | v1.5 | inference | **Deferred 2026-05-17 — non-gating measurement.** Story 2.15's MTP fore/background latency A/B decides nothing (MTP ships enabled regardless of the ratio per the story's own rule), is not demo-visible, and correctness is already pinned by `LiteRtLmEngineTest`. Measuring needs two invasive builds (no runtime MTP toggle). Pure verification debt — fails the demo-impact test | A v1.5 perf-tuning pass that actually acts on the number, or a `macrobenchmark` module if decode latency becomes a real regression surface |
| `archetype-template-labeling` | ~~v1.5~~ done | extraction | **RESOLVED 2026-05-24.** `template_label` is now model-emitted per lens and convergence-voted — every lens emits the archetype directly, so it resolves CONSENSUS rather than the discarded-Inferential-CANDIDATE failure below. The redesign the unblock-condition called for shipped (the model-emitted contract + ADR-002 §Addendum 2026-05-24); the tag→archetype `TemplateLabeler` path is deleted, the label is live, not inert. On-device STT-H 2026-05-24: AUDIT 8/12 → 4/12, six distinct archetypes. Historical root cause retained below. — **Structurally broken on realistic input (traced 2026-05-17).** `TemplateLabeler` only consumes CONSENSUS fields; CONSENSUS requires ≥2-of-3 lens agreement; the archetype triggers (`energy_descriptor=="crashed"`, `state_shift`, tags `tunnel-exit`/`decision-spiral`/`stuck`/`late-night`) are all inferences only the Inferential lens emits, which resolves to CANDIDATE and is discarded. Every realistic entry falls through to `AUDIT`; the feature only produces a real label when the user speaks the exact internal vocabulary (Literal then also emits it → 2-lens agreement). Code retained but inert; Story 4.16 removed untrusted labels from UI. The Pattern List eyebrow slot currently uses `pattern.kind` and can accept a trusted label again after this redesign. See detail block | A redesigned multi-lens contract: label demoted from canonical pattern-grouping key to a display-only single-lens hint (Inferential-sourced CANDIDATE accepted), superseding the ADR-002 resolver-contract coupling. New ADR required |
| `labeler-prompt-tightening` | ~~v1.5~~ done | extraction | **RESOLVED 2026-05-24.** The prompt was tightened (`output-schema.txt` reworded off the `audit` default so the lens commits to an archetype) and the widened tag-sets were **deleted entirely** — `TemplateLabeler` is now a goblin-window clock predicate, not a tag→archetype compensator. On-device STT-H 2026-05-24 confirms the narrow behavior survives extraction (six distinct archetypes, AUDIT 8/12 → 4/12). The `PatternDetector` AUDIT filter noted below remains correct. See ADR-002 §Addendum (2026-05-24). Historical context: — **Added 2026-05-20.** `TemplateLabeler.DECISION_SPIRAL_TAGS` and `AFTERMATH_TAGS` were widened beyond the AGENTS.md "narrow on purpose. STT-C tag stability will expand or trim this list with measured evidence" rule so demo labels would land pre-STT-C. The real fix is prompt tightening so the lens output emits the existing narrow vocabulary instead of compensating in the post-processor. `PatternDetector` also silently filters `TemplateLabel.AUDIT` from template-recurrence + tag-pair detection because AUDIT is the fallback bucket, not a pattern shape — that filter is correct, but it masks the prompt quality. | STT-C tag-stability measurement, or a Phase-6 prompt revision pass with `DemoExamplesSmokeTest` proving the narrow vocabulary survives extraction |

## Detail blocks

Only items where the index row drops information needed to disambiguate.

### `archetype-template-labeling` (RESOLVED 2026-05-24 — model-emitted + convergence-voted)

```
root-cause (2026-05-17, traced through ConvergenceResolver.kt + TemplateLabeler.kt):
  - TemplateLabeler.isLoadBearing() accepts only CONSENSUS / CONSENSUS_WITH_CONFLICT;
    CANDIDATE and AMBIGUOUS fields are discarded before label selection.
  - DefaultConvergenceResolver mints CONSENSUS only on >=2-of-3 lens agreement
    (one lens alone -> CANDIDATE; no majority -> AMBIGUOUS).
  - The three lenses cannot corroborate an archetype signal from natural language:
      Literal     — strict, "null is a real answer"; emits the trigger token only
                    if the user said the literal word.
      Inferential — the only lens that infers archetype tags / energy=crashed /
                    state_shift from behavior; but Inferential-only is CANDIDATE
                    by documented contract (inferential.txt).
      Skeptical   — adversarial; emits `flags`, not a second corroborating value.
  - Net: every archetype trigger is single-lens (Inferential) -> CANDIDATE ->
    discarded -> entry falls through to AUDIT. A non-AUDIT label only appears
    when the user speaks the exact internal vocabulary, at which point Literal
    ALSO emits it and 2-lens agreement mints CONSENSUS. That is the
    keyword-stuffed fixture, not a real test.
why-not-an-easy-fix:
  Accepting Inferential-sourced CANDIDATE in TemplateLabeler is ~3 lines but
  contradicts the documented contract ("the template label feeds pattern
  grouping" — concept-locked.md / ADR-002). Pattern grouping would then key off
  single-lens guesses — an ADR-level pivot, not a token tweak. It also does not
  fix AFTERMATH, which needs energy_descriptor to normalize to the exact string
  "crashed" from an Inferential guess (fragile, untestable without keyword
  stuffing).
v1-decision:
  Story 4.16 landed the UI yank for untrusted labels. Code (`TemplateLabeler`,
  `template_label` field, `BackgroundExtractionResult.templateLabel`) stays
  inert because removing it mid-phase is a cross-cutting change with no demo
  value. Pattern cards keep the POC eyebrow slot, but v1 binds it to stored
  `pattern.kind`; a redesigned trusted template label can reclaim that slot.
  Patterns are unaffected: they group on tags / recurrence, not the label.
unblock-condition:
  New ADR superseding the ADR-002 resolver coupling: label demoted to a
  display-only hint, single-lens (Inferential CANDIDATE) accepted, decoupled
  from canonical pattern grouping. Out of v1 scope (no quick fixes — AGENTS.md).
spec-ref: concept-locked.md §"Templates"; adrs/ADR-002-multi-lens-extraction-pattern.md
          §"Convergence Resolver Contract"; docs/stories/phase-2-core-loop.md §Story 2.10
```

**Resolution (2026-05-24).** The root cause above was the tag→archetype `TemplateLabeler` path: it
derived the label from tags the Inferential lens alone could infer, which resolved CANDIDATE and was
discarded → AUDIT. That path no longer exists. `template_label` is a first-class field every lens
emits directly, so it converges to CONSENSUS like any other field; the model commits to a specific
archetype (output-schema reworded off the `audit` default), and `TemplateLabeler` is reduced to the
`isGoblinHours(capturedAt)` clock predicate. The "accept Inferential CANDIDATE" / "new ADR demoting
the label" unblock path is moot — the label is trusted because it's convergence-voted, not because a
single lens was promoted. On-device STT-H 2026-05-24: six distinct archetypes, AUDIT 8/12 → 4/12. See
ADR-002 §Addendum (2026-05-24).

### `multi-chunk-foreground` (high priority — first v1.5 input-path work)

```
why-high-pri:
  STT-A verified end-to-end on 2026-05-14 (REC → 30s cap → transcript + persona follow-up).
  The 30s hard cap is now the most-visible product limit. Audio cue at 28s informs the user
  but does not relieve the limit. Multi-chunk is the unblock; everything else on the input
  path (streaming, retries, longer captures) builds on this.

mechanism:
  Per ADR-002 §"For >30s captures":
  - Intermediate chunks: stripped-down transcription-only call (no persona follow-up); the
    foreground inference call must accept `isFinal=false` and return transcript text only.
  - Final chunk: the running concatenated transcript-so-far is injected into the prompt
    alongside the final audio chunk; the model returns the full transcription + the single
    persona follow-up for the entire session.
  - `CaptureSession.recordTranscription` is called once with the assembled transcript,
    not per chunk — the single-turn lifecycle (ADR-005) is preserved.

audio-cue-behavior-during-chunking:
  Per-chunk cap cue fires at the same 28s pre-warn threshold. Multi-chunk sessions hear
  the cue at 28s of each chunk window, not at 28s of the cumulative session. Verify the
  cue's one-shot flag resets at chunk boundaries, not only at session start.

ui-state-during-chunking:
  Recording state stays through chunk transitions. ChunkProgressBar resets per chunk; the
  elapsed timer in TimerHeader continues cumulative (user perception is "how long have I
  been talking", not "how full is this chunk").

discard-during-multi-chunk:
  Per ADR-001 §Q8: tapping discard on the current chunk discards the in-flight chunk audio
  AND the accumulated transcription from prior chunks in the same session AND the entire
  CaptureSession. Session terminates DISCARDED. No partial save.

ux-during-stop-on-non-final-chunk:
  Tapping STOP mid-multi-chunk-session: the current chunk closes (returns up the flow),
  the orchestrator detects no further chunks pending, and routes the final-chunk path
  (transcript-so-far injection) with whatever audio the current chunk has captured.
  Even if that chunk is only 4 seconds, it's the "final" for orchestrator purposes.

what-this-does-NOT-include:
  - Streaming token output. ADR-002 §Q1 — separate gate.
  - Audio retention. Backlog `audio-retention`.
  - Foreground call cancellation after STOP. Backlog `discard-after-stop`.

unblock-condition:
  Post-submission v1.5 work. The demo scenario stays within 30 s; the long-dump pathway
  is the first thing users will request after the hackathon.

spec-ref:
  - docs/adrs/ADR-001-stack-and-build-infra.md §Q4 (audio chunking)
  - docs/adrs/ADR-002-multi-lens-extraction-pattern.md §"For >30s captures"
  - docs/sample-data-scenarios.md §STT-A chunk-boundary script
  - core-inference/.../AudioCapture.kt (`tryBuildCapChunk` currently drops past-cap chunks with WARN)
```

### `pattern-auto-close`

```
mechanism-needed:
  After each extraction, PatternEngine runs a staleness check on all active patterns.
  If a pattern has not matched any new entry in the last N days (v1.5 proposal: 30 days),
  it transitions to CLOSED state automatically. No user action triggers this.

user-visible-on-close:
  Pattern detail shows: "Closed {date}. No new entries matched in {N} days."
  State badge: CLOSED · DONE (per docs/ux-copy.md §Pattern List section headers)
  No snackbar — state change is silent; visible on next list load.

user-actions-in-closed-state:
  Read-only except Restart. Skip and Drop are not available once a pattern is Closed.

why-not-v1:
  PatternEngine in v1 is forward-only — it detects and updates confidence on new entries.
  A staleness check requires either (a) a scheduled WorkManager pass or (b) a post-extraction
  hook that scans all active patterns for last-matched date. Neither is in the v1 story budget.
  The demo scenario uses a fresh corpus where patterns are actively firing; no stale patterns
  accumulate during the pitch window.

design-ref: docs/ux-copy.md §Pattern List, §Pattern Detail; docs/stories/phase-4-ux-surface.md
```

### `archetype-fields`

```
fields-deferred:
  - state_before, onset, last_food_caffeine, last_sleep, intent_now
  - focus_subject, focus_duration, ignored_during_focus, output_produced
  - stuck_task, resistance_type, time_stuck, external_pressure, last_attempt
  - decision_looped, iterations, stakes, decision_missing, time_pressure
  - spiral_topic, bedtime_delta, body_state
extraction-strategy: re-extract on demand from entry_text in v2; no schema migration needed
spec-ref: concept-locked.md §Schema; PRD.md §"Future Considerations"
```

### ~~`embeddings-fallback`~~ — resolved 2026-05-12

```
outcome: STT-E PASSED on 2026-05-12 (3 of 4 cohort queries, threshold 50%)
ships-in-v1: EmbeddingGemma 300M + ObjectBox HNSW vector index on EntryEntity.vector
spec-ref: adrs/ADR-001-stack-and-build-infra.md §"Addendum (2026-05-12) — STT-E passed";
          docs/stories/phase-3-memory-patterns.md §Story 3.4
```

### `vocab-cluster-threshold`

```
symptom: no VOCAB_FREQUENCY pattern on the demo seed (verified on-device 2026-05-23: 5 patterns =
         4 TEMPORAL_RELATIVE + 1 TEMPLATE_RECURRENCE, zero vocab). Embeddings have no visible surface.
root-cause-hypothesis: EmbeddingClustering DEFAULT_MAX_COSINE_DISTANCE=0.30 was calibrated on the
         identical-word "tired × 23" fixture; the demo corpus is drifted multi-word prose embedded as a
         synthesis string (tags+observations+commitment topic, NOT the tone word) → pairwise cosine
         distance > 0.30 → entries fragment below VOCAB_THRESHOLD=4, so no cluster mints.
confirmed-present: embeddinggemma-300M .tflite + sentencepiece.model on device; vocabularyWord emitted
         (e.g. "resigned"). So the gap is clustering, not a missing model. (vector backfill unconfirmed.)
axis-fix-landed (2026-05-24): buildEmbeddingText repointed from synthesis string → tone word
         (EntryEntity.vocabularyWord, trimmed+lowercased; null/blank → "" so toneless entries are excluded,
         never embedded as "null"). vectorSchemaVersion 1→2 forces re-backfill. detectVocab logs
         candidates + clusterSizes + nnDistances. EmbeddingClustering.nearestNeighborDistances() added.
resolved (2026-05-24): the axis fix ALONE minted it — no threshold change needed. On-device EXTRACT=1
         re-seed, 18 toned entries: clusterSizes=[6,4,2,1,1,1,1,1,1] at the unchanged maxCosine=0.30,
         nnDistances showed repeated/synonymous tones at ~0–0.21 (well inside 0.30) and distinct tones at
         0.31+. "Drained Vocab Frequency" is now a live ACTIVE pattern on the scoreboard. The 0.30 cut,
         calibrated on identical words, is correct for the tone axis too. DEFAULT_MAX_COSINE_DISTANCE /
         VOCAB_THRESHOLD / MIN_SUPPORTING_ENTRIES all UNCHANGED.
spec-ref: core-storage/EmbeddingClustering.kt; core-storage/PatternDetector.kt §detectVocab; EmbeddingText.kt
```

### `embedding-retrieval-surface`

```
state: RetrievalRepo implemented + STT-E-validated (stt-results/stt-e-2026-05-19.md: hybrid 3/4 wins);
       UNWIRED on the live path.
unwired-evidence: AppContainer.retrieveHistory() has zero callers; CaptureViewModel passes
                  retrievedHistory=emptyList(); BackgroundExtractionSaveFlow uses deterministic
                  TemporalHistoryRetrieval + PatternCandidates, NOT RetrievalRepo.
live-embedding-consumer: EmbeddingClustering → VOCAB_FREQUENCY pattern → Vocab Drift screen
                  (this is the only thing reading EntryEntity.vector at runtime).
resolution (2026-05-24): CUT (option C). Repointing buildEmbeddingText to the tone word made the
         vector the feeling axis, so content retrieval is incoherent against it. Deleted RetrievalRepo,
         RetrievalRepoTest, AppContainer.retrieveHistory (+ FOREGROUND_HISTORY_TOP_N), and the STT-E gate
         (SttEEmbeddingComparisonTest + SttEManifest). EntryEntity.vector now serves vocab clustering only.
         Reviving content retrieval later needs its OWN second content vector — not this one.
note: stt-results/stt-e-2026-05-19.md stays as the historical measurement record; the code it validated
         is gone, but the finding (content embeddings beat tag-only) was real at the time.
```

### `tts-voice-output`

```
candidate-engine: Kokoro (or similar on-device TTS)
scope-estimate: ~3d eng (TTS pipeline + persona-voice mapping + audio playback)
brand-risk: AI TTS voices flatten sarcasm; persona bite reads sharper as text
```

### `audio-retention`

```
default-state-if-shipped: OFF (opt-in only)
retention-window-default: 7 days
required-plumbing: Settings UI, ObjectBox metadata, WorkManager expiry job, encryption-at-rest
scope-estimate: ~1d uncompressed, ~1.5d with Opus compression
```

### `cloud-sync`

```
hard-constraint: opt-in only, encrypted, never default
forbidden-in-v1: any cloud touchpoint, any analytics, any RemoteConfig (per adrs/ADR-001 §Q7)
```

### `seed-data-prepopulation`

```
tier: v1.5 (demo tooling)
flagged: 2026-05-22
problem: the dev seed (DebugPatternSeeder writes PENDING rows; run_extraction=true runs
         recovery extraction) is not returning responses on-device this run — seeded entries
         land without resolved fields, so detail surfaces (vocab, promises, three-lens read)
         and the vocab/template patterns stay empty.
deferred-by: user (2026-05-22) — do NOT change the seed path now.
future-task: bake extraction output (resolved fields + lens receipts) for the demo set so the
             walkthrough opens populated entries without waiting on a live model run.
```
