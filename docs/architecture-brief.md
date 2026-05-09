# Architecture Brief — Vestige v1

This is the implementation map for Phase 1. Product behavior lives in `concept-locked.md` and `PRD.md`; this doc keeps module ownership and build order from leaking all over the carpet.

## Module Split

| Module | Owns | Depends on |
|---|---|---|
| `:core-model` | Domain types: `Entry`, `Tag`, `Pattern`, `TemplateLabel`, `Persona`, `ConvergenceResult`, error/status enums | none |
| `:core-storage` | ObjectBox entities/repositories, markdown read/write/export, keyword/tag/recency retrieval | `:core-model` |
| `:core-inference` | LiteRT-LM wrapper, model artifact loading, audio normalization, prompt composition, lens calls, convergence resolver | `:core-model`, `:core-storage` interfaces only |
| `:app` | Android app shell, Compose UI, navigation, `AppContainer`, permissions, onboarding, settings | all modules |

No extra modules in v1 unless they remove a real compile or ownership problem. Decorative architecture can go sit quietly.

## AppContainer Ownership

`AppContainer` is constructed once from `Application.onCreate`.

| Singleton | Lifecycle | Owns |
|---|---|---|
| `ModelArtifactStore` | process-scoped | model file path, download state, SHA-256 verification, re-download/delete model |
| `ModelHandle` | process-scoped, lazy after artifact verified | loaded LiteRT-LM engine and conversation factory |
| `NetworkGate` | process-scoped | sole HTTP/download path; `OPEN` only during model download, `SEALED` otherwise |
| `EntryStore` | process-scoped | ObjectBox entry/tag writes plus markdown source-of-truth |
| `PatternStore` | process-scoped | ObjectBox pattern persistence; lifecycle states (`active` / `dismissed` / `snoozed` / `resolved` / `below_threshold`), `pattern_id` content-hashing, supporting-entry references, global callout cooldown — all per `adrs/ADR-003-pattern-detection-and-persistence.md` |
| `RetrievalRepo` | process-scoped | keyword + tag + recency retrieval; vector only if STT-E passes |
| `InferenceCoordinator` | process-scoped | foreground call, background extraction scheduling, prompt composition, resolver |
| `SessionState` | active session | last turns, current persona override, chunk counter |

Use manual constructor injection. No Hilt in v1.

## Data Flow

1. User records or types.
2. Voice path captures with `AudioRecord`.
3. `:core-inference` downmixes/resamples/normalizes audio to Gemma's model-level target: mono 16 kHz float32 samples in `[-1, 1]`, max 30 seconds per clip.
4. STT-A (Phase 1 audio plumbing) locks the exact LiteRT-LM Android handoff: `Content.AudioBytes(...)` packing or temp `Content.AudioFile(...)`.
5. Foreground Gemma call returns transcription + follow-up.
6. `EntryStore` persists transcription as `entry_text` and writes markdown before background extraction starts.
7. Background extraction runs three sequential lens calls.
8. Convergence resolver writes canonical/candidate/ambiguous fields plus `entry_observations`.
9. Pattern detection runs after the configured threshold and persists sourced patterns.

Audio bytes are never product data. If temp audio files are required for LiteRT-LM, delete them immediately after the call.

## ObjectBox Entry Shape

Content fields:
- `entry_text`
- `timestamp`
- `template_label`
- `tags`
- `energy_descriptor`
- `recurrence_link`
- `stated_commitment`
- `entry_observations`
- `confidence`

Operational fields:
- `extraction_status`: `PENDING` / `RUNNING` / `COMPLETED` / `TIMED_OUT` / `FAILED`
- `attempt_count`
- `last_error`

If EmbeddingGemma ships, include vector fields/entities before the submitted APK schema is cut. Do not make vector schema conditional at runtime.

## Markdown Entry Shape

Markdown files are the source of truth per `concept-locked.md` §"Memory architecture." ObjectBox is a structured cache of what the markdown already contains. If ObjectBox is wiped, the app rebuilds from markdown. If markdown is missing, the entry never existed — there is no ObjectBox-only entry.

### Filename

```
{filesDir}/entries/{ISO8601-utc-second}--{slug}.md
```

