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

This brief assumes those gates exist and the architecture is built to absorb their failure modes. (Outcome: **STT-E passed 2026-05-12** — the `EntryEntity.vector` HNSW field shipped in v1. The vector *surfaces* are not yet live; see README §"Known Limitations".)

## Module Split

| Module | Owns | Depends on |
|---|---|---|
| `:core-model` | Domain types: `Entry`, `Tag`, `Pattern`, `TemplateLabel`, `Persona`, `ConvergenceResult`, error/status enums | none |
| `:core-storage` | ObjectBox entities/repositories, markdown read/write/export, keyword/tag/recency retrieval | `:core-model` |
| `:core-inference` | LiteRT-LM wrapper, model artifact loading, audio capture, prompt composition, lens calls, convergence resolver | `:core-model` (only — does **not** depend on `:core-storage`; inference↔storage orchestration lives in `:app`) |
| `:app` | Android app shell, Compose UI, navigation, `AppContainer`, permissions, onboarding, settings | all modules |

No extra modules in v1 unless they remove a real compile or ownership problem. Decorative architecture can go sit quietly.

## AppContainer Ownership

`AppContainer` is constructed once from `Application.onCreate`.

| Singleton | Lifecycle | Owns |
|---|---|---|
| `ModelArtifactStore` | process-scoped | model file path, download state, SHA-256 verification, re-download/delete model |
| `ModelHandle` *(conceptual role — shipped as `backgroundEngine: LiteRtLmEngine`; no class literally named `ModelHandle`)* | process-scoped, lazy after artifact verified. Backgrounding/lifecycle per `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` (conditional foreground service in v1). | The loaded LiteRT-LM engine. One engine per process holding **one live session at a time** (`callMutex`). Concurrent multi-context was **disproven on-device and reversed** — `litertlm-android:0.11.0` is single-session (ADR-008 §Addendum 2026-05-17, STT-F). The interim ADR-009 was a deleted mis-scoped-probe mistake. |
| `Embedder` | process-scoped, lazy after embedding artifacts verified. **STT-E passed — shipped** (not contingent). | EmbeddingGemma 300M loader via `GemmaEmbeddingModel` from `com.google.ai.edge.localagents:localagents-rag` (LiteRT TFLite + SentencePiece bundled in `libgemma_embedding_model_jni.so`). Distinct native runtime from the `LiteRtLmEngine`'s LiteRT-LM — they share no `.so`. SDK pick rationale: `adrs/ADR-010-embeddinggemma-runtime-switch-to-litert.md`. |
| `NetworkGate` | process-scoped | sole HTTP/download path; `OPEN` only during model download, `SEALED` otherwise |
| `EntryStore` | process-scoped | ObjectBox entry/tag writes; export filename stability |
| `PatternStore` | process-scoped | ObjectBox pattern persistence, lifecycle state machine, and pattern detection algorithm per `adrs/ADR-003-pattern-detection-and-persistence.md` |
| `RetrievalRepo` | process-scoped | keyword + tag + recency + EmbeddingGemma cosine (STT-E passed — shipped). **Implemented + validated but currently unwired on the live path — see Addendum 2026-05-23.** |
| `InferenceCoordinator` *(conceptual role — no `InferenceCoordinator` class; realized by `ForegroundInference` + `BackgroundExtractionQueue` / `BackgroundExtractionSaveFlow` + `PromptComposer` + `DefaultConvergenceResolver`)* | process-scoped | Foreground call, background extraction scheduling, prompt composition, resolver. **Single-session, sequential — concurrent multi-context is SDK-impossible on `litertlm-android:0.11.0` (measured on-device 2026-05-17, STT-F `stt-results/stt-f-2026-05-17.md`; `adrs/ADR-008-parallel-lens-execution.md` §Addendum 2026-05-17 reverses §Correction).** `LiteRtLmEngine.callMutex` holds the createConversation→close lifetime exclusively: at most one live session ever (`stateMutex`/`drainGate` close-drain is orthogonal). The three background lenses run sequentially (`LENSES.map { runLens }`). Foreground capture owns the slot: starting a voice foreground call cancels active background extraction; queued background extraction drains FIFO when foreground releases the slot. Two Engines is out (2× weight load). The v1 inference lifecycle is locked by `adrs/ADR-014-foreground-background-split-and-periodic-pattern-analysis.md`; `adrs/ADR-018-inline-foreground-follow-up.md` keeps the follow-up inline with the transcription call. |
| `SessionState` *(conceptual role — no `SessionState` class; the capture-scoped state lives in `CaptureViewModel`)* | per-capture (single-use, terminates with the capture) | active persona for this capture + the live capture state, owned by `CaptureViewModel.CaptureUiState` (**Idle / Recording / Submitting** — there is no Inferring/Reviewing surface; post-stop navigates to History detail) over the `ForegroundStreamEvent` stream, persisted via `saveAndExtract` / `EntryStore`. v1 single-turn lifecycle per `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"); non-recoverable discard during RECORDING per `adrs/ADR-001-stack-and-build-infra.md` §Q8 (no rehydration, no Undo). The earlier `CaptureSession` / `Transcript` types that realized this were retired post-streaming as an orphaned duplicate — see `adrs/ADR-005…` §Addendum (2026-05-17). |

Use manual constructor injection. No Hilt in v1.

## Data Flow

1. User records or types.
2. Voice path captures with `AudioRecord`.
3. `:core-inference` downmixes/resamples/normalizes audio to Gemma's model-level target: mono 16 kHz float32 samples in `[-1, 1]`, max 30 seconds per clip.
4. STT-A (Phase 1 audio plumbing) locks the exact LiteRT-LM Android handoff: `Content.AudioBytes(...)` packing or temp `Content.AudioFile(...)`.
5. Foreground Gemma call returns transcription + follow-up.
6. `EntryStore` persists the transcript before background extraction starts: transcription as `entry_text`, foreground `follow_up`, and the selected `persona` for row provenance.
7. Background extraction runs three sequential lens calls.
8. Convergence resolver writes consensus/candidate/ambiguous fields plus `entry_observations`.
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
- `vocabulary`
- `recurrence_link`
- `stated_commitment`
- `entry_observations`
- `confidence`

Operational fields:
- `extraction_status`: `PENDING` / `RUNNING` / `COMPLETED` / `TIMED_OUT` / `FAILED`
- `attempt_count`
- `last_error`

If EmbeddingGemma ships, include vector fields/entities before the submitted APK schema is cut. Do not make vector schema conditional at runtime.

## Generated Entry Markdown

ObjectBox is the internal source of truth. Markdown is generated only during Settings → Export by rendering the current `EntryEntity` rows. There are no durable per-entry markdown sidecars in app storage.

### Filename

```
entries/{ISO8601-utc-second}--{slug}.md
```

- `ISO8601-utc-second` — `2026-05-08T14-32-15Z`. Colons replaced with hyphens for cross-FS safety.
- `slug` — kebab-case, ≤32 chars, derived from the first 5–6 content words of `entry_text` after stop-word strip; collisions get a `-2` / `-3` suffix.
- Filename is stable for the life of the entry and stored on the row so pattern/export references stay readable.

### File format

YAML frontmatter, then plain markdown body. Frontmatter is rendered from ObjectBox fields; body holds `entry_text` exactly as captured.

```yaml
---
schema_version: 1
timestamp: 2026-05-08T14:32:15Z
persona: witness
follow_up: What happened right after that?
template_label: aftermath
vocabulary: drained
recurrence_link: a3f9c2b8d4e7f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6
stated_commitment: null
tags:
  - tuesday-meeting
  - standup
  - flattened
