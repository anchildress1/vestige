# AGENTS.md — Vestige Guardrails

## Demo gate
- Every feature/polish: improves 90s pitch or 5min walkthrough? No → `backlog.md` v1.5 with rationale.

## Privacy & runtime
- Voice = Gemma 4 audio only. No `SpeechRecognizer`, no cloud STT, no third-party transcription.
- No analytics, crash-SaaS, RemoteConfig, cloud sync, telemetry. No privacy-invasive SDKs.
- Network = model download only. Normal ops sealed.
- No broad storage permission. Export via system picker / share.
- Audio discarded after inference. Persist transcription text only.
- One inference runtime in v1: LiteRT-LM.

## Product surface
- No therapy / wellness / mood scoring / gratitude / streaks / badges / mascots / clinical framing.
- In-app copy may use "dump." Public copy uses "voice entry" / "capture" / "cognitive event."
- Personas (Witness / Hardass / Editor) are tone variants only — no extraction-logic forks.
- Templates are model-emitted labels, not user-selected modes.
- Pattern claims source counts, dates, snippets, tags, or field evidence.

## Stories as work queue
- Tick `docs/stories/phase-{N}-*.md` checkboxes as work completes.
- N lines = N lines. No "while we're here" refactors or new abstraction layers.
- Can't ship as written → edit the story or push to `backlog.md` before moving on.

## Atomic correctness
- Accuracy over speed. Verify facts, behavior, and assumptions before optimizing for turnaround.
- Long-term maintainability and reliability beat challenge-deadline panic. The human owns the deadline; the agent owns code quality.
- No backwards compatibility. Design change → rewrite + supersede ADR. No shims, no deprecated-kept APIs, no compat wrappers.
- No quick fixes, no temp solutions, no `// TODO fix later` in shipped code.
- All scans pass (Sonar, Semgrep, Snyk, lint, detekt, ktlint). Every finding is a blocker. Fix at root.
- Codex + Copilot review every PR. Match documented patterns; cite ADR / spec when deviating; no unexplained idiom drift.

## Manual-check stop
- Unrunnable check (on-device install, keystore, mic permission, STT-A round-trip, `tcpdump` privacy clip, etc.) → stop on the first one, surface, wait for user outcome. Don't check the box yourself.
- Existential STT failure (notably STT-A) → stop and replan. Don't wrap a broken premise.

## Tests + docs ship together
- Every code change: pos / neg / err / edge across unit / integration / perf / a11y tiers as applicable.
- Before `lefthook` pre-push: run targeted tests for the touched area, not the full suite. The hook already owns the full pre-push gate.
- Same commit updates README / ADRs / stories / architecture-brief / design-guidelines / ux-copy / diagrams the change invalidates.
- Coverage shape declared explicitly per touched suite. UI suites assert a11y semantics + tap-target where feasible. Robolectric-blocked a11y → say so + keep JVM suite honest with semantics coverage.
- **Band a11y coverage is a blocker, same gate as test coverage.** Every inline status / error / diagnostic band ships with role + `contentDescription` + `liveRegion` + click-action presence/absence asserted at the unit tier.

## ADR discipline
- ADRs are architecture, not design-change logs. Token swaps, copy changes, verification milestones, follow-up impl choices → `design-guidelines.md` / `ux-copy.md` / story / commit message.
- Prior decision sections never rewritten after main.
- Minor refinement → dated `### Addendum (YYYY-MM-DD) — title` block, additive only.
- Architecture pivot / runtime swap / premise change → new ADR superseding the prior by number.

## PR lifecycle
- Branch, commit, push are autonomous.
- `gh pr create` requires explicit user instruction in the current turn. Default: push, surface, wait.

## UI discipline
- No per-surface color / contrast / spacing / type overrides. Recurring UX bugs fix the owning theme token / shared primitive / common style element.
- Comment only hidden constraints / operator decisions / onboarding facts not recoverable from code or existing docs.

## Compose work
- Anything touching `@Composable`, `LaunchedEffect` / `DisposableEffect` / `rememberCoroutineScope`, state modeling, Compose perf / a11y / theming, or onboarding-flow architecture → invoke `/compose-skill` first.

## LiteRT-LM work
- Anything touching `LiteRtLmEngine`, `GemmaTextEmbedder`, `ForegroundInference`, `BackgroundExtractionWorker`, `EmbeddingArtifactManifest`, model loading, audio path (`audioBackend`), inference config, or model artifact management → invoke `/litertlm-android-sdk` first.

## Stack constraints
- Android-only. Do not introduce KMP / CMP / `commonMain` / `iosMain` / `expect`-`actual` / Nav 3 migration / new architecture frameworks.
- Don't force MVI / MVVM if existing conventions are coherent.
