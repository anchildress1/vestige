# AGENTS.md — Vestige Guardrails

## Demo gate
- Every feature/polish: improves 90s pitch or 5min walkthrough? No → `backlog.md` v1.5 with rationale.

## Authoritative docs
- `README.md` first; follow its reading order.
- `concept-locked.md`, `PRD.md`, accepted ADRs are authoritative.

## Privacy & runtime
- Voice = Gemma 4 audio only. No `SpeechRecognizer`, no cloud STT, no third-party transcription.
- No analytics, crash-SaaS, RemoteConfig, cloud sync, telemetry. No privacy-invasive SDKs.
- Network = model download only. Normal ops sealed.
- No broad storage permission. Export via system picker / share.
- Audio discarded after inference. Persist transcription text only.
- One inference runtime in v1: LiteRT-LM. Switches require superseding ADR.

## Product surface
- No therapy / wellness / mood scoring / gratitude / streaks / badges / mascots / clinical framing.
- In-app copy may use "dump." Public copy uses "voice entry" / "capture" / "cognitive event."
- Personas (Witness / Hardass / Editor) are tone variants only — no extraction-logic forks.
- Templates are model-emitted labels, not user-selected modes.
- Pattern claims source counts, dates, snippets, tags, or field evidence.

## STT failure
- LiteRT-LM or Gemma 4 audio failing an existential STT (notably STT-A) → stop and replan. Don't wrap a broken premise.

## Stories as work queue
- Tick `docs/stories/phase-{N}-*.md` checkboxes as work completes.
- N lines = N lines. No "while we're here" refactors or new abstraction layers.
- Shipped code with unchecked items is unfinished. Can't ship → edit story or push to `backlog.md` before moving on.

## Atomic correctness
- No backwards compatibility. Design change → rewrite + supersede ADR. No shims, no deprecated-kept APIs, no compat wrappers.
- No quick fixes, no temp solutions, no `// TODO fix later` in shipped code.
- All scans pass (Sonar, Semgrep, Snyk, lint, detekt, ktlint). Every finding is a blocker. Fix at root.
- Codex + Copilot review every PR. Match documented patterns; cite ADR / spec when deviating; no unexplained idiom drift.

## Manual-check stop
- Unrunnable check (on-device install, keystore, mic permission, STT-A, `tcpdump` privacy clip, etc.) → stop on the first one, surface, wait for user outcome. Don't check the box yourself.

## Tests + docs ship together
- Every code change: pos / neg / err / edge across unit / integration / perf / a11y tiers as applicable.
- Same commit updates README / ADRs / stories / architecture-brief / design-guidelines / ux-copy / diagrams the change invalidates.
- Green tests alone ≠ done. Stale docs / diagrams = unfinished.
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

## Build order
- Per `docs/PRD.md` §"Build philosophy: build first, test at failure zones." Risk via STT-A–E in phases 1–3.

## Android stack (default)
- Android-only unless explicitly instructed otherwise.
- Jetpack Compose, Kotlin, Material 3, AndroidX lifecycle / ViewModel, existing Gradle catalog (`libs.versions.toml`).
- Do not introduce KMP / CMP / `commonMain` / `iosMain` / `expect`-`actual` / CMP resources / Nav 3 migration / new architecture frameworks.
- Preserve existing architecture unless explicitly asked to migrate. Don't force MVI / MVVM if conventions are coherent.

## Gradle edits
- Inspect `libs.versions.toml` first. Verify AGP / Kotlin / Compose compiler / dep versions. Reuse aliases. Don't guess Maven coordinates.

## Testing defaults
- JVM / Robolectric Compose test setup. Turbine for Flow / ViewModel tests when available. Semantic UI assertions over screenshot tests.