confidence:
  template_label: canonical
  tags: canonical
  vocabulary: canonical
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
| `follow_up` | frontmatter | Foreground persona follow-up for voice captures; `null` for typed entries. |
| `duration_ms` | frontmatter | Millis of captured audio; `0` for typed entries and rows written before Story 4.6. |
| `template_label` | frontmatter | Lowercase enum value (one of: aftermath, tunnel-exit, stalled, decision-spiral, goblin-hours, audit). |
| `tags` | frontmatter list | Lowercase, kebab-case. Sorted lexicographically on write for diff stability. Empty case serializes as the inline `tags: []` — bare `tags:` parses as `null` under YAML 1.2 and breaks round-trip importers. |
| `vocabulary` | frontmatter | Single lowercase tone word or `null`. |
| `recurrence_link` | frontmatter | `pattern_id` or `null`. |
| `stated_commitment` | frontmatter | Object with `text`, `topic_or_person`, `entry_id` keys, or `null`. |
| `entry_observations` | frontmatter list | 1–2 objects with `text`, `evidence`, `fields[]`. Generated per ADR-002. |
| `confidence` | frontmatter object | Per-field convergence verdict. Mirrors the resolved confidence. |
| `extraction_status` / `attempt_count` / `last_error` | `vestige-export.json` only | Operational lifecycle state per ADR-001 Q3. Not rendered into per-entry markdown. |

