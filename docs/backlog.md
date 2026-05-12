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
| `multi-chunk-foreground` | stt-A | inference | v1 `AudioCapture` is hard-capped at 30 s and emits one `isFinal=true` chunk; `ForegroundInference.runForegroundCall` rejects non-final chunks. The >30 s orchestration (stripped-down transcription-only call per intermediate chunk + concatenated transcript-so-far injected on the final chunk per ADR-002 §"For >30s captures") is unwritten. Single-narrative recordings cover the demo's 90 s pitch + 5 min walkthrough; long-dump pathway is not on the critical path | user gives a >30 s entry attempt and it fails / `docs/sample-data-scenarios.md` §STT-A chunk-boundary script gets exercised end-to-end. To reproduce the chunk-boundary fixture, record the §STT-A "Read as one long capture" script forcing a 30 s split at `[CUT]` (or pre-split into two halves), transcode to PCM_S16LE 16 kHz mono per the STT-A §Q4 device-test record, then drive both halves through whatever multi-chunk orchestration ships at that point |
| ~~`smart-turn-boundaries`~~ | — | — | **Collapsed 2026-05-09** when v1 scoped to single-turn-per-capture (after the STT-B prompt-stuffing pattern produced retention=0.0; the SDK's stateful Conversation path was not measured — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior")). With each tap of record producing a fresh `CaptureSession` and no prior-turn context threaded into the prompt, there is no session boundary to be smart about under v1. | A future revival of multi-turn (post-v1, exercising the SDK stateful path) would re-open this row before Phase 4 history UI lands |
| `parallel-lens-execution-via-clone` | v1.5 | inference | **Deferred 2026-05-11** after the ADR-008 feasibility probe confirmed `litertlm-android:0.11.0` does not expose `Session.clone()` or any parent-Session API. JNI bridge inspected — `nativeCreateSession(engineHandle, samplerConfig)` has no parent reference. Upstream PR #1515 (Kotlin clone surface) was closed unmerged 2026-03-09; upstream Issue #1226 ("Session Advanced" Android support) is open with no shipped timeline. Story 2.6.6 superseded; v1 ships ADR-002's original sequential 3-lens rule. Full evidence in `adrs/ADR-009-litertlm-kotlin-session-clone-unavailable.md`. | One of: a new `com.google.ai.edge.litertlm:litertlm-android` artifact >0.11.0 publishes; upstream `main` HEAD adds `Session.clone()` or parent-Session `SessionConfig`; Issue #1226 closes "shipped"; a Google-authored Kotlin clone PR merges. See ADR-009 §"Revival triggers" |
| `retrieval-indexed-prefilter` | v1.5 | memory | **Deferred 2026-05-11.** Story 3.1 `RetrievalRepo.query` loads `entryBox.all` + `tagBox.all` per call. At v1 scale (ADR-003 sizes Phase 3 to ≤1000 entries with detection cost in tens of ms) the in-memory scan is fast and trivially deterministic. ObjectBox prefilter (keyword `contains` + tag join) + stem-key index trades complexity for a future scale problem. PR #19 Copilot review flagged. | Measured retrieval latency degrades on the reference S24U beyond the foreground budget, *or* entry count crosses ~1000 in real usage |
| ~~`backfill-on-artifact-complete`~~ | — | — | **Resolved 2026-05-12.** `AppContainer.launchVectorBackfillIfReady()` now keeps one bounded in-process drain loop alive: if backlog exists before artifacts are complete, it retries for up to 12 delayed passes (60 s budget at 5 s intervals), then exits cleanly. A later save or cold start retriggers the worker. No Phase 4 download-complete event hook is required for correctness. | n/a — closed |

## Detail blocks

Only items where the index row drops information needed to disambiguate.

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
