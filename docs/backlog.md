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
| `multilingual` | v2 | localization | Gemma 4 audio is multilingual; v1 prompts are English-only | v2 release targeting non-English market |
| `notifications` | v1.5 | engagement | notification fatigue conflicts with anti-pushy brand | clear ADHD-specific use case for scheduled reminders |
| `light-theme` | v2 | design | dark mode is on-brand; visual system designed dark-only | user base explicitly requesting it |
| `calendar-health-correlation` | v2 | data | adds perms + integrations + new analytical surface; breaks v1 simplicity | v2 "data sources" expansion release |
| `pattern-charts` | v1.5 | patterns | pure polish; needs charting infrastructure; v1 patterns are textual | post-submission v2 polish window |
| ~~`embeddings-fallback`~~ | — | — | **Resolved 2026-05-12 — STT-E passed.** Hybrid (tag + keyword + recency + EmbeddingGemma cosine) beat tag-only on 3 of 4 cohort queries against the 18-entry STT-E corpus on the reference S24 Ultra. EmbeddingGemma ships in v1 per ADR-001 §"Addendum (2026-05-12)". No v1.5 fallback remains. | n/a — closed |
| `mic-perm-resume-recheck` | v1.5 | permissions | Phase 1 shell checks mic permission once at startup via `rememberSaveable`; revoking in Settings and returning leaves UI showing stale "granted" state. Fix is a `LifecycleEventEffect(ON_RESUME)` re-check, but Phase 4 replaces the shell entirely | Phase 4 onboarding UX ships — wire into the real permission gate there |
| ~~`gpu-model-artifact`~~ | — | — | **Resolved 2026-05-10 — wrong premise.** Artifact was GPU-capable; manifest missed `<uses-native-library>` for `libOpenCL.so` + `libvndksupport.so` (Android 12+ namespace). Fix in `AndroidManifest.xml`. Latency record in ADR-001 §Q3 addendum. | n/a — closed |
| `multi-chunk-foreground` | **v1.5 — HIGH** | inference | **Promoted 2026-05-14** from stt-A tier after on-device STT-A round-trip verified end-to-end. v1 `AudioCapture` is hard-capped at 30 s and emits one `isFinal=true` chunk; `ForegroundInference.runForegroundCall` rejects non-final chunks. The >30 s orchestration (stripped-down transcription-only call per intermediate chunk + concatenated transcript-so-far injected on the final chunk per ADR-002 §"For >30s captures") is unwritten. Single-narrative recordings cover the demo's 90 s pitch + 5 min walkthrough; long-dump pathway is the most-asked-for follow-up | Post-submission v1.5 work — the audio cue at 28 s tells the user the cap is firing but does not address the underlying length limit. To reproduce the chunk-boundary fixture, record the `docs/sample-data-scenarios.md` §STT-A "Read as one long capture" script forcing a 30 s split at `[CUT]` (or pre-split into two halves), transcode to PCM_S16LE 16 kHz mono per the STT-A §Q4 device-test record, then drive both halves through whatever multi-chunk orchestration ships at that point. See detail block below |
| `long-capture-duration-format` | v1.5 | capture | Footer/history duration labels intentionally use raw seconds while voice entries are capped at 30 s; once multi-chunk capture lands, `242s` stops being acceptable UI and the compact `4m 02s` style from `docs/ux-copy.md` needs to become real formatting instead of mocked test data | `multi-chunk-foreground` ships or any other change allows completed captures to exceed 30 s |
| ~~`smart-turn-boundaries`~~ | — | — | **Collapsed 2026-05-09** when v1 scoped to single-turn-per-capture (after the STT-B prompt-stuffing pattern produced retention=0.0; the SDK's stateful Conversation path was not measured — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior")). With each tap of record producing a fresh `CaptureSession` and no prior-turn context threaded into the prompt, there is no session boundary to be smart about under v1. | A future revival of multi-turn (post-v1, exercising the SDK stateful path) would re-open this row before Phase 4 history UI lands |
| ~~`parallel-lens-execution-via-clone`~~ | — | — | **Retired 2026-05-16 — created by a mistake.** This row only existed because the (now-**deleted**) ADR-009 wrongly concluded `litertlm-android:0.11.0` couldn't do concurrent multi-context inference. A direct AAR bytecode probe found `Engine.createSession()`/`createConversation()` on the pinned 0.11.0 — concurrent multi-context is **SDK-supported today**, never needed `Session.clone()`. It is **not** a backlog SDK-gap; concurrent-lens execution is now **scope-deferred** in Story 2.6.6 / Story 2.19 pending a RAM + wall-clock measurement (single-GPU command-queue serialization means no literal 3×). ADR-008 restored; see ADR-008 §Correction (2026-05-16). | n/a — retired (not SDK-gated; lives in Story 2.6.6 / 2.19) |
| `retrieval-indexed-prefilter` | v1.5 | memory | **Deferred 2026-05-11.** Story 3.1 `RetrievalRepo.query` loads `entryBox.all` + `tagBox.all` per call. At v1 scale (ADR-003 sizes Phase 3 to ≤1000 entries with detection cost in tens of ms) the in-memory scan is fast and trivially deterministic. ObjectBox prefilter (keyword `contains` + tag join) + stem-key index trades complexity for a future scale problem. PR #19 Copilot review flagged. | Measured retrieval latency degrades on the reference S24U beyond the foreground budget, *or* entry count crosses ~1000 in real usage |
| ~~`backfill-on-artifact-complete`~~ | — | — | **Resolved 2026-05-12.** `AppContainer.launchVectorBackfillIfReady()` now keeps one bounded in-process drain loop alive: if backlog exists before artifacts are complete, it retries for up to 12 delayed passes (60 s budget at 5 s intervals), then exits cleanly. A later save or cold start retriggers the worker. No Phase 4 download-complete event hook is required for correctness. | n/a — closed |
| `pattern-auto-close` | v1.5 | patterns | PatternEngine detects and updates patterns forward but has no staleness scan; nothing currently transitions a pattern from active → Closed; demo scenario doesn't require auto-removal; v1 user actions are Snooze and Drop only (Closed is model-detected per ADR-011 UX direction) | post-v1 usage data shows patterns accumulating without removal; or Phase 5 UX audit surfaces the missing lifecycle transition |
| `discard-after-stop` | v2 | capture | **Out of scope per ADR-001 §Q8.** Once STOP fires, the foreground inference call is in flight and not cancellable. Q8 explicitly defers in-flight-call cancellation; streaming would reopen the contract and require a new ADR | A streaming inference path lands AND user research shows accidental-STOP as a real pain point |
| `archetype-template-labeling` | v1.5 | extraction | **Structurally broken on realistic input (traced 2026-05-17).** `TemplateLabeler` only consumes CANONICAL fields; CANONICAL requires ≥2-of-3 lens agreement; the archetype triggers (`energy_descriptor=="crashed"`, `state_shift`, tags `tunnel-exit`/`decision-loop`/`stuck`/`late-night`) are all inferences only the Inferential lens emits, which resolves to CANDIDATE and is discarded. Every realistic entry falls through to `AUDIT`; the feature only produces a real label when the user speaks the exact internal vocabulary (Literal then also emits it → 2-lens agreement). Code retained but inert through Phase 2/3; the **UI yank is queued** in Phase 4 (Story 4.16) and has **not** landed on this branch yet. See detail block | A redesigned multi-lens contract: label demoted from canonical pattern-grouping key to a display-only single-lens hint (Inferential-sourced CANDIDATE accepted), superseding the ADR-002 resolver-contract coupling. New ADR required |

## Detail blocks

Only items where the index row drops information needed to disambiguate.

### `archetype-template-labeling` (UI yank queued for v1; redesign is v1.5)

```
root-cause (2026-05-17, traced through ConvergenceResolver.kt + TemplateLabeler.kt):
  - TemplateLabeler.isLoadBearing() accepts only CANONICAL / CANONICAL_WITH_CONFLICT;
    CANDIDATE and AMBIGUOUS fields are discarded before label selection.
  - DefaultConvergenceResolver mints CANONICAL only on >=2-of-3 lens agreement
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
    ALSO emits it and 2-lens agreement mints CANONICAL. That is the
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
  Queue the UI yank in Phase 4 (Story 4.16); it is not landed on this branch
  yet. Code (`TemplateLabeler`, `template_label` field,
  `BackgroundExtractionResult.templateLabel`) stays inert through Phase 2/3 —
  removing it mid-phase is a cross-cutting change with no demo value. Phase 4
  already specs a date fallback for "no template label resolved"
  (`phase-4-ux-surface.md` §Story 4.15) — that fallback becomes the only path
  once the yank lands. Patterns are unaffected: they group on tags / recurrence,
  not the label.
unblock-condition:
  New ADR superseding the ADR-002 resolver coupling: label demoted to a
  display-only hint, single-lens (Inferential CANDIDATE) accepted, decoupled
  from canonical pattern grouping. Out of v1 scope (no quick fixes — AGENTS.md).
spec-ref: concept-locked.md §"Templates"; adrs/ADR-002-multi-lens-extraction-pattern.md
          §"Convergence Resolver Contract"; docs/stories/phase-2-core-loop.md §Story 2.10
```

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
  Read-only. Snooze and Drop are not available once a pattern is Closed.

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
