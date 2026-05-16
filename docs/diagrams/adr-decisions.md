# ADR Decisions

Every ADR (001–013) as written, assuming the full v1 feature set is complete. `backlog.md` is
out of scope by design. Shared state machines live in [state-diagrams.md](state-diagrams.md) and
are cross-linked rather than redrawn.

---

## Supersession & amendment graph

```mermaid
flowchart TD
    accTitle: ADR supersession and amendment relationships
    accDescr: ADR-006 and ADR-007 amend ADR-004. ADR-005 amends ADR-002. ADR-008 was superseded entirely by ADR-009, which restored ADR-002's sequential lens rule. ADR-010 supersedes ADR-001's embeddings section. ADR-011 supersedes the design-guidelines visual layer only. ADR-013 supersedes the model-free typed-fallback premise.

    A001["ADR-001<br/>stack & infra"]
    A002["ADR-002<br/>3-lens × 5-surface"]
    A003["ADR-003<br/>pattern detection"]
    A004["ADR-004<br/>backgrounding + model handle"]
    A005["ADR-005<br/>single-turn (amends 002)"]
    A006["ADR-006<br/>START_NOT_STICKY (amends 004)"]
    A007["ADR-007<br/>FG state machine ext (amends 004)"]
    A008["ADR-008<br/>parallel lenses (SUPERSEDED)"]
    A009["ADR-009<br/>clone unavailable (supersedes 008)"]
    A010["ADR-010<br/>EmbeddingGemma → LiteRT"]
    A011["ADR-011<br/>Scoreboard design pivot"]
    A012["ADR-012<br/>GPU perf + pre-warm"]
    A013["ADR-013<br/>typed requires fg model"]

    A005 -- amends --> A002
    A006 -- amends --> A004
    A007 -- amends --> A004
    A007 -- depends on --> A006
    A009 -- supersedes --> A008
    A009 -- restores sequential rule of --> A002
    A010 -- supersedes §Embeddings of --> A001
    A011 -- supersedes visual layer of --> Design["design-guidelines.md"]
    A013 -- supersedes typed-fallback premise --> A005
```

---

## ADR-001 — v1 Stack & Build Infrastructure

**Status:** Accepted. §Embeddings/Q6 superseded by ADR-010.
**Decision:** Lock the stack; 4-module split (`:app` / `:core-model` / `:core-inference` /
`:core-storage`); `AppContainer` constructor-DI built once in `Application.onCreate`;
`NetworkGate` (`SEALED` default, `OPEN` only for download); resolve Q1–Q8. State machines for
`NetworkGate`, `extraction_status`, and CaptureSession-discard are in
[state-diagrams.md](state-diagrams.md); module graph + DI in [architecture.md](architecture.md).

**Q4 — audio chunking (>30 s):** ≤30 s is one call; >30 s is N sequential 30 s chunks,
transcription-only for 1…N−1, follow-up on the final chunk.

```mermaid
sequenceDiagram
    accTitle: ADR-001 Q4 over-30-second audio chunking
    accDescr: For audio longer than 30 seconds, chunks 1 through N minus 1 are transcription-only calls. The final chunk receives the concatenated transcript and produces the follow-up. Final-chunk detection is the explicit Stop.

    participant Cap as CaptureSession
    participant G as Gemma
    Cap->>G: chunk 1 (transcription only)
    G-->>Cap: transcript₁
    Cap->>G: chunk 2 (transcription only)
    G-->>Cap: transcript₂
    Note over Cap,G: … chunks 3 … N-1 …
    Cap->>G: chunk N + concatenated transcript (explicit Stop)
    G-->>Cap: { transcription, follow_up }
```

---

## ADR-002 — Multi-Lens Extraction Pattern

**Status:** Accepted. Amended by ADR-005; sequencing superseded by ADR-008 then **restored**
by ADR-009.
**Decision:** 3 independent lens calls (Literal / Inferential / Skeptical), each composing all 5
surfaces (Behavioral / State / Vocabulary / Commitment / Recurrence); a **deterministic Kotlin**
convergence resolver (not a 4th model call). Two-tier: foreground returns
`{transcription, follow_up}`; background runs the 3 lenses + resolver in 30–90 s. **Agreement
predicate is written against the storage enum** (`template_label` ∈ {Aftermath, Tunnel exit,
Concrete shoes, Decision spiral, Goblin hours, Audit} — positional 1:1 with the
`concept-locked` product names). Full sequence + resolver decision:
[llm-functionality.md](llm-functionality.md).

---

## ADR-003 — Pattern Detection & Persistence

**Status:** Accepted. Lifecycle revised by the 2026-05-13 / 13b / 15 addenda.
**Decision:** 5 sourced content-addressable primitives (`template_recurrence`,
`tag_pair_co_occurrence`, `time_of_day_cluster`, `commitment_recurrence`, `vocab_frequency`),
deterministic Kotlin pass over the last 90 days, run every 10 entries; ObjectBox keyed by
`sha256(Json{kind, signatureKey})`; 3-entry global callout cooldown. Lifecycle (incl. the
13/13b Skip/Drop/Restart revision, `CLOSED` model-only) is in
[state-diagrams.md](state-diagrams.md).

