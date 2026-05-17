# Architecture Brief — Vestige v1

Implementation map for the v1 build, sequenced for Phase 1 entry. Product behavior lives in `concept-locked.md` and `PRD.md`; the per-phase work queues live in `stories/phase-{1..7}-*.md`. This doc keeps module ownership, data flow, and the schema shape from leaking all over the carpet.

**Build philosophy:** there is no Phase 0 validation phase. We build directly. Five stop-and-test points are embedded inline in phases 1–3 — see `PRD.md` §"Build philosophy: build first, test at failure zones" for the canonical table. Brief reference:

| STT | Lives in | What's tested | Failure mode |
|---|---|---|---|
| **STT-A** | Phase 1 audio pipeline | Audio bytes round-trip through Gemma 4 via LiteRT-LM | Existential — replan |
| **STT-B** | Phase 2 capture loop | Multi-turn reliability on E4B | Drop to single-turn |
| **STT-C** | Phase 2 extraction | Tag stability across equivalent dumps | Tighten prompts; accept noise as last resort |
| **STT-D** | Phase 2 multi-lens | 3-lens convergence differs sometimes | Drop multi-lens to single-pass |
| **STT-E** | Phase 3 retrieval | EmbeddingGemma vs tag-only | Drop EmbeddingGemma to v1.5 |

