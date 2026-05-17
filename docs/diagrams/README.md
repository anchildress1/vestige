# Vestige — Diagram Atlas 🗺️

Visual companion to the canonical spec. Every diagram here is **Mermaid** (renders natively on
GitHub, diffs in PRs, no binary assets) and is **derived from the docs as written**, assuming the
**full v1 feature set is complete**.

Authority order if anything here disagrees with prose: the ADRs and `concept-locked.md` /
`ux-copy.md` win — these pages are a lens on those, not a new source of truth.

---

## Pages

| Page | What it covers |
|---|---|
| [architecture.md](architecture.md) | 4-module split, `AppContainer` DI, `NetworkGate`, capture→storage dataflow |
| [llm-functionality.md](llm-functionality.md) | Gemma 4 E4B + LiteRT-LM, 3-lens × 5-surface, two-tier processing, convergence, personas, embeddings |
| [user-flows.md](user-flows.md) | Onboarding, voice + typed capture, history, patterns, settings, model lifecycle |
| [state-diagrams.md](state-diagrams.md) | capture UI state, pattern lifecycle, ModelReadiness, download phases, extraction status, foreground service |
| [adr-decisions.md](adr-decisions.md) | ADR-001…013 — decision + diagram per ADR, plus the supersession graph |

---

## Two things to know before reading

**Dual template vocabulary.** The product spec (`concept-locked.md`, authoritative) uses
`Crashed / Deep Space / Busy Stalling / Nonstop Spiral / Goblin Hours / Brain Dump`. ADR-002's
agreement predicate and the storage enum are written with the older positional names
(`Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit`). They map
1:1. **User-facing diagrams use the `concept-locked` names; the ADR-002 page quotes the enum form
as written.**

**`CLOSED` is model-only.** The pattern lifecycle includes `CLOSED · DONE`, but no *user* action
reaches it — closure is model-detected (v1.5 `pattern-auto-close`). User actions are
`Skip` / `Drop` / `Restart`. Diagrams label system vs. user transitions explicitly.

---

## Accessibility

Every diagram carries `accTitle` / `accDescr` for screen readers and renders on the default
Mermaid profile (no custom theme) for deterministic output. All diagram source in this folder was
syntax-validated before commit.