```mermaid
flowchart TD
    accTitle: ADR-003 eight-step detection algorithm
    accDescr: Load the 90-day window plus 30 extra days for goblin, enumerate signatures, count via each predicate, apply the per-kind threshold, compute the content-addressable id, upsert into ObjectBox, emit a Patterns-list row for any pattern flipped to active, then append a callout if the 3-entry cooldown allows.

    L["load 90d (+30d goblin)"] --> E["enumerate signatures"]
    E --> C["count via per-kind predicate"]
    C --> TH["apply per-kind threshold (≥3 / ≥4)"]
    TH --> ID["compute pattern_id = sha256(kind + signatureKey)"]
    ID --> UP["upsert: new→active+title · active→update · snoozed-expired→active"]
    UP --> ROW["emit Patterns-list row for flipped-to-active"]
    ROW --> CO{"cooldown (3 entries) allows?"}
    CO -- yes --> CALL["append callout (highest supporting; tie → lastSeen)"]
    CO -- no --> Skip(["no callout this entry"])
```

---

## ADR-004 — App Backgrounding & Model-Handle Lifecycle

**Status:** Accepted. Amended by ADR-006 (restart policy) and ADR-007 (state machine).
**Decision:** Conditional foreground service — normal priority by default, promote on first
`extraction_status = RUNNING`, demote after all terminal **+30 s keep-alive**. Notification:
`Reading the entry.`, channel `vestige.local_processing`, importance LOW, tap → History.
**Addendum 2026-05-14:** the 3-screen onboarding hub supersedes the dedicated Screen 3.5; the
notification permission moves to the optional `Notify` switch. Full 5-state + failure machine:
[state-diagrams.md](state-diagrams.md).

---

## ADR-005 — STT-B Scope & v1 Single-Turn (amends ADR-002)

**Status:** Accepted. Amends ADR-002 §Multi-turn / §Q5 / Action Item #1.
**Decision:** The STT-B `retention=0.0/3` verdict is scoped to the prompt-stuffing pattern only
(the SDK stateful path was unmeasured). v1 ships **single-turn-per-capture**: a fresh
`CaptureSession` per record, no prior-turn context, terminal at `RESPONDED` / `ERROR`. The
foreground signature becomes `runForegroundCall(audio, persona)`.
**Addendum 2026-05-15:** pattern callouts — not the follow-up — are the cross-entry surface.

---

## ADR-006 — Foreground Service Restart Policy (amends ADR-004)

**Status:** Accepted. Amends ADR-004 §Crash recovery.
**Decision:** `BackgroundExtractionService.onStartCommand` returns **`START_NOT_STICKY`**
(kills phantom-notification restarts). Crash recovery flows entirely through the ADR-001 Q3
cold-start sweep (`findNonTerminalEntryIds`), not service stickiness. Promote dispatch becomes a
synchronous `onPromoteRequested` callback (no StateFlow replay hazard).

---

## ADR-007 — Foreground Service State Machine Extensions (amends ADR-004)

**Status:** Accepted. Amends ADR-004 §State Machine. Depends on ADR-006.
**Decision:** Add three failure pathways — (1) `DEMOTING → PROMOTING` when work arrives during
demote; (2) `PROMOTING → NORMAL → PROMOTING` via a single bounded 5 s retry on
`onForegroundStartFailed`; (3) any active state `→ PROMOTING` on `onServiceKilled` (OS-only
kill). `onStartCommand` resolves 5 cases by current state. Drawn in
[state-diagrams.md](state-diagrams.md).

---

## ADR-008 — Parallel 3-Lens Execution (SUPERSEDED)

**Status:** **Superseded entirely by ADR-009.** Historical only — recorded for the revival path.
**Decision (historical):** one Engine (weights loaded once), one base Session per entry (shared
prefix), 3 CoW-cloned Sessions appending lens suffixes, fired concurrently (~7–9 s/entry vs
~15–21 s sequential). Action Item 6 = stop-and-supersede on first cloning failure.

```mermaid
flowchart TD
    accTitle: ADR-008 historical parallel-clone lens execution
    accDescr: Historical and superseded. One engine loads weights once. A base session holds the shared prefix. Three copy-on-write cloned sessions append the Literal, Inferential, and Skeptical suffixes and run concurrently into the resolver.

    Eng["Engine (weights once)"] --> Base["base Session (shared prefix)"]
    Base --> Cl1["clone → Literal suffix"]
    Base --> Cl2["clone → Inferential suffix"]
    Base --> Cl3["clone → Skeptical suffix"]
    Cl1 --> R["resolver"]
    Cl2 --> R
    Cl3 --> R
    R --> X["SUPERSEDED by ADR-009 — clone API absent in 0.11.0"]
```