### Export policy

- **No internal markdown sidecars.** `EntryStore` writes ObjectBox only.
- **Export is generated.** Settings → Export zips rendered markdown under `entries/` plus `vestige-export.json`, a structured snapshot of ObjectBox rows, pattern evidence links, vectors, callout cooldown state, and onboarding settings.
- **No import/edit contract in v1.** External markdown edits are impossible because markdown exists only in the exported archive.

### `schema_version`

Top-level integer in generated markdown. v1 is `schema_version: 1`. Bump on any breaking frontmatter change.

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

**Addendum (2026-05-24) — embedding axis repointed to the tone word (feeling), not the topic.** The distilled-content target above optimized for *retrieval* ("what the entry is about"). But the only live consumer of `EntryEntity.vector` is `EmbeddingClustering` (the `VOCAB_FREQUENCY` / Vocab Drift surface), and that feature clusters by *feeling* — it needs synonymous tones ("drained", "wiped", "running on empty") to group across different topics. A content vector clusters by subject and never groups them, which is why no vocab cluster ever minted (the root cause behind `backlog.md` → `vocab-cluster-threshold`).

- **New target:** `buildEmbeddingText` now embeds `EntryEntity.vocabularyWord` (trimmed, lowercased). A null/blank tone yields `""` — a toneless (purely factual) entry is excluded from every feeling cluster, not assigned a fabricated one. `vectorSchemaVersion` bumped `1 → 2` so the backfill worker re-embeds existing rows.
- **Consequence for retrieval:** the vector is now the *feeling* axis. `RetrievalRepo` (still dead on the live path) would now compare a content query against tone-word entry vectors — incoherent. This reinforces `embedding-retrieval-surface`: wire it to a real surface with its *own* content vector, or delete it. Don't revive it against this vector.
- **Thresholds pending recalibration.** `DEFAULT_MAX_COSINE_DISTANCE` / `VOCAB_THRESHOLD` / `MIN_SUPPORTING_ENTRIES` were calibrated on the identical-word "tired × 23" fixture and are wrong for tone-word distances. `detectVocab` now logs candidate count + cluster sizes + nearest-neighbor distances. **Open on-device step:** `EXTRACT=1` re-seed, read the real distances, set the cut from measurement, confirm a cluster mints. Until then vocab still won't surface — the axis is fixed, the calibration is not.

---

## Concurrent Inference Architecture (Addendum 2026-05-16)

**Superseded by ADR-008 addendums 2026-05-17 and 2026-05-20.** This section records the measured fork in reasoning: the 2026-05-16 bytecode probe exposed methods that looked viable, STT-F proved concurrent sessions fail at runtime, and the 2026-05-20 implementation uses cancellation + FIFO rerun instead of concurrent contexts.

v1 runs foreground and background inference sequentially through a single `LiteRtLmEngine` behind a `Mutex`. A recording attempt while a background extraction is running blocks on the mutex until the current lens call finishes. This is the documented v1 **scope position** (ADR-002 sequential rule), not an SDK limitation.

**SDK reality (2026-05-16 bytecode probe of pinned 0.11.0):** one Engine → many **independent** contexts via `Engine.createSession(SessionConfig)` / `Engine.createConversation(ConversationConfig)`. Contexts share the loaded model weights (no 2× weight RAM) but each holds **its own** KV state — there is **no** parent-Session Copy-on-Write prefix sharing (`Session.clone()` does not exist; the earlier ADR-009 claim to the contrary was a mis-scoped-probe mistake and was deleted — see ADR-008 §Correction 2026-05-16).

