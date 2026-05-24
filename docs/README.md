# Vestige — Docs Index 📚

The canonical product, architecture, and UX spec for Vestige — the on-device cognition tracker built
with Gemma 4 for the Gemma 4 Challenge ("Build with Gemma 4"). Locked decisions in these docs are
authoritative; the diagrams are a lens on the prose, not a separate source of truth. For the
**current build Status** and **Known Limitations**, read the root [`../README.md`](../README.md) —
this index does not hold the canonical status.

---

## Reading order

1. **`concept-locked.md`** — full product spec. Read first.
2. **`PRD.md`** — P0/P1/P2 requirements, acceptance criteria, phase schedule.
3. **[`../AGENTS.md`](../AGENTS.md)** — implementor guardrails (repo root). Read before changing anything.
4. **`architecture-brief.md`** — module ownership, AppContainer wiring, data flow.
5. **`adrs/`** — architecture decision records (ADR-001..018, no ADR-009).
6. **`stories/`** — phased build queue. Drives what to work on now.
7. **`design-guidelines.md`** + **`ux-copy.md`** — when implementing any UI surface.
8. **`backlog.md`** — when tempted to add scope. Reference, then say no.

---

## Product spec

| File | What it is |
|---|---|
| `concept-locked.md` | Canonical product spec — templates-as-labels, 3×5 extraction, schema, personas, voice rules, privacy claims |
| `PRD.md` | P0 / P1 / P2 requirements, acceptance criteria, phase schedule, build philosophy |
| `challenge-brief.md` | Challenge rules, judging criteria, submission requirements |
| `runtime-research.md` | Android stack rationale (LiteRT-LM SDK) with sources |
| `sample-data-scenarios.md` | Prepared validation transcripts (tag consistency, lens divergence, embedding vs tag-only) |
| `spec-pattern-action-buttons.md` | Pattern action-button behavior spec (Skip / Drop / Restart) |

## Architecture & ADRs

| File | What it is |
|---|---|
| `architecture-brief.md` | Module ownership, AppContainer singletons, data flow, ObjectBox shape |
| `adrs/` | Decision records ADR-001..018 (no ADR-009). Stack, lenses, patterns, lifecycle, runtime, design pivot, foreground/background split, ObjectBox source of truth |

## Design & UX

| File | What it is |
|---|---|
| `design-guidelines.md` | Visual system, microcopy register, screen specs, persona voices, accessibility rules |
| `ux-copy.md` | Locked copy strings for every UI surface — pull strings directly from here |

## Diagrams

| File | What it covers |
|---|---|
| `diagrams/README.md` | Diagram atlas index + template-vocabulary mapping |
| `diagrams/architecture.md` | 4-module split, AppContainer DI, NetworkGate, capture→storage dataflow |
| `diagrams/llm-functionality.md` | Gemma 4 E4B + LiteRT-LM, 3-lens × 5-surface, two-tier processing, convergence, personas, Vocab Drift embeddings |
| `diagrams/user-flows.md` | Onboarding, voice + typed capture, history, patterns, settings, model lifecycle |
| `diagrams/state-diagrams.md` | Capture UI state, pattern lifecycle, ModelReadiness, download phases, extraction status, foreground service |
| `diagrams/adr-decisions.md` | Decision + diagram per live ADR, plus the supersession graph |

## Stories

| File | What it is |
|---|---|
| `stories/README.md` | Story format + how the build queue works |
| `stories/phase-1..7-*.md` | Phased build queue — scaffold, core loop, memory/patterns, UX surface, demo optimization, submission, buffer |

## STT results

| File | What it is |
|---|---|
| `stt-results/README.md` | STT evidence archive index + filename/format conventions |
| `stt-results/stt-*-*.md` + `.raw.log` | On-device measurement captures (STT-D lens divergence, STT-E embeddings, STT-F..H prompt tuning) |
| `stt-results/*-manifest.example.txt` | Example artifact manifests for reproducing a run |