---

## ADR-009 — Kotlin `Session.clone()` Unavailable (supersedes ADR-008)

**Status:** Accepted. Supersedes ADR-008 entirely; **restores ADR-002's sequential rule**.
**Decision:** The clone API is absent in `litertlm-android:0.11.0`. 3 lenses run **sequential**
on one Engine (~5–7 s/lens GPU, 25–55 s/entry). Story 2.6.6 deferred. Revival is
upstream-signal-driven (new release / `main` adds clone / Issue #1226 ships / PR #1515-equiv
merges) — no calendar re-probe.
**Addendum 2026-05-16:** C++ clone is first-class in the docs (CoW, <10 ms); an unpinned
`<latest>` AAR re-probe is required before any post-submission implementation.

---

## ADR-010 — EmbeddingGemma Runtime Swap → LiteRT (supersedes ADR-001 §Embeddings)

**Status:** Accepted. Supersedes ADR-001 Locked-Stack Embeddings row + §Q6.
**Decision:** EmbeddingGemma 300M loads through **LiteRT (TFLite)**, not LiteRT-LM (the HF
artifact ships only `.tflite`). Active path (Addendum 2026-05-11): load via
`localagents-rag:0.3.0` → `GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)`, which bundles
a self-contained `.so` and so avoids the `libLiteRt.so` collision.

```mermaid
flowchart TD
    accTitle: ADR-010 embedder native-library resolution
    accDescr: The embedder loads via localagents-rag GemmaEmbeddingModel, which statically links LiteRT TFLite and SentencePiece into one self-contained native library, avoiding the libLiteRt.so collision with the LiteRT-LM ModelHandle. A pickFirst plus excludes fallback strategy was the alternative if the collision had occurred.

    Art["embeddinggemma-300M .tflite + sentencepiece.model"] --> GEM["GemmaEmbeddingModel (localagents-rag 0.3.0)"]
    GEM --> SO["self-contained libgemma_embedding_model_jni.so<br/>(static LiteRT TFLite + SentencePiece)"]
    SO --> NoClash["no libLiteRt.so collision with ModelHandle"]
    GEM -. fallback if collided .-> PF["pickFirst libLiteRt.so + excludes"]
```

---

## ADR-011 — Design Language: Mist → Scoreboard

**Status:** Accepted. **Visual-only** — supersedes the design-guidelines visual sections + Story
4.1 tokens. All behavioral ADRs (002 / 003 / 004 / 005 / 010) hold unchanged.
**Decision:** Replace Mist with Scoreboard wholesale in `:app/.../ui/**`. New token set
(`lime` = signal, `coral` = heat, never co-occurring; `teal` = resolved), sharper radii, new
motion keyframes, new primitives (`BigStat`, `Pill`, `TraceBarE`, `EyebrowE`, `AppTop`, …);
`MistHero` / `FogDrift` / `NoiseGrain` deleted. Story 4.1.5 carries the rebuild before Story 4.2.

---

## ADR-012 — GPU Inference Performance Gaps

**Status:** Accepted. Decision 1 blocked.
**Decision:** (1) bundle the OpenCL TopK sampler `.so` — **blocked**, not present in the 0.11.0
AAR, so no `jniLibs` addition lands; (2) **pre-warm** the engine on model-ready —
`refreshModelReadiness()` launches `ensureBackgroundEngineInitialized()` when
`ModelReadiness.Ready`, hiding the ~15 s cold init. **Addendum 2026-05-16:** CPU fallback is a
bug to fix at root, not a documented limitation; ~7–11 s/call GPU on E4B is the baseline.

```mermaid
sequenceDiagram
    accTitle: ADR-012 engine pre-warm on model ready
    accDescr: When refreshModelReadiness observes ModelReadiness Ready on app resume, it launches ensureBackgroundEngineInitialized in the background so the roughly fifteen second cold initialization is paid before the first capture instead of during it.

    participant LC as Lifecycle (ON_RESUME)
    participant AC as AppContainer
    participant Eng as Engine
    LC->>AC: refreshModelReadiness()
    AC->>AC: current == ModelReadiness.Ready?
    AC->>Eng: scope.launch { ensureBackgroundEngineInitialized() }
    Eng-->>AC: warm (≈15 s paid off the hot path)
```

---

## ADR-013 — Typed Entry Requires the Foreground Model

**Status:** Accepted. Supersedes the ADR-005-era model-free typed-fallback premise.
**Decision:** Typed runs the **same** foreground call as voice
(`runForegroundTextCall(text, persona)`, `Content.Text`, shared `CaptureViewModel.runForeground`,
same `{transcription, follow_up}` parser). The model is **required**: when
`ModelReadiness != Ready`, `submitTyped` is a silent no-op (parity with a disabled REC). The
old `saveTypedEntry` / typed-`PENDING` branch is **deleted** — no compatibility shim. Flow in
[user-flows.md](user-flows.md).
