# Vestige — CLI Build Handoff

On-device cognition tracker built with Gemma 4 for the Gemma 4 Challenge ("Build with Gemma 4"). Submission deadline: 2026-05-24, 23:59 PDT.

This folder is the canonical source-of-truth for the v1 build. The CLI agent reads from here. **Locked decisions in these docs are authoritative.**

---

## Reading order (new session orientation)

1. **`concept-locked.md`** — full product spec. Read first.
2. **`PRD.md`** — formal P0/P1/P2 requirements + acceptance criteria + phase schedule + the five stop-and-test points (build philosophy section). Read second.
3. **[`../AGENTS.md`](../AGENTS.md)** — implementor guardrails and non-negotiables. Lives at repo root. Read before changing anything.
4. **`adrs/`** — architecture decision records. ADR-001 (stack & build infra), ADR-002 (multi-lens extraction pattern), ADR-003 (pattern detection algorithm & persistence), ADR-004 (app backgrounding & model-handle lifecycle), ADR-005 (STT-B scope correction + v1 single-turn decision), ADR-006 (foreground service restart policy), ADR-007 (foreground service state machine extensions), ADR-008 (parallel lens execution via Engine/Session — superseded by ADR-009), ADR-009 (LiteRT-LM Kotlin Session.clone() unavailable; restores ADR-002 sequential for v1). Read before Phase 1.
5. **`architecture-brief.md`** — module ownership, data flow, and Phase-1 build sequence.
6. **`stories/`** — phased build stories. Drives what to work on right now.
7. **`design-guidelines.md`** + **`ux-copy.md`** — when implementing UI for any screen.
8. **`runtime-research.md`** — when setting up the Android stack in Phase 1.
9. **`backlog.md`** — when tempted to add features. Reference, then say no.

---

## Files at root

| File | Purpose |
|---|---|
| `concept-locked.md` | Canonical product spec — templates-as-labels, multi-lens architecture (3×5), schema, personas, voice rules, privacy claims |
| `PRD.md` | Formal requirements: P0 (must-have) / P1 (nice-to-have) / P2 (future). Acceptance criteria + phase schedule with dates |
| `architecture-brief.md` | Module ownership, AppContainer singletons, data flow, ObjectBox shape, Phase-1 build sequence |
| `adrs/` | Architecture decision records. ADR-001 stack & build infra, ADR-002 multi-lens extraction pattern, ADR-003 pattern detection algorithm & persistence, ADR-004 app backgrounding & model-handle lifecycle, ADR-005 STT-B scope correction + v1 single-turn decision, ADR-006 foreground service restart policy, ADR-007 foreground service state machine extensions, ADR-008 parallel lens execution via Engine/Session (superseded by ADR-009), ADR-009 LiteRT-LM Kotlin Session.clone() unavailable + ADR-002 sequential restored. |
| `design-guidelines.md` | Visual system, microcopy register, screen specs, persona voices, anti-patterns, accessibility rules |
| `ux-copy.md` | Literal copy strings for every UI surface. Pull strings directly from here. |
| `runtime-research.md` | Android stack rationale (LiteRT-LM SDK) with sources |
| `challenge-brief.md` | Challenge rules, judging criteria, submission requirements |
| `backlog.md` | Scope shield — features deferred from v1 with "what would unblock" rationale per entry |
| `blog-template.md` | dev.to submission scaffolding (used in Phase 6) |
| `sample-data-scenarios.md` | Prepared validation transcripts for STT-C (tag consistency), STT-D (lens divergence), STT-E (embedding vs tag-only) |
| `stories/` | Phased build stories. Read `stories/README.md` for format. |

---

## Scope rule (mandatory)

This is a 17-day build aimed at winning the May 24 challenge submission. Decision criterion for any change request:

> Does it visibly improve the 90-second pitch or the 5-minute technical walkthrough?

If no → reject, log to `backlog.md`, respond: *"this doesn't help us win, deferring to v1.5."* Polish invisible in the demo is deferred.

**Submission category:** Build with Gemma 4 only (one of five $500 + DEV++ + badge slots). The challenge has 10 total winners across Build and Write; Vestige does not submit to Write. Required tags on dev.to: `devchallenge`, `gemmachallenge`, `gemma`.

**Build philosophy:** No upfront Phase 0 validation. Build directly. Risk is managed via five **stop-and-test points** at known failure zones during phases 1–3 (see `PRD.md` §"Build philosophy: build first, test at failure zones"). Each STT has a clear failure mode and a clear contingency. STT-A (audio plumbing) is the existential one — time-box hard.

**In-app vs public copy:** The brand voice uses "dump" in-app (capture screen, microcopy, persona lines). Public-facing surfaces — README, dev.to post, demo narration — use "voice entry," "capture," or "cognitive event" unless intentionally quoting the in-app voice. Judge legibility wins over brand bite for the submission package.

---

## Reference device & stack

- **Device:** Galaxy S24 Ultra (12 GB RAM, SD8 Gen 3) is the reference. Min spec for the post: Android 14+, 8 GB RAM, **6 GB free storage** (model 3.66 GB + markdown/ObjectBox + temp downloads + export room). **7 GB recommended if EmbeddingGemma ships** (adds the embedding artifact plus vector index room).
- **Stack:** see `concept-locked.md` §Stack for the canonical list (LiteRT-LM SDK, Kotlin + Compose + Material 3, ObjectBox + markdown). Build-infra and module-layout decisions live in `adrs/ADR-001-stack-and-build-infra.md`.

---

## Stop-and-test points

Five stop-and-test points replace the previous Phase 0 phase. Full table with failure modes and contingencies lives in `PRD.md` §"Build philosophy: build first, test at failure zones". `adrs/ADR-002-multi-lens-extraction-pattern.md` covers the architectural stakes of STT-D.

Quick reference:

- **STT-A** *(Phase 1)* — audio input plumbing. Existential. If broken, replan.
- **STT-B** *(Phase 2)* — multi-turn reliability on E4B. If broken, drop to single-turn.
- **STT-C** *(Phase 2)* — tag extraction consistency. If broken, accept noisy patterns or tighten prompts.
- **STT-D** *(Phase 2)* — 3-lens convergence differs sometimes. If broken, drop to single-pass + remove Reading.
- **STT-E** *(Phase 3)* — embeddings outperform tag-only. If broken, ship without embeddings; defer to v1.5.

---

## What is still being written

- `pre-mortem.md` — risks and mitigations.
- `demo-storyboard.md` — exact 90s pitch + 5-min walkthrough beats. Required by Phase 5. Same name used in PRD Open Questions; do not create a duplicate doc.

Phase story files (`phase-1-scaffold.md` through `phase-7-buffer.md`) are present in `stories/` — that's the active work queue. There is no Phase-0 validation phase; build directly per PRD §"Build philosophy: build first, test at failure zones".

**Naming/ownership rules to prevent duplicates:**
- Font/type system decisions land in `design-guidelines.md`, never in a new doc.
- Sample-data narratives land in `sample-data-scenarios.md`, never inline in PRD.
- Demo storyboard beats land in `demo-storyboard.md`, never inline in concept-locked.

When the unwritten docs land, this section gets pruned.
