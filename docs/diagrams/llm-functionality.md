# LLM Functionality

Gemma 4 E4B running on-device via LiteRT-LM. Source: ADR-002 (3-lens × 5-surface, two-tier,
convergence), ADR-005 (single-turn), ADR-008 (concurrent multi-context; v1 sequential pending measurement), ADR-010 (embedder runtime),
`concept-locked.md` (personas, audio, observation layering).

One model artifact (`gemma-4-E4B-it-litert-lm`, 3.66 GB), one `ModelHandle` per process.
Foreground and background inference are **sequential through a single engine behind a `Mutex`** —
recording blocks if a background lens call is running.

---

## 1. 3 lenses × 5 surfaces

5 surfaces = **what** is extracted. 3 lenses = **how** it's framed. Each lens call composes one
lens module with all five surface modules into a single prompt and returns the full schema.
**3 model calls per entry** in the background pass.

```mermaid
flowchart LR
    accTitle: Three lenses by five surfaces extraction matrix
    accDescr: Each of three lenses (Literal, Inferential, Skeptical) is one model call that composes all five surfaces (Behavioral, State, Vocabulary, Commitment, Recurrence) and returns the full schema. Three model calls per entry.

    subgraph surfaces["5 surfaces — WHAT (orthogonal modules)"]
      direction TB
      B["Behavioral → tags"]
      S["State → energy_descriptor"]
      V["Vocabulary → tags + contradictions"]
      C["Commitment → stated_commitment"]
      R["Recurrence → recurrence_link"]
    end

    L1["Lens 1: Literal<br/>only what's explicit"] --> P1["call 1<br/>lens + all 5 surfaces"]
    L2["Lens 2: Inferential<br/>explicit + reasonable inference"] --> P2["call 2<br/>lens + all 5 surfaces"]
    L3["Lens 3: Skeptical<br/>flags contradictions / gaps"] --> P3["call 3<br/>lens + all 5 surfaces"]

    surfaces --> P1
    surfaces --> P2
    surfaces --> P3
    P1 --> RES["→ Convergence Resolver"]
    P2 --> RES
    P3 --> RES
```

---

## 2. Two-tier processing (sequence)

Foreground is fast (transcription + follow-up only, single-turn per ADR-005). Background does the
3-lens convergence over 30–90 s. Audio bytes are discarded immediately after the foreground call.

```mermaid
sequenceDiagram
    accTitle: Two-tier foreground and background inference sequence
    accDescr: User stops recording. The foreground Gemma call returns transcription and follow-up. EntryStore persists markdown first then ObjectBox and marks extraction PENDING. The background pass runs three sequential lens calls, the resolver writes fields, entry observations are generated, then pattern detection runs if the threshold is met.

    actor U as User
    participant Cap as CaptureSession
    participant FG as Gemma (foreground)
    participant ES as EntryStore
    participant BG as Background pass
    participant LM as Gemma (lens calls)
    participant CR as Convergence Resolver
    participant PD as Pattern Detection

    U->>Cap: STOP · FILE IT
    Cap->>FG: audio/text + persona (single-turn)
    FG-->>Cap: { transcription, follow_up }
    Cap->>ES: persist (markdown → ObjectBox), status = PENDING
    Note over FG,Cap: audio bytes discarded now
    ES->>BG: schedule background extraction
    BG->>LM: lens 1 — Literal
    LM-->>BG: full schema
    BG->>LM: lens 2 — Inferential
    LM-->>BG: full schema
    BG->>LM: lens 3 — Skeptical
    LM-->>BG: full schema
    BG->>CR: 3 results
    CR-->>BG: canonical / candidate / ambiguous fields
    BG->>BG: generate entry_observations
    BG->>PD: if ≥10 entries & pattern ≥3 supporting
    PD-->>ES: persist sourced patterns (status = COMPLETED)
```

---

## 3. Convergence resolver — deterministic Kotlin

Not a 4th model call. Per-field agreement predicate decides the verdict.

```mermaid
flowchart TD
    accTitle: Convergence resolver per-field decision
    accDescr: For each field, if two or more of three lenses agree the verdict is canonical, unless Skeptical flags a conflict in which case it is canonical_with_conflict. If only Inferential populated it, the verdict is candidate. If all three disagree, the verdict is ambiguous and the field is saved null with a debug note.

    F(["per field across 3 lens results"]) --> A{"≥2 lenses agree?"}
    A -- yes --> SK{"Skeptical flags<br/>a contradiction?"}
    SK -- no --> CAN["canonical<br/>(saved authoritative)"]
    SK -- yes --> CWC["canonical_with_conflict<br/>(canonical + conflict marker)"]
    A -- no --> ONE{"only Inferential<br/>populated it?"}
    ONE -- yes --> CND["candidate<br/>(low confidence; not used by pattern engine)"]
    ONE -- no --> AMB["ambiguous<br/>(saved null + note)"]

    note["Lens error path: run with surviving 2 (both must agree);<br/>2+ lenses fail ⇒ all fields ambiguous + re-eval suggestion"]
```

---

## 4. Personas — tone only

Witness (default) / Hardass / Editor change **prompt + copy**, never extraction logic. The
chosen persona is recorded per entry so old entries keep their original speaker label.

```mermaid
flowchart LR
    accTitle: Personas are tone-only variants
    accDescr: One extraction pipeline. Witness, Hardass, and Editor are three prompt-and-copy tone variants feeding the same foreground and background logic. Persona is recorded per entry.

    Logic["Single extraction pipeline<br/>(observe behavior · refuse performed validation · pattern not psychology)"]
    W["Witness<br/>observes · names pattern · quiet"] --> Logic
    H["Hardass<br/>sharper · less padding · more action"] --> Logic
    E["Editor<br/>cuts vague vocabulary"] --> Logic
    Logic --> Rec["recorded per entry: persona = witness | hardass | editor"]
```

---

## 5. Embeddings & retrieval (STT-E-gated)

P0 retrieval is keyword + Gemma-extracted tags + recency. The semantic vector layer
(EmbeddingGemma 300M via LiteRT, loaded through `GemmaEmbeddingModel` / `localagents-rag` — a
separate native `.so` from `ModelHandle`) ships **only if STT-E passes**; otherwise it drops to
v1.5. The embedding target is a post-convergence **synthesis string** (tags + observations +
commitment), not the raw transcription.

```mermaid
flowchart TD
    accTitle: Hybrid retrieval with optional vector layer
    accDescr: Baseline retrieval is keyword plus tags plus recency. If STT-E passes, a vector similarity layer using EmbeddingGemma over a post-convergence synthesis string is added to protect pattern detection from vocabulary drift. If STT-E fails, the vector layer defers to v1.5.

    Q(["query / pattern match"]) --> Base["Baseline: keyword + tags + recency"]
    Base --> Gate{"STT-E passed?"}
    Gate -- yes --> Vec["+ vector similarity<br/>EmbeddingGemma over synthesis string<br/>(tags · observations · commitment)"]
    Gate -- no --> V15["vector layer → v1.5"]
    Vec --> Out(["ranked results"])
    Base --> Out
```
