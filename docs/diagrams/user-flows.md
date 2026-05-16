# User Flows

End-to-end paths through the shipped v1 surfaces. Source: ADR-004 Addendum (onboarding hub),
`ux-copy.md` + `spec-pattern-action-buttons.md` (patterns, settings), ADR-013 (typed parity),
`concept-locked.md` (capture, history).

---

## 1. Onboarding — 3-screen hub

Not a queue — a hub. Only the **Local** row gates entry; Mic and Notify are optional capability
switches that never block.

```mermaid
flowchart TD
    accTitle: Onboarding three-screen hub flow
    accDescr: Screen one picks a persona. Screen two is a wiring hub with Persona, Local, Mic, Notify, and Type rows; only the Local row gates entry. If the model is not present the user goes to Screen three to download it, then auto-returns to the hub. Open Vestige is enabled only when the Local row is green.

    S1["Screen 1 — Pick a persona<br/>Witness (default) · Hardass · Editor"] --> S2
    S2["Screen 2 — Wiring hub<br/>rows: Persona · Local · Mic · Notify · Type"]
    S2 --> LOCAL{"Local row green?<br/>(model verified)"}
    LOCAL -- no, Wi-Fi up --> S3["Screen 3 — Model download<br/>bytes/total/ETA · Pause"]
    LOCAL -- no, no Wi-Fi --> WIFI["open Wi-Fi settings<br/>(no dead-end screen)"]
    WIFI --> S2
    S3 --> S2
    LOCAL -- yes --> OPEN(["Open Vestige (enabled)"])
```

---

## 2. Voice capture

```mermaid
flowchart TD
    accTitle: Voice capture flow
    accDescr: Tap record to enter RECORDING. Discard during recording ends silently with no entry. Stop and file it triggers the foreground Gemma call returning transcription and follow-up, persists markdown then ObjectBox, then runs the background three-lens extraction, convergence, observations, and pattern detection.

    IDLE(["IDLE"]) -- "tap Record" --> REC["RECORDING"]
    REC -- "DISCARD · NO SAVE<br/>(single tap, no confirm, silent)" --> IDLE
    REC -- "STOP · FILE IT" --> FG["Foreground Gemma call<br/>→ transcription + follow-up"]
    FG --> PERSIST["EntryStore: markdown → ObjectBox<br/>(audio discarded now)"]
    PERSIST --> SHOW["show exchange<br/>(transcription dimmed · follow-up primary)"]
    PERSIST --> BG["background: 3 sequential lenses → resolver →<br/>entry_observations → pattern detection if threshold"]
    SHOW --> IDLE
```

---

## 3. Typed capture (ADR-013 — model required)

Typed runs the **same** foreground call as voice. No model-free fallback: if `ModelReadiness`
is not `Ready`, `submitTyped` is a silent no-op (parity with a disabled REC button).

```mermaid
flowchart TD
    accTitle: Typed capture flow requires foreground model
    accDescr: The user taps Type and enters text. If the model readiness is Ready, Log entry runs the same foreground call and background pipeline as voice with follow-up null for typed. If the model is not Ready, submit is a silent no-op matching the disabled record button.

    T(["tap Type → 'What just happened.'"]) --> RDY{"ModelReadiness == Ready?"}
    RDY -- no --> NOOP["silent no-op<br/>(parity with disabled REC)"]
    RDY -- yes --> LOG["Log entry → same foreground call<br/>(Content.Text · follow_up = null for typed)"]
    LOG --> BG["same background pipeline as voice"]
```

---

## 4. Patterns — list → detail → actions

Sections render only when non-empty, fixed order. User actions are **Skip / Drop / Restart**,
each with a ~4 s Undo snackbar. `CLOSED · DONE` is model-detected only (v1.5) — no user Close.

```mermaid
flowchart TD
    accTitle: Patterns list, detail, and lifecycle actions
    accDescr: The patterns list shows non-empty sections in fixed order ACTIVE, SKIPPED, CLOSED, DROPPED. A card opens detail with sourced evidence. From overflow or the detail action row the user can Skip (returns in 7 days), Drop (record kept), or Restart a non-active pattern, each with an Undo snackbar. Close is model-detected only.

    LIST["Patterns list<br/>ACTIVE — STILL HITTING · SKIPPED · ON HOLD · CLOSED · DONE · DROPPED"]
    LIST -- "tap card" --> DET["Pattern detail<br/>name · template label · sourced evidence · 30-day TraceBar"]
    DET --> ACT{"action"}
    LIST -- "overflow" --> ACT
    ACT -- "Skip" --> SK["→ SKIPPED · 7-day wake-up · Undo"]
    ACT -- "Drop" --> DR["→ DROPPED · record kept · Undo"]
    ACT -- "Restart (non-active)" --> RS["→ ACTIVE · 'Pattern is back.' · Undo"]
    ACT -. "Close (model-detected, v1.5 — not a user action)" .-> CL["→ CLOSED · DONE"]
```

---

## 5. Settings & model lifecycle

Settings P0: Persona · Data (export / wipe) · Model (delegates to the Local Model Status screen) ·
About. Export is SAF `CreateDocument` (a copy, no storage permission); wipe is type-`DELETE`-gated
and returns to first-run.

```mermaid
flowchart TD
    accTitle: Settings sections and model lifecycle
    accDescr: Settings has Persona, Data, Model, and About sections. Export writes a SAF zip of the entry markdown files as a copy. Delete all data requires typing DELETE then wipes ObjectBox, markdown, and onboarding prefs and returns to first run. The Model row opens Model Status where Re-download and Delete live; re-download replaces the file with entries untouched, a failed re-download falls back to Loading.

    SET["Settings"] --> PER["Persona<br/>(3 names)"]
    SET --> DATA["Data"]
    SET --> MOD["Model status row"]
    SET --> ABT["About<br/>version · source · license"]

    DATA --> EXP["Export all entries<br/>SAF markdown zip (copy; failures surface)"]
    DATA --> WIPE["Delete all data<br/>type DELETE → wipe ObjectBox + markdown + prefs → first-run"]

    MOD --> MS["Local Model Status screen"]
    MS --> RDL["Re-download<br/>~3.7 GB · replaces file · entries untouched"]
    MS --> DEL["Delete model<br/>app inert until re-download · entries stay"]
    RDL -- "failure" --> LOAD["→ Loading (no model on disk)"]
```