**Two viable paths (Story 2.6.6 / 2.19 decide on measurement, not on an SDK gate):**

*Path B — Priority queue, single context:* Foreground calls preempt background. Background extraction jobs live in a `Channel<InferenceRequest>` ordered by priority. When a foreground call arrives, the background coroutine is cancelled (Flow cancellation is cooperative) and the foreground call runs immediately. Background re-queues after completion. Simplest; no concurrency RAM cost.

*Path C — Detached background context:* One Engine; a dedicated context per concurrent task via `createSession`/`createConversation` (independent, **not** cloned — each composes its own prefix; no CoW). Eliminates Kotlin-layer mutex blocking so foreground can preempt without waiting. A single GPU still serializes at the hardware command queue, so this is **not** a literal wall-clock speedup — the win is non-blocking preemption. Net background wall-clock and concurrent-context RAM on the reference S24 Ultra are **unmeasured**; that measurement is the Path-B-vs-C decision input.

Two Engine instances pointing at the same model file path load weights twice (~2× RAM). Not viable on Android given E4B's footprint. Do not propose this path.

Story 2.19 carries the implementation decision and wiring once Story 2.14 confirms what the SDK supports.

---

## Retrieval History Gap (Addendum 2026-05-16)

`CaptureViewModel` persists the entry as soon as the foreground terminal result lands and leaves `retrievedHistory` empty on that foreground save. `BackgroundExtractionSaveFlow` performs retrieval before it calls `BackgroundExtractionWorker`.

The consequence is deliberate: entry creation is no longer stalled on query embedding + vector lookup. The user gets the transcript-backed entry first; history-conditioned lens extraction lands afterward.

**Correct behavior:** foreground owns the immediate transcript + follow-up / open-entry handoff; retrieval history feeds detached background analysis after transcription lands.

**Corrected 2026-05-20.** Both voice and typed capture skip retrieval on the critical path. `CaptureViewModel` saves the pending entry as soon as it has authoritative foreground text and opens History detail immediately. `BackgroundExtractionSaveFlow` performs its own retrieval when the caller supplied none, so structured extraction keeps prior-entry context without making the user wait. `LiteRtLmEngine` still serializes Gemma calls on the GPU; foreground cancels active background extraction, then queued extraction reruns FIFO after foreground releases the slot.

**Addendum (2026-05-23) — "retrieval" on the live path is deterministic; the embedding hybrid is unwired.** The "retrieval" `BackgroundExtractionSaveFlow` performs (above) is **deterministic**, not embedding-based — and the embedding hybrid is dead on the live path. Be precise about which is which:

- **Live (deterministic, no embeddings):** the lens read gets `PatternCandidates` (signature match against ACTIVE patterns) via `retrievePatternCandidates`; the observation read gets `TemporalHistoryRetrieval` (same weekday + time-of-day block). Neither touches `EntryEntity.vector`.
- **Built but unwired:** `RetrievalRepo` (keyword + tag-Jaccard + recency + **EmbeddingGemma cosine**) is fully implemented and STT-E-validated (`stt-results/stt-e-2026-05-19.md`), but its only caller `AppContainer.retrieveHistory` is **never invoked**, and `CaptureViewModel` passes `retrievedHistory = emptyList()`. It is dead code on the live path.
- **The only runtime consumer of `EntryEntity.vector`** is `EmbeddingClustering` (the `VOCAB_FREQUENCY` pattern → Vocab Drift screen). That is the single surface where embeddings *would* visibly act — but on the demo corpus it does **not** mint a cluster (cosine cut calibrated for an identical-word fixture; drifted prose fragments below the `VOCAB_THRESHOLD = 4` floor). Verified on-device 2026-05-23: 5 patterns formed, all deterministic, zero vocab. So embeddings currently surface nothing visible. Tracked in `backlog.md` → `vocab-cluster-threshold`.

Tracked in `backlog.md` → `embedding-retrieval-surface` (wire it into a demo-gate-clearing surface, or cut it rather than ship dead plumbing).

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
