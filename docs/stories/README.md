# Stories

Phased build stories that drive the CLI's per-phase work. Each story references the spec docs at the project root for context.

These are *build-driving* stories, not user-experience-narrative stories (those live in `PRD.md`). They tell the CLI what to do next, in what order, with clear acceptance criteria.

## Suggested file naming

`phase-{N}-{short-description}.md` — one file per phase, optionally split if a phase is large.

Current/planned layout:

- `phase-1-scaffold.md` — four-module split per ADR-001 Q1, LiteRT-LM SDK integration, audio pipeline (≤30s + >30s chunking per ADR-001 Q4), **STT-A audio plumbing test (existential)**, ObjectBox schema including `extraction_status` / `attempt_count` / `last_error` (ADR-001 Q3), `ModelArtifactStore` + SHA-256 verification (ADR-001 Q6), `NetworkGate` (ADR-001 Q7), signed dummy release APK (ADR-001 Q5)
- `phase-2-core-loop.md` — capture loop end-to-end, multi-turn session state, foreground extraction, prompt composer + 3-lens resolver per ADR-002, **STT-B multi-turn reliability test, STT-C tag consistency test, STT-D 3-lens convergence test**
- `phase-3-memory-patterns.md` — hybrid retrieval, pattern detection, patterns view, embedding layer integration, **STT-E embeddings vs tag-only test (drop EmbeddingGemma if it doesn't earn its keep)**
- `phase-4-ux-surface.md` — onboarding (model download UX), history, settings, dark mode, error states; re-eval cost confirmation per ADR-002 Q3
- `phase-5-demo-optimization.md` — sample data, demo storyboard execution, dry runs
- `phase-6-submission.md` — final signed release APK, video edit, dev.to post, README, GitHub release, `tcpdump` privacy proof clip
- `phase-7-buffer.md` — bug fixes, P1 polish if scope allows, submit

**Note:** there is no `phase-0-validation.md`. The Phase-0 validation phase was retired in favor of inline stop-and-test points (STT-A through STT-E) embedded in phases 1–3. See `PRD.md` §"Build philosophy: build first, test at failure zones" for the full table.

## Story format (suggested)

```markdown
# Phase {N} — {Title}

**Status:** Not started / In progress / Complete
**Dates:** YYYY-MM-DD – YYYY-MM-DD
**References:** concept-locked.md § {section}, PRD.md § {requirement}, ...

## Goal
One sentence — what this phase produces.

## Acceptance criteria
- [ ] ...
- [ ] ...

## Stories

### Story N.1 — {short title}
**As** {role}, **I need** {capability}, **so that** {outcome}.

**Done when:**
- [ ] ...
- [ ] ...

**Notes / risks:** ...
```

Add stories here as they are written. The CLI uses these as its work queue.