- `ISO8601-utc-second` — `2026-05-08T14-32-15Z`. Colons replaced with hyphens for cross-FS safety.
- `slug` — kebab-case, ≤32 chars, derived from the first 5–6 content words of `entry_text` after stop-word strip; collisions get a `-2` / `-3` suffix.
- Filename is stable for the life of the entry. Renaming requires a write of the new file + delete of the old, never an in-place move (re-export safety).

### File format

YAML frontmatter, then plain markdown body. Frontmatter holds the structured fields; body holds `entry_text` exactly as captured.

```yaml
---
schema_version: 1
timestamp: 2026-05-08T14:32:15Z
template_label: aftermath
energy_descriptor: crashed
recurrence_link: null
stated_commitment: null
tags:
  - tuesday-meeting
  - standup
  - flattened
confidence:
  template_label: canonical
  tags: canonical
  energy_descriptor: canonical
  recurrence_link: null
  stated_commitment: null
entry_observations:
  - text: "you said 'fine' before the meeting and 'flattened' after"
    evidence: vocabulary-contradiction
    fields: [tags]
  - text: "third post-standup crash this month"
    evidence: pattern-callout
    fields: [recurrence_link]
---

Standup ran long again. I was fine before it, then completely flattened by 11. Opened the doc and just stared at it.
```

### Field placement rules

| Field | Location | Notes |
|---|---|---|
| `entry_text` | body | Exactly as captured. No transformation. Trailing newline only. |
| `timestamp` | frontmatter | UTC, ISO-8601 with seconds, no fractional. |
| `template_label` | frontmatter | Lowercase enum value (one of: aftermath, tunnel-exit, concrete-shoes, decision-spiral, goblin-hours, audit). |
| `tags` | frontmatter list | Lowercase, kebab-case. Sorted lexicographically on write for diff stability. |
| `energy_descriptor` | frontmatter | Free string or `null`. |
| `recurrence_link` | frontmatter | `pattern_id` or `null`. |
| `stated_commitment` | frontmatter | Object with `text`, `topic_or_person`, `entry_id` keys, or `null`. |
| `entry_observations` | frontmatter list | 1–2 objects with `text`, `evidence`, `fields[]`. Generated per ADR-002. |
| `confidence` | frontmatter object | Per-field convergence verdict. Mirrors the resolved confidence. |
| `extraction_status` / `attempt_count` / `last_error` | **not persisted to markdown** | Operational lifecycle state per ADR-001 Q3. Storage-layer-only; if rebuilt from markdown, status is `COMPLETED` by definition. |

### Sync direction & conflict policy

- **ObjectBox is downstream of markdown.** All writes go through `EntryStore`, which writes the markdown file first and the ObjectBox row second, in that order, in a single transactional unit. If the markdown write succeeds and ObjectBox fails, the next cold start rebuilds the row from the markdown. If the markdown write fails, no ObjectBox row exists.
- **External markdown edits are out of scope for v1.** The user can read or back up the files, but in-place external edits are not detected and may be overwritten by a later re-eval. v1 ships with markdown as a debugging/export surface only. External-edit support is a v1.5 entry in `backlog.md` if it ever earns one.
- **Re-eval rewrites the file.** Re-eval (P1) updates `tags`, `entry_observations`, etc. The resolver writes the new markdown atomically (write to `.tmp`, fsync, rename). Old content is not preserved unless the user explicitly rejects the new shape.
- **Export is a copy, not a move.** Settings → Export zips a snapshot of `entries/` plus a generated `manifest.json` (one row per entry: filename, sha256, schema_version). The originals remain in-place.

### `schema_version`

Top-level integer. v1 is `schema_version: 1`. Bump on any breaking frontmatter change. The reader must reject a markdown file with a schema_version it does not understand rather than silently downgrading fields. Migration paths are v1.5+ work.

## Phase-1 Build Sequence

1. Create Gradle scaffold and modules.
2. Add `:core-model` domain types.
3. Add ObjectBox + markdown storage skeleton.
4. Add `ModelArtifactStore` and model manifest shape.
5. Add `NetworkGate` and network security config.
6. Add LiteRT-LM engine smoke test.
7. Add audio normalization utility and STT-A API probe harness.
8. Add convergence resolver tests.
9. Build and install signed dummy release APK on the S24 Ultra.

Stop after each step if the premise breaks. Momentum is good; sprinting confidently into a wall is just cardio.
