# AGENTS.md — Vestige Build Guardrails

These rules apply to AI implementors working in this folder.

## Operating Rule

Vestige is a challenge build, not an infinite product garden. Before accepting any feature or polish request, ask:

> Does this visibly improve the 90-second pitch or the 5-minute technical walkthrough?

If no, defer it to `backlog.md` and say: "this doesn't help us win, deferring to v1.5."

## P0 Guardrails

1. Read `README.md` first, then follow its reading order.
2. Treat `concept-locked.md`, `PRD.md`, and accepted ADRs as authoritative.
3. Do not use Android `SpeechRecognizer`, cloud STT, or third-party transcription. Voice input is Gemma 4 audio input or the feature is not the feature.
4. Do not add analytics, crash-reporting SaaS, RemoteConfig, cloud sync, or telemetry.
5. Model download is the only planned network event. Normal operation must be network-sealed.
6. Do not request broad storage permission for app-internal markdown/ObjectBox storage. Use system picker/share flows for export.
7. Do not add therapy, wellness, mood scoring, gratitude prompts, streaks, badges, mascots, or clinical framing.
8. In-app copy may use "dump"; public copy uses "voice entry," "capture," or "cognitive event" unless intentionally quoting UI.
9. Witness, Hardass, and Editor are tone variants only. They must not fork extraction logic.
10. Templates are model-emitted labels, not user-selected modes.
11. Audio is discarded after inference. Persist transcription text, not audio bytes.
12. Pattern claims must be sourced with counts, dates, snippets, tags, or field evidence.
13. Use one inference runtime in v1: LiteRT-LM. Any llama.cpp / MediaPipe / AICore switch requires a superseding ADR.
14. If LiteRT-LM or Gemma 4 audio fails an existential stop-and-test (notably STT-A audio plumbing in Phase 1), stop and replan. Do not build a pretty wrapper around a broken premise. We have standards, allegedly.
15. Tick checklist items off the active phase story file in `docs/stories/phase-{N}-*.md` as work completes. Stories are the canonical work queue; an unchecked item with shipped code is an unfinished story, not a victory lap. If a story can't ship as written, edit the story (or push it to `backlog.md`) before moving on — don't ghost the checklist.
16. **No backwards compatibility.** Vestige is a v1 challenge submission, not a long-running product. Do not add migration shims, deprecated-but-kept APIs, feature flags for "old behavior," or compatibility wrappers. If a design changes, the old design dies — supersede the ADR and rewrite the code.
17. **Technical excellence at all times.** This is a public submission judged against other submissions. Code quality is part of the deliverable. No quick fixes, no temp solutions, no `// TODO fix later` for things that ship. If it's not right, do not commit it.
18. **All scans must pass at all times.** Sonar, Semgrep, and Snyk findings are blockers, not warnings. Lint, detekt, and ktlint findings are blockers, not warnings. A failing scan is a failing build. Fix at root, not at the report level.
19. **Codex and Copilot will review every PR.** Assume an adversarial automated reviewer is reading the diff. Match documented patterns; cite the ADR/spec when deviating; do not leave unexplained idiom drift.
20. **Stick to the plan.** Do not overbuild or over-deliver. The story file is the contract. If a story can ship as N lines, it ships as N lines — not N + a refactor + a new abstraction layer "while we're here." Scope creep is rejected at review.

## Build Order

Build directly per `docs/PRD.md` §"Build philosophy: build first, test at failure zones." There is no upfront Phase-0 validation phase. Risk is mitigated through five stop-and-test points (STT-A–E) embedded in phases 1–3.

STT-A (audio plumbing, Phase 1) is the existential one — time-box hard. If the specs are intentionally rewritten, supersede via ADR.

