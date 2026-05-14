# AGENTS.md — Vestige Build Guardrails

Rules for AI implementors working in this folder.

## Operating Rule

Demo-impact test before accepting any feature or polish: *Does this visibly improve the 90-second pitch or the 5-minute technical walkthrough?* If no, defer to `backlog.md` with: "this doesn't help us win, deferring to v1.5."

## P0 Guardrails

1. Read `README.md` first, then follow its reading order.
2. Treat `concept-locked.md`, `PRD.md`, and accepted ADRs as authoritative.
3. Voice input is Gemma 4 audio only. No Android `SpeechRecognizer`, no cloud STT, no third-party transcription.
4. No analytics, crash-reporting SaaS, RemoteConfig, cloud sync, or telemetry.
5. Model download is the only planned network event. Normal operation is network-sealed.
6. No broad storage permission for app-internal markdown / ObjectBox. Export uses system picker / share flows.
7. No therapy, wellness, mood scoring, gratitude prompts, streaks, badges, mascots, or clinical framing.
8. In-app copy may use "dump." Public copy uses "voice entry," "capture," or "cognitive event" unless quoting UI.
9. Witness, Hardass, Editor are tone variants. They do not fork extraction logic.
10. Templates are model-emitted labels, not user-selected modes.
11. Audio is discarded after inference. Persist transcription text, not audio bytes.
12. Pattern claims source counts, dates, snippets, tags, or field evidence.
13. One inference runtime in v1: LiteRT-LM. llama.cpp / MediaPipe / AICore switches require a superseding ADR.
14. If LiteRT-LM or Gemma 4 audio fails an existential stop-and-test (notably STT-A in Phase 1), stop and replan. Do not wrap a broken premise.
15. Tick checklist items in `docs/stories/phase-{N}-*.md` as work completes. Stories are the work queue; shipped code with unchecked items is unfinished. Can't ship as written → edit the story or push to `backlog.md` before moving on.
16. **No backwards compatibility.** No migration shims, deprecated-kept APIs, "old behavior" flags, or compat wrappers. Design change → old design dies, supersede the ADR, rewrite.
17. **Technical excellence at all times.** No quick fixes, no temp solutions, no `// TODO fix later` in shipped code. If it's not right, don't commit.
18. **All scans pass.** Sonar, Semgrep, Snyk, lint, detekt, ktlint — every finding is a blocker. Fix at root, not at the report.
19. **Codex and Copilot review every PR.** Assume an adversarial automated reviewer is reading the diff. Match documented patterns; cite the ADR / spec when deviating; no unexplained idiom drift.
20. **Stick to the plan.** Story file is the contract. No "while we're here" refactors or new abstraction layers — N lines means N lines.
21. **Stop on manual checks.** When a spec calls for a check the agent can't run (on-device install, real-keystore signing, mic permission, STT-A round-trip, `tcpdump` privacy clip, etc.), stop on the first one and surface it. Do not skip past — a failed check may invalidate later work and breaks atomic-commit discipline. Wait for the user's outcome, record it, then proceed. Don't check the box yourself. Don't fake "looks correct."
22. **Tests and docs ship with the change.** Every code change: pos / neg / err / edge coverage at unit / integration / perf / a11y tiers as applicable. Same commit updates relevant docs (README, ADRs, stories, architecture-brief, design-guidelines, ux-copy) and any diagrams it invalidates. Green tests alone are not "done" — missing scenarios or stale docs / diagrams are unfinished.
23. **ADR amendments.** ADRs are historical; prior decision sections never rewritten after they are on main. Minor refinement → dated `### Addendum (YYYY-MM-DD) — short title` block, additive only. Architecture pivot, runtime swap, premise change → new ADR superseding the prior by number. Judgment: refines an existing rule → addendum; changes what the ADR is *about* → new ADR.
24. **Open PRs only on explicit instruction.** Branch, commit, push are autonomous. `gh pr create` (or equivalent) needs a direct user instruction in the current turn. Default: push, surface, wait. The user decides when a PR opens, what gets bundled, and what stays on the side.
25. **No UI patching.** Screen-level color / contrast / spacing / type overrides used to "just fix this one surface" are quick fixes and are forbidden. If a UX bug appears in multiple places or can recur through shared composition, fix the owning theme token, shared primitive, or common style element instead.
27. **No unnecessary overdocumentation.** Redundant comments, duplicate docs, and prose that restates obvious code are defects. Document only the hidden constraint, operator decision, or onboarding fact a reader would not recover from the code or existing docs.
28. **Test coverage shape must be explicit.** Any touched test suite declares its pos / neg / err / edge scope; user-visible UI suites also assert a11y semantics and tap-target behavior where feasible. If automated accessibility checks are blocked by Robolectric or unavailable device runtime, say so and keep the JVM suite honest with semantics coverage instead of hand-waving.

## Build Order

Build per `docs/PRD.md` §"Build philosophy: build first, test at failure zones." No upfront Phase-0 validation — risk is mitigated via five stop-and-test points (STT-A–E) in phases 1–3.

STT-A (audio plumbing, Phase 1) is existential — time-box hard. Spec rewrites supersede via ADR.

# Android Compose Agent Rules

This repository is Android-only unless explicitly instructed otherwise.

Use Jetpack Compose, Kotlin, Material 3, AndroidX lifecycle/ViewModel, and the existing Gradle version catalog.

Do not introduce:
- Kotlin Multiplatform
- Compose Multiplatform
- commonMain / iosMain source sets
- expect/actual patterns
- CMP resources
- Navigation 3 migration
- new architecture frameworks

Preserve existing architecture unless explicitly asked to migrate.
Do not force MVI/MVVM changes if current project conventions are coherent.

Before editing Gradle:
- inspect libs.versions.toml
- verify AGP, Kotlin, Compose compiler, and dependency versions
- reuse existing aliases
- do not guess Maven coordinates

Testing defaults:
- use existing JVM/Robolectric Compose test setup
- use Turbine for Flow/ViewModel tests when already available
- prefer semantic UI assertions over screenshot tests

Respect project guardrails:
- no telemetry dependencies
- no privacy-invasive SDKs
- no broad dependency changes without explaining why