This brief assumes those gates exist and the architecture is built to absorb their failure modes (e.g., the Phase-1 schema does not include a vector field; the vector field lands in Phase 3 only if STT-E passes).

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
| `ModelHandle` | process-scoped, lazy after artifact verified. Backgrounding/lifecycle behavior per `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` (conditional foreground service in v1, Option 1 always-on as documented fallback). | Loaded LiteRT-LM engine and conversation factory. One handle per process. ADR-008's "Engine wrapper" framing stands — the Engine creates contexts via `Engine.createSession`/`createConversation` (the shipping code already uses `createConversation`). The interim ADR-009 rollback was a mis-scoped-probe mistake and was deleted; see `adrs/ADR-008-parallel-lens-execution.md` §Correction (2026-05-16). |
| `Embedder` | process-scoped, lazy after embedding artifacts verified. STT-E-contingent — instantiated only if `embedding_artifact_*` + `embedding_tokenizer_*` manifest entries resolve and Story 3.3 passes. | EmbeddingGemma 300M loader via `GemmaEmbeddingModel` from `com.google.ai.edge.localagents:localagents-rag` (LiteRT TFLite + SentencePiece bundled in `libgemma_embedding_model_jni.so`). Distinct native runtime from `ModelHandle`'s LiteRT-LM — they share no `.so`. SDK pick rationale: `adrs/ADR-010-embeddinggemma-runtime-switch-to-litert.md`. |
| `NetworkGate` | process-scoped | sole HTTP/download path; `OPEN` only during model download, `SEALED` otherwise |
| `EntryStore` | process-scoped | ObjectBox entry/tag writes plus markdown source-of-truth |
| `PatternStore` | process-scoped | ObjectBox pattern persistence, lifecycle state machine, and pattern detection algorithm per `adrs/ADR-003-pattern-detection-and-persistence.md` |
| `RetrievalRepo` | process-scoped | keyword + tag + recency retrieval; vector only if STT-E passes |
| `InferenceCoordinator` | process-scoped | Foreground call, background extraction scheduling, prompt composition, resolver. **Concurrent multi-context adopted 2026-05-17 (operator directive; Stories 2.6.6 + 2.19).** `LiteRtLmEngine` uses a readers/writer guard: inference calls are concurrent "readers" each opening an independent SDK conversation off the one Engine (`createConversation`; no `Session.clone()`/CoW per `adrs/ADR-008-parallel-lens-execution.md` §Correction + §Addendum 2026-05-17), `close()` is the exclusive draining "writer". The three background lenses fan out (`coroutineScope`/`awaitAll`); the foreground path no longer blocks on a shared exclusive call mutex (Story 2.19 — Path C). The single GPU still serializes at its command queue, so this is non-blocking structure, not a literal 3×; concurrent-context RAM + wall-clock on the reference S24 Ultra is an open on-device manual-check measurement (not self-reported). |
| `SessionState` | per-capture (single-use, terminates with the capture) | active persona for this capture + the in-flight `CaptureSession` instance (one USER turn + one MODEL turn under the v1 single-turn lifecycle per `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`, which amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"). Terminals: `RESPONDED`, `ERROR`, `DISCARDED` (user-initiated cancel during RECORDING per `adrs/ADR-001-stack-and-build-infra.md` §Q8 — no rehydration, no Undo). The "last turns" + "chunk counter" fields the original spec described are moot under the 30 s hard cap and have been retired. |

Use manual constructor injection. No Hilt in v1.

## Data Flow

1. User records or types.
2. Voice path captures with `AudioRecord`.
3. `:core-inference` downmixes/resamples/normalizes audio to Gemma's model-level target: mono 16 kHz float32 samples in `[-1, 1]`, max 30 seconds per clip.
4. STT-A (Phase 1 audio plumbing) locks the exact LiteRT-LM Android handoff: `Content.AudioBytes(...)` packing or temp `Content.AudioFile(...)`.
5. Foreground Gemma call returns transcription + follow-up.
6. `EntryStore` persists the foreground exchange before background extraction starts: transcription as `entry_text`, saved model turn as `follow_up` when present, and the recorded `persona` that authored it.
7. Background extraction runs three sequential lens calls.
8. Convergence resolver writes canonical/candidate/ambiguous fields plus `entry_observations`.
9. Pattern detection runs after the configured threshold and persists sourced patterns.

Audio bytes are never product data. If temp audio files are required for LiteRT-LM, delete them immediately after the call. User-initiated cancel during RECORDING (the `DISCARD · NO SAVE` affordance) destroys the in-flight buffer synchronously with the tap — `AudioRecord` is stopped + released, no Gemma 4 call fires, no `Entry` row lands, no markdown is written, the session terminates `DISCARDED`. Contract: `adrs/ADR-001-stack-and-build-infra.md` §Q8.

## ObjectBox Entry Shape

Content fields:
- `entry_text`
- `follow_up`
- `persona`
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

YAML frontmatter, then plain markdown body. Frontmatter holds the structured fields for the saved exchange; body holds `entry_text` exactly as captured.

```yaml
---
schema_version: 1
timestamp: 2026-05-08T14:32:15Z
persona: witness
follow_up: What happened right after that?
template_label: aftermath
energy_descriptor: crashed
recurrence_link: a3f9c2b8d4e7f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6
stated_commitment: null
tags:
  - tuesday-meeting
  - standup
  - flattened
confidence:
  template_label: canonical
  tags: canonical
  energy_descriptor: canonical
  recurrence_link: canonical
  stated_commitment: canonical
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
| `persona` | frontmatter | Lowercase enum value for the saved model turn's author (`witness`, `hardass`, `editor`). |
| `follow_up` | frontmatter | Saved model turn for single-turn voice captures, or `null` when no follow-up exists. |
| `duration_ms` | frontmatter | Millis of captured audio; `0` for typed entries and rows written before Story 4.6. |
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
- **Export is a copy, not a move.** Settings → Export zips a snapshot of the markdown files under `entries/`. The originals remain in-place.

### `schema_version`

Top-level integer. v1 is `schema_version: 1`. Bump on any breaking frontmatter change. The reader must reject a markdown file with a schema_version it does not understand rather than silently downgrading fields. Migration paths are v1.5+ work.

## Embedding Strategy (Addendum 2026-05-16)

**Unit of embedding: one vector per entry (`EntryEntity.vector`).**

The deterministic retrieval layer (Story 3.1) already handles tag matching via Jaccard overlap — exact tag-set intersection is fast, deterministic, and correct for "find entries with this tag." Adding per-tag semantic embeddings on top of that is redundant; the deterministic layer already does that job better.

The semantic embedding layer's job is to catch what the deterministic layer *can't*: vocabulary drift over months, paraphrased concepts that didn't resolve to the same tags, semantic relationships between entries that tag overlap misses entirely. That is a per-entry, cross-vocabulary concern. One vector per entry is the correct granularity.

**The bug in the original implementation:** `VectorBackfillWorker` embeds `entry.entryText` — the raw verbatim transcription. A 30s ADHD voice entry is stream-of-consciousness; its semantic centroid captures filler, tangents, and noise, not what the entry is actually about. The distilled signal already exists in the extracted fields.

**Correct embedding target:** synthesize a short string from the extraction output after convergence resolves:
- Tags: join the `TagEntity` labels as a space-separated phrase (e.g., `"tuesday-meeting standup flattened"`)
- Observation texts: join each `text` field from `entryObservationsJson` with `. `
- Commitment topic: append `topic_or_person` from `statedCommitmentJson` if present
- Result: `"{tags}. {observations}. {commitment topic}"` — omit any empty component and its separator

This is the model's own distillation of what happened. The vector then represents semantic meaning, not transcription noise. Two entries about the same phenomenon phrased differently will cluster correctly even if the user's vocabulary drifted between them.

**Query side:** `RetrievalRepo.query(text: String, ...)` embeds the raw user query as-is — correct. Natural language query against distilled semantic content.

**Story 3.11 carries the embedding source fix and re-backfill sweep.**

---

## Concurrent Inference Architecture (Addendum 2026-05-16)

v1 runs foreground and background inference sequentially through a single `LiteRtLmEngine` behind a `Mutex`. A recording attempt while a background extraction is running blocks on the mutex until the current lens call finishes. This is the documented v1 **scope position** (ADR-002 sequential rule), not an SDK limitation.

**SDK reality (2026-05-16 bytecode probe of pinned 0.11.0):** one Engine → many **independent** contexts via `Engine.createSession(SessionConfig)` / `Engine.createConversation(ConversationConfig)`. Contexts share the loaded model weights (no 2× weight RAM) but each holds **its own** KV state — there is **no** parent-Session Copy-on-Write prefix sharing (`Session.clone()` does not exist; the earlier ADR-009 claim to the contrary was a mis-scoped-probe mistake and was deleted — see ADR-008 §Correction 2026-05-16).

**Two viable paths (Story 2.6.6 / 2.19 decide on measurement, not on an SDK gate):**

*Path B — Priority queue, single context:* Foreground calls preempt background. Background extraction jobs live in a `Channel<InferenceRequest>` ordered by priority. When a foreground call arrives, the background coroutine is cancelled (Flow cancellation is cooperative) and the foreground call runs immediately. Background re-queues after completion. Simplest; no concurrency RAM cost.

*Path C — Detached background context:* One Engine; a dedicated context per concurrent task via `createSession`/`createConversation` (independent, **not** cloned — each composes its own prefix; no CoW). Eliminates Kotlin-layer mutex blocking so foreground can preempt without waiting. A single GPU still serializes at the hardware command queue, so this is **not** a literal wall-clock speedup — the win is non-blocking preemption. Net background wall-clock and concurrent-context RAM on the reference S24 Ultra are **unmeasured**; that measurement is the Path-B-vs-C decision input.

Two Engine instances pointing at the same model file path load weights twice (~2× RAM). Not viable on Android given E4B's footprint. Do not propose this path.

Story 2.19 carries the implementation decision and wiring once Story 2.14 confirms what the SDK supports.

---

## Retrieval History Gap (Addendum 2026-05-16)

`AppContainer.saveAndExtract` accepts `retrievedHistory: List<HistoryChunk>` but `CaptureViewModel` does not perform a `RetrievalRepo.query()` before the foreground call and therefore passes no history. Retrieved history flows only into `BackgroundExtractionWorker`'s lens prompts.

The consequence: the foreground response the user sees immediately after recording has no awareness of prior entries. The background extraction does. This means the follow-up question the user receives is context-free while the structured fields are context-aware — an inversion of where context matters most from a UX standpoint.

**Correct behavior:** run `RetrievalRepo.query(transcription)` after the foreground transcription is available but before the follow-up is generated — or restructure the foreground call to accept prior context. Story 2.18 carries the fix.

**Resolved 2026-05-17 (Story 2.18, option C).** The foreground voice path is now two model calls: call 1 streams the transcription from the audio and is cancelled at the `Transcription` event (no wasted follow-up generation; the temp WAV is still discarded by `ForegroundInference`'s `finally`); `AppContainer.retrieveHistory(transcription)` then runs `RetrievalRepo.query(query, topN = 3)` on `Dispatchers.Default`; call 2 (`runForegroundTextCall`) streams a follow-up conditioned on a `## PRIOR ENTRIES` prompt block (same budget as `PromptComposer`'s background history). The call-1 transcription is authoritative — call 2's echo never overwrites the verbatim user words. Typed entries are single-call (text known up front). Retrieved history is also threaded into `saveAndExtract`, so background extraction is context-aware too. Retrieval degrades to empty (capture never blocked) at two layers — `AppContainer.retrieveHistory` and `CaptureViewModel.retrieveHistorySafely`. Operator-accepted cost: the voice path is two calls; call-1 latency is masked because the transcription streams immediately. Two-call wall-clock on the reference S24 Ultra is an unrecorded manual-check measurement.

---

## Phase-1 Build Sequence

The granular work queue lives in `stories/phase-1-scaffold.md`. This list is the architectural ordering — what gets stood up before what — not the full story breakdown.

1. Create Gradle scaffold and modules. *(Story 1.1, 1.2)*
2. Add `:core-model` domain types. *(part of Story 1.1)*
3. Add ObjectBox + markdown storage skeleton — no vector field. *(Story 1.6, 1.7)*
4. Add `ModelArtifactStore` and model manifest shape. *(Story 1.9)*
5. Add `NetworkGate` and network security config. *(Story 1.10)*
6. Add LiteRT-LM engine smoke test (text-only). *(Story 1.3)*
7. Add audio normalization utility and STT-A API probe harness. *(Story 1.4)*
8. **🛑 STT-A — audio plumbing test on the reference device.** *(Story 1.5, existential)*
9. Add persona prompt scaffold (tone-only, three personas). *(Story 1.8)*
10. Add convergence resolver tests (scaffold only — Phase 2 implements). *(Story 1.12)*
11. Build and install signed dummy release APK on the S24 Ultra. *(Story 1.11)*

STT-A is the only existential gate inside Phase 1. If it fails after a time-boxed debugging window, stop and replan rather than continue building. Stop after any other step if the premise breaks too — momentum is good; sprinting confidently into a wall is just cardio.

## Release Keystore Setup (Story 1.11)

Per ADR-001 §Q5 the keystore lives outside the repo and is referenced via `keystore.properties` at the repo root (gitignored). One-time setup on a fresh machine:

1. Generate the keystore once: `keytool -genkeypair -v -storetype PKCS12 -keystore ~/.vestige/keystore.jks -alias vestige-release -keyalg RSA -keysize 4096 -validity 10000`. Save the passwords in macOS Keychain (or a password manager).
2. Copy `keystore.properties.example` to `keystore.properties` and fill in the four fields. The file is gitignored.
3. Build: `./gradlew :app:assembleRelease`. The output APK is at `app/build/outputs/apk/release/app-release.apk` and is signed with the real key.
4. Install on the reference S24 Ultra: `adb install -r app/build/outputs/apk/release/app-release.apk`.

If `keystore.properties` is absent the release variant falls back to the debug keystore so the build still completes for agent loops — the Gradle warning makes this loud. The Phase 6 submission build must use the real key; the build operator confirms by checking the WARN line is absent.

If the keystore is lost: a sideload upgrade to a differently-signed APK requires the user to uninstall first. Document that limitation in the README before submission.
