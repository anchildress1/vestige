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
| `embeddings-fallback` | stt-E | memory | conditional: tag-only covers ~365-1000 entry scale | STT-E fails OR user data scale where tag-only weakens visibly |
| `mic-perm-resume-recheck` | v1.5 | permissions | Phase 1 shell checks mic permission once at startup via `rememberSaveable`; revoking in Settings and returning leaves UI showing stale "granted" state. Fix is a `LifecycleEventEffect(ON_RESUME)` re-check, but Phase 4 replaces the shell entirely | Phase 4 onboarding UX ships — wire into the real permission gate there |

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

### `embeddings-fallback`

```
trigger: STT-E outcome
if-pass: ship EmbeddingGemma 300M + ObjectBox vector index in v1 P0 (memory)
if-fail: ship keyword + tags + recency only; this entry activates and embeddings move to v1.5
spec-ref: PRD.md §"Build philosophy: build first, test at failure zones" STT-E; adrs/ADR-001-stack-and-build-infra.md §"Locked Stack" Storage row; concept-locked.md §"Memory architecture"
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
