# Architecture

How the components fit together. Source: ADR-001 (stack, module split, `NetworkGate`,
`AppContainer`), `architecture-brief.md` (dataflow, storage contract), ADR-010 (embedder runtime).

---

## 1. Four-module dependency graph

Acyclic, fan-in to `:app`. Core modules never depend on `:app`.

```mermaid
flowchart TD
    accTitle: Vestige Gradle module dependency graph
    accDescr: app depends on core-inference, core-storage, and core-model. core-inference depends on core-model and core-storage interfaces. core-storage depends on core-model. core-model depends on nothing.

    App[":app<br/>Compose UI · navigation · AppContainer · permissions"]
    Inf[":core-inference<br/>LiteRT-LM wrapper · audio norm · prompt composition · convergence resolver"]
    Sto[":core-storage<br/>ObjectBox · markdown SOT · keyword/tag/recency retrieval"]
    Mod[":core-model<br/>domain types · manifests · status enums · no Android deps"]

    App --> Inf
    App --> Sto
    App --> Mod
    Inf --> Mod
    Inf -. interfaces only .-> Sto
    Sto --> Mod
```

---

## 2. AppContainer — manual constructor DI

One container, constructed once in `Application.onCreate`. No Hilt, no service locator. Process-
scoped singletons; one capture-scoped holder.

```mermaid
flowchart TB
    accTitle: AppContainer composition root
    accDescr: Application onCreate constructs AppContainer once, which holds process-scoped singletons ModelArtifactStore, ModelHandle, Embedder, NetworkGate, EntryStore, PatternStore, RetrievalRepo, InferenceCoordinator, and a capture-scoped SessionState.

    OnCreate(["Application.onCreate()"]) --> AC["AppContainer<br/>(constructed once)"]

    subgraph proc["process-scoped singletons"]
      MAS["ModelArtifactStore<br/>path · download · SHA-256 · re-download · delete"]
      MH["ModelHandle<br/>lazy after artifact verified · one engine/process"]
      EMB["Embedder<br/>lazy · STT-E-contingent"]
      NG["NetworkGate<br/>OPEN only during download"]
      ES["EntryStore<br/>markdown-first, ObjectBox-second"]
      PS["PatternStore<br/>persistence · lifecycle SM · detection algo"]
      RR["RetrievalRepo<br/>keyword + tag + recency (+ vector if STT-E)"]
      IC["InferenceCoordinator<br/>fg call · bg sequential lenses · resolver"]
    end

    AC --> MAS
    AC --> MH
    AC --> EMB
    AC --> NG
    AC --> ES
    AC --> PS
    AC --> RR
    AC --> IC
    AC --> SS["SessionState<br/>(capture-scoped: active persona + CaptureViewModel.CaptureUiState)"]
```

---

## 3. NetworkGate — the only HTTP path

Default `SEALED`. `OPEN` exclusively for the model/artifact download, re-sealed the instant it
completes. Any outbound construction outside the gate is grep-blocked in CI (ADR-001 §Q7).

```mermaid
stateDiagram-v2
    accTitle: NetworkGate state machine
    accDescr: NetworkGate starts SEALED. It transitions to OPEN only when a model or artifact download starts, and returns to SEALED the instant the download completes or fails. Any outbound call while SEALED asserts.

    [*] --> SEALED
    SEALED --> OPEN: model / artifact download starts
    OPEN --> SEALED: download complete or failed
    SEALED --> SEALED: outbound call ⇒ assertion (forbidden)
```

---

## 4. Capture → inference → resolver → storage → patterns

The end-to-end dataflow. `EntryStore` writes **markdown first, ObjectBox second** as one
transactional unit — markdown is the source of truth; a missing ObjectBox row rebuilds from
markdown on next cold start, never the reverse.

```mermaid
flowchart TB
    accTitle: End-to-end capture and extraction dataflow
    accDescr: User records or types. Voice goes through AudioRecord then audio normalization to mono 16kHz float32 max 30s, then a foreground Gemma call returns transcription and follow-up. EntryStore persists markdown first then ObjectBox. A background pass runs three sequential lens calls, the convergence resolver writes canonical, candidate, or ambiguous fields plus entry observations, then pattern detection runs when the threshold is met.

    U(["User"]) -- voice --> AR["AudioRecord capture"]
    U -- type --> FG
    AR --> NORM["audio normalize<br/>mono 16 kHz f32 · ≤30 s"]
    NORM --> FG["Foreground Gemma call<br/>→ transcription + follow-up"]
    FG --> ES["EntryStore.persist<br/>markdown FIRST → ObjectBox SECOND"]
    ES --> BG["Background pass<br/>3 sequential lens calls (Literal→Inferential→Skeptical)"]
    BG --> CR["Convergence Resolver<br/>canonical · candidate · ambiguous · canonical_with_conflict"]
    CR --> OBS["entry_observations<br/>generated from transcript + resolved fields"]
    OBS --> PD{"≥10 entries AND<br/>pattern ≥3 supporting?"}
    PD -- yes --> PAT["Pattern detection<br/>persist sourced patterns + callout (cooldown 3)"]
    PD -- no --> Done(["done — observation only"])
    PAT --> Done
```
