# AGENTS.md

Rules for AI agents. Authoritative. Overrides any conflicting local file.

## CONTEXT

- Project: Vestige. On-device voice journaling for ADHD adults. Anti-sycophant tone.
- Platform: Android only. Kotlin 2.3.x, Compose, Gradle KTS, version catalog.
- Inference: Gemma 4 E4B via LiteRT-LM, on-device.
- Persistence: ObjectBox (structured tags), markdown files (entry source-of-truth).
- Deadline: 2026-05-24 23:59 PDT. 17-day build. Goal: win Gemma 4 Challenge "Build with Gemma 4" prize.
- Spec: `PRD.md`. Scope-cut backlog: `v1.5-backlog.md`.

## SCOPE GATE

Before any change request, evaluate:

> Does it visibly improve the 90s pitch or 5-min walkthrough?

- NO → reject. Append to `v1.5-backlog.md`. Reply: `"this doesn't help us win, deferring to v1.5."`
- YES → proceed.

PRD `Non-Goals` and P2 list are blocklists. Check before adding features.

## HARD PROHIBITIONS

| ID | Rule |
|---|---|
| P1 | No outbound network calls except model download. No analytics, telemetry, crash beacons, remote config, CDN fonts. |
| P2 | Audio bytes never persist. Discard PCM after model extraction. Memory-only. |
| P3 | No therapy framing. No "how did that make you feel," no validation default, no warmth performance. |
| P4 | No interpretive overlay on patterns. User's own words only. `"you might be feeling…"` is FORBIDDEN. |
| P5 | No quantified-self UI. No streaks, scores, "good day" grading. |
| P6 | No PII or behavioral data leaves device. Export is user-initiated. No auto-sync. |
| P7 | No backwards-compatibility shims. v1 only. |
| P8 | No comments restating WHAT. Comments only when WHY is non-obvious. |
| P9 | No `.skip` on tests. No lowering coverage thresholds to pass CI. |
| P10 | No `!!`. No `lateinit var` unless framework demands. No `runBlocking` in production. |
| P11 | No `--no-verify`. No skipping pre-commit hooks. |
| P12 | No commits on `main`. Always branch. |
| P13 | No second inference runtime. No cloud LLM fallback. |

## TONE INVARIANTS

- Detective persona is default. Sharp, observant, behavioral follow-ups.
- Hardass + Editor: P1, prompt scaffolds stubbed only.
- Bite must land within 60 seconds of demo video start.

## ARCHITECTURE INVARIANTS

| Layer | Rule |
|---|---|
| Storage | Markdown = source of truth. One file per entry. ObjectBox = structured tags + retrieval indices, NEVER entry content. |
| Inference | LiteRT-LM only. Gemma 4 E4B `.litertlm` from Hugging Face, downloaded once at first launch into app-internal storage. |
| Audio | `AudioRecord` → 30s PCM chunk → Gemma native audio modality → text + tags → discard buffer. |
| Retrieval | Hybrid: keyword + tags + recency over candidate set. Embeddings = Phase-0-gated layer; ship only if comparison demo holds. |
| Patterns | Run at session END, not real-time. Persist as own ObjectBox entity. |
| Network | Zero outbound at runtime. Verify with `adb logcat` + packet sniffer before submission. |

## CODE STYLE (Kotlin)

- `val` over `var`. Immutable collections.
- Coroutines + `Flow` for async.
- Constructor injection. No service locators. No stateful `object` singletons.
- Sealed classes for state machines.
- `?.`, `?:`, `requireNotNull(value) { "why" }` over `!!`.
- Max cognitive complexity: 15. Max line length: 120.
- Compose-only UI. No XML Views unless Compose has no equivalent.

## TESTS

- Unit: `app/src/test/kotlin/` (JVM, fast).
- Instrumented: `app/src/androidTest/kotlin/` (only when Android primitive is unavoidable).
- Coverage floor: 80% lines/branches/functions. NEVER lower.
- Every public function: positive + negative + edge.
- Fake LiteRT-LM with canned bytes. NEVER mock it — mocks lie.
- Remove local-validation test files before commit.

## COMMITS

- Conventional Commits. Lowercase type and subject.
- Each commit independently builds + lints + tests green.
- AI attribution footer required: `Generated-by: Claude <Model> <noreply@anthropic.com>`.
- Atomic. One logical change per commit.
- Never on `main`. Branch types:
  - `feat/*` — touches `app/src/main/`, related tests, feature docs.
  - `fix/*` — bounded to bug + tests proving fix.
  - `chore/*` — tooling, CI, deps. No user-facing changes.
  - `docs/*` — `README.md`, `docs/*` only. No code.
- Pre-commit (lefthook): ktlint format on staged kt files, detekt over `app/src`, gitleaks, actionlint. commit-msg: commitlint (bash regex, no node toolchain). pre-push: build + test. DO NOT bypass with `--no-verify`.
- Push only when ready for review. Not after every commit.

## BRANCH SCOPE GUARD

Scope = branch name + first commit's diff shape.

When user requests work outside active branch's scope:
1. STOP.
2. Propose new branch.
3. Do NOT append.

Triggers for new branch:
- Active is `feat/*`, request is doc-only and not feature-required.
- Active is `docs/*`, request touches code.
- Active branch already shows two themes, third requested.

## GITHUB ACTIONS

- `actions/*`: tagged major (`@v4`).
- Third-party: commit SHA + version comment (`@abc123 # v4.1.0`).
- Treat warnings as errors.
- No LHCI.

## RELEASE PLEASE CONFIG

- `release-type: simple`.
- `initial-version: "1.0.0"` inside package entry.
- NO `bump-minor-pre-major` / `bump-patch-for-minor-pre-major` keys.
- Keys are kebab-case. camelCase silently ignored.
- `.release-please-manifest.json` + `app/build.gradle.kts` `versionName` MUST both be `1.0.0` at init.
- Pre-create both `autorelease: pending` and `autorelease: tagged` labels at repo init.
- Do NOT customize `pull-request-title-pattern`.

## DISTRIBUTION

- License: Polyform Shield 1.0.0 + Supplemental Terms (`LICENSE`).
- Sideload via GitHub Releases. No Play Store in v1.

## AUTHORITATIVE DOCS

| File | Purpose |
|---|---|
| `PRD.md` | Locked spec. |
| `AGENTS.md` | This file. |
| `concept-locked.md` | Full product spec. (TBD) |
| `runtime-research.md` | Android Gemma 4 deployment research. (TBD) |
| `challenge-brief.md` | Challenge rules + judging. (TBD) |
| `architecture-brief.md` | Module breakdown, build plan, contracts. (Phase 0+) |
| `v1.5-backlog.md` | Deferred features. |

## VERSIONS

Source of truth: `gradle/libs.versions.toml`. Sync this section when bumping.

- Kotlin: `2.3.21`
- AGP: `9.0.0`
- Gradle: `9.1.0`
- KSP: `2.3.7`
- Compose BOM: `2026.04.01`
- ObjectBox: `5.4.2`
- LiteRT-LM: `com.google.ai.edge.litertlm:litertlm-android:0.11.0` (bundles `libLiteRt.so` — do NOT also add `:litert` directly)
- minSdk 31 / targetSdk 35 / compileSdk 35. JVM toolchain 25 (latest LTS); Java source/target compat 17 (Android max).
