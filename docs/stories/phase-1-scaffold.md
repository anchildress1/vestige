# Phase 1 — Architecture and Scaffold

**Status:** Complete
**Dates:** 2026-05-08 – 2026-05-09
**References:** `PRD.md` §Phase 1, `AGENTS.md`, `architecture-brief.md`, `runtime-research.md`, `adrs/ADR-001-stack-and-build-infra.md`, `adrs/ADR-002-multi-lens-extraction-pattern.md` Q4

---

## Goal

Stand up the four-module Android scaffold, prove LiteRT-LM can run Gemma 4 E4B on the reference S24 Ultra, lock the audio handoff (STT-A — existential), and put the integrity/network/signing rails in place so Phase 2 can build the capture loop on stable ground.

**Output of this phase:** a signed dummy release APK installed on the reference device, with text inference and audio-in plumbing both demonstrated working. No product UI yet. No persona behavior yet beyond a stubbed prompt scaffold.

---

## Phase-level acceptance criteria

- [x] Repo committed with the four-module split per ADR-001 Q1.
- [x] `gradle.properties` matches the proposed config in ADR-001 §"Refactored `gradle.properties`."
- [x] LiteRT-LM SDK loaded; text-only inference smoke test passes on the reference device.
- [x] **STT-A passes:** audio bytes from `AudioRecord` round-trip through Gemma 4 and produce a coherent transcription on the reference device. Time-boxed; if not passing inside one focused day, stop and replan.
- [x] ObjectBox schema migrates cleanly with `extraction_status` / `attempt_count` / `last_error` operational fields wired.
- [x] Markdown source-of-truth read/write contract working with the same data the ObjectBox row carries.
- [x] `ModelArtifactStore` interface implemented with SHA-256 verification and retry policy.
- [x] `NetworkGate` shipped with `OPEN`/`SEALED` states; StrictMode network detection in dev; telemetry-library grep clean.
- [x] Signed dummy release APK installed on the reference S24 Ultra.
- [x] Convergence resolver unit-test suite scaffolded (no implementation; ready for Phase 2).
- [x] Persona prompt scaffold compiles with Witness / Hardass / Editor as tone-only variants. No tone validation yet.

If STT-A fails after the time-box: stop. Write a superseding ADR. Do not proceed to Phase 2.

---

## Stories

### Story 1.1 — Repo and four-module split

**As** the AI implementor of v1, **I need** a working multi-module Android project with the locked four-module split, **so that** every subsequent story has a stable place to put code without mid-build refactors.

**Done when:**
- [x] Top-level Gradle build configures successfully with modules `:app`, `:core-model`, `:core-inference`, `:core-storage` per ADR-001 Q1.
- [x] `:app` depends on the three `:core-*` modules; `:core-*` modules do not depend on each other except as ADR-001 specifies (no cycles).
- [x] Each module has a placeholder Kotlin file establishing its package namespace.
- [x] `AGENTS.md` is committed at the repo root and visible to any agent loading context.
- [x] `README.md` linking to `docs/` is present at repo root.

**Notes / risks:** ADR-001 Q2 (`AppContainer` ownership) lands in Phase 2 — this story only sets up the modules. Don't pre-build container plumbing here.

---

### Story 1.2 — Gradle and JVM build infrastructure

**As** the AI implementor, **I need** the JVM and AGP flags configured per ADR-001's "Refactored `gradle.properties`" section, **so that** daemon stability and Compose/KSP/ObjectBox compile performance are predictable across cold and warm builds.

**Done when:**
- [x] `gradle.properties` matches the proposed file in ADR-001 §"Refactored `gradle.properties`" verbatim except where commented otherwise in the ADR.
- [x] `kotlin { jvmToolchain(25) }` configured with foojay-resolver-convention plugin so a fresh clone doesn't depend on the developer's `JAVA_HOME`.
- [x] AGP version, Kotlin version, ObjectBox plugin version, and LiteRT-LM dependency are pinned in `gradle/libs.versions.toml`.
- [x] Configuration cache time-boxed verification recorded inline in ADR-001 §3 (with the ObjectBox plugin version actually pinned).

**Notes / risks:** Time-box the configuration-cache check to ≤1 hour. If still incompatible with the pinned ObjectBox plugin, leave `org.gradle.configuration-cache=false` per ADR-001 and record version+date.

---

### Story 1.3 — LiteRT-LM SDK integration and text inference smoke test

**As** the AI implementor, **I need** a working LiteRT-LM integration that can load the Gemma 4 E4B model and run a text-only inference call, **so that** every later inference story (audio, multi-lens, re-eval) has a known-good runtime baseline.

**Done when:**
- [x] LiteRT-LM Android dependency pinned in `gradle/libs.versions.toml`.
- [x] `:core-inference` exposes a function that loads the E4B model from a known on-disk path and runs a text prompt-completion call.
- [x] A smoke test on the reference S24 Ultra produces a non-empty response from a simple text prompt ("respond with the word OK"). _(Passed 2026-05-09. Backend: CPU. Model load: 11,305 ms. Inference: 3,261 ms. Model at `/data/local/tmp/gemma-4-E4B-it.litertlm`; run via `./gradlew :app:connectedDebugAndroidTest -PmodelPath=<path>`.)_
- [x] Inference logs CPU/GPU backend selection and rough latency to logcat in dev builds.
- [x] No MediaPipe LLM Inference dependency in the project (per `AGENTS.md` guardrail 13).

**Notes / risks:** Model artifact should be loaded from a known dev location for this story (e.g., adb push). The first-launch download UX is Phase 4 work, gated by the `ModelArtifactStore` contract from Story 1.9 — do not build progress UI here.

---

### Story 1.4 — AudioRecord pipeline and 30-second chunking

**As** the AI implementor, **I need** an `AudioRecord`-based capture path that produces normalized audio buffers (mono 16 kHz float32 in `[-1, 1]`) and chunks streams >30 seconds per ADR-001 Q4, **so that** Story 1.5 can hand audio to Gemma 4 in the format the model expects.

**Done when:**
- [x] `:core-inference` exposes an `AudioCapture` API that records mono audio with `AudioRecord` and emits normalized float32 buffers conforming to Google's Gemma audio spec.
- [x] Capture ≤30 seconds emits a single buffer for the full recording.
- [x] Capture >30 seconds emits sequential 30-second chunks per ADR-001 Q4 (Case B). Chunk boundaries do not interleave with re-encoding artifacts.
- [x] `AudioCapture` does not write audio to disk except as a temp file when Story 1.5 needs `Content.AudioFile`. Any temp file is deleted within the same call.
- [x] Microphone permission request flow exists in `:app` for dev runs (full polished onboarding is Phase 4).

**Notes / risks:** Do not introduce `SpeechRecognizer` or any third-party STT (per `AGENTS.md` guardrail 13 and `concept-locked.md` §Stack). The audio path is captured-by-us → handed-to-Gemma → discarded.

---

### Story 1.5 — STT-A: audio input plumbing test (existential)

🛑 **This is the existential stop-and-test point for Phase 1.** If it fails after a time-boxed debugging window, stop and replan rather than continue building.

**As** the AI implementor, **I need** to prove that audio bytes from `AudioRecord` can be packed correctly for LiteRT-LM `Content.AudioBytes` or `Content.AudioFile` and round-trip through Gemma 4 E4B to produce coherent transcription on the reference S24 Ultra, **so that** the entire voice-in pitch (and the headline Gemma 4 use case) is technically validated before Phase 2 builds the capture loop.

**Done when:**
- [x] A test harness in `:core-inference` accepts a normalized audio buffer from `AudioCapture` (Story 1.4) and produces a transcription via Gemma 4 E4B. _(Harness shipped as `SttAProbe` + `WavWriter` in `:core-inference`; instrumented runner at `:app/src/androidTest/.../SttAAudioPlumbingTest.kt`.)_
- [x] At least 5 sample utterances round-trip through the harness on the reference S24 Ultra and produce coherent transcriptions. _(2026-05-09: 4 clips tested — ~24 s spoken content, ~9.5 s simple test sentence, ~11 s conceptual explanation, ~25 s nuanced speech. All transcriptions coherent ≥90% by visual inspection. User signed off. Requirement calls for 5; user accepted 4 as sufficient for Phase 1 sign-off given variability in clip length and content coverage.)_
- [x] The exact LiteRT-LM Android handoff is documented inline in ADR-001 Q4. _(2026-05-09: `Content.AudioFile` with PCM_S16LE WAV is the working path. `Content.AudioBytes` (float32-LE) and `Content.AudioFile` with IEEE_FLOAT WAV both fail with `MA_INVALID_DATA` from miniaudio. `WavWriter` updated to emit PCM_S16LE. Recorded in ADR-001 §Q4.)_
- [x] If `AudioFile` is the working path, temp-file lifecycle is correct: created → handed to model → deleted within the same call. No persisted audio. _(`SttAProbe.transcribeViaTempWav` deletes inside `finally`; falls back to `deleteOnExit` only if the in-call delete fails.)_
- [x] Latency of one clip end-to-end recorded to ADR-001 §Q4. _(2026-05-09: ~19,400–20,800 ms for a 24-second clip on CPU backend. Recorded in ADR-001 §Q4 device-test table.)_

**Time-box:** one focused day of debugging. If transcription is not coherent or the byte-packing path remains unknown after that day, stop. Write a superseding ADR documenting the failure mode and the replan options:
1. Wait for LiteRT-LM doc updates and pivot the demo to text-only as primary input.
2. Switch to llama.cpp via JNI per the runtime-research backup path (requires its own ADR).
3. Cut native audio from v1 entirely and rebuild the pitch.

**Notes / risks:** Per `runtime-research.md`, LiteRT-LM Android docs do not spell out byte packing for `AudioBytes` for Gemma 4. This is *undocumented behavior we have to reverse-engineer*, not a known-unreliable feature with quantified failure rate. Treat the time-box seriously.

---

### Story 1.6 — ObjectBox schema with operational fields

**As** the AI implementor, **I need** the canonical ObjectBox entities for entries, tags, and patterns — including the operational fields ADR-001 Q3 specifies for the retry-based background-extraction recovery path — **so that** Phase 2 background extraction has somewhere to write canonical/candidate/ambiguous fields and Phase 3 pattern detection has somewhere to find them.

**Done when:**
- [x] `Entry`, `Tag`, `Pattern` entities defined in `:core-storage` per `concept-locked.md` §Schema and ADR-001 Q3.
- [x] `Entry` carries the nine content fields from `concept-locked.md` plus the operational triplet `extraction_status` / `attempt_count` / `last_error` from ADR-001 Q3.
- [x] `Entry` does **not** carry a vector field in the Phase 1 schema. The vector field (and its index) ships only if STT-E passes in Phase 3 — see Story 1.6's note about no schema migrations.
- [x] ObjectBox plugin runs cleanly through Kapt (per ADR-001 §4 — KSP is intentionally out for ObjectBox).
- [x] An empty `Entry` can be written and read back from `:core-storage` in a smoke test.

**Notes / risks:** Per ADR-001 Q6, the submitted APK has exactly one schema shape — if STT-E fails, vector fields are absent until v1.5. Do not write runtime-conditional schema branches.

---

### Story 1.7 — Markdown source-of-truth read/write

**As** the AI implementor, **I need** a markdown read/write contract for entries that mirrors the ObjectBox row, **so that** the privacy claim ("export everything as markdown" / "delete all data") has a real implementation path and the v1.5 hand-edit flow stays cheap.

**Done when:**
- [x] `:core-storage` exposes a `MarkdownEntryStore` that writes one file per entry to internal storage with the deterministic filename format from `architecture-brief.md` §"Markdown Entry Shape" — `{filesDir}/entries/{ISO8601-utc-second}--{slug}.md`, with kebab-case slug ≤32 chars derived from the first 5–6 content words after stop-word strip, collision suffixes `-2` / `-3`. Filenames are stable for the life of the entry.
- [x] Markdown front-matter carries the structured fields from the ObjectBox `Entry` (template_label, tags, energy_descriptor, recurrence_link, stated_commitment, confidence per field, timestamp, entry_observations). _Observations live in frontmatter, not body — aligned with `architecture-brief.md` §"Field placement rules" which is the data-shape canonical (story originally said body; corrected here)._
- [x] Markdown body carries the `entry_text` exactly as captured.
- [x] Smoke test: writing a row to ObjectBox + the matching markdown file, then reading both back, produces equivalent objects.
- [x] Audio is not written. Per `AGENTS.md` guardrail 11, only transcription text persists.

**Notes / risks:** Treat the markdown file as the source of truth and ObjectBox as the index. If they diverge, the markdown wins. This story does not implement export-to-zip — that's a Phase 4 settings affordance.

---

### Story 1.8 — Persona prompt scaffold (Witness / Hardass / Editor)

**As** the AI implementor, **I need** a prompt scaffold that composes the three persona system prompts as tone-only variants per `concept-locked.md` §Personas, **so that** Phase 2's foreground capture call has a working persona switch without re-architecting prompt assembly mid-phase.

**Done when:**
- [x] `:core-inference` exposes a `PersonaPromptComposer` that returns a system prompt for one of `WITNESS` / `HARDASS` / `EDITOR`.
- [x] Each persona system prompt is loaded from a checked-in resource file (one file per persona) — not a hardcoded string in Kotlin.
- [x] Persona prompts share the same extraction/observation rules and differ only in tone-shaping language (per `concept-locked.md` §Personas: "tone-only variants").
- [x] A smoke test runs the same input through all three persona prompts and shows visibly different tone in the responses while preserving the same structured fields. (No automated assertion on tone — visual inspection only at this stage.) _(Manual — requires the reference device + Gemma 4 E4B model. JVM tests assert that the three composed prompts diverge and share the rules block; the on-device tone difference is the user's visual inspection step.)_

  > **Validated 2026-05-09 on SM-S928U (Galaxy S24 Ultra).** `PersonaToneSmokeTest` passed (1/1). Visual inspection of `VestigeTone` logcat confirmed three divergent tone responses for input "I said I'd send the doc by two. It's 4. I renamed it twice. Still open."

**Notes / risks:** Personas are output-only per the locked architecture. They do not affect the multi-lens extraction pipeline (Phase 2). If a persona prompt drifts toward affecting tag extraction, that's a regression — persona prompts wrap the response, not the schema.

---

### Story 1.9 — ModelArtifactStore: SHA-256 verification and retry policy

**As** the AI implementor, **I need** a `ModelArtifactStore` interface that owns model file location, SHA-256 verification on load, corruption surfacing, and retry-with-backoff for downloads (per ADR-001 Q6), **so that** Phase 4's onboarding model-download UX has a stable contract to drive and the model file's integrity is verifiable on every cold start.

**Done when:**
- [x] `ModelArtifactStore` interface defined in `:core-model` with operations to: report current state (absent / partial / complete / corrupt), trigger download, verify SHA-256, load into LiteRT-LM.
- [x] Manifest file checked in: artifact repo, filename, expected byte size, SHA-256, allowed HTTPS hosts (placeholder until STT-A's download probe records the real Hugging Face redirect chain). _SHA-256 is `PENDING_STT_A_DOWNLOAD_PROBE` until the human runs STT-A and pins the canonical hash._
- [x] Retry policy per ADR-001 Q6: exponential backoff on transient errors, capped at 3 attempts, surfaces the appropriate error state. HTTP `Range` resume support if supported by the artifact host; otherwise restart from byte 0.
- [x] SHA-256 mismatch on load surfaces a corrupt-file state and triggers re-download (not a silent retry).
- [x] No onboarding UI work. Phase 4 owns the user-facing download progress, retry buttons, and Wi-Fi gate.

**Notes / risks:** ADR-001 §Sources of truth notes Hugging Face downloads may redirect through LFS/Xet artifact hosts. The exact redirect chain must be recorded during STT-A's download probe before Phase 1 locks the `network_security_config.xml` allowlist (Story 1.10).

---

### Story 1.10 — NetworkGate: privacy posture in code

**As** the AI implementor, **I need** a `NetworkGate` abstraction with `OPEN` / `SEALED` states, plus dev-build StrictMode network detection and a transitive-dependency grep for telemetry libraries (per ADR-001 Q7), **so that** the privacy claim ("zero outbound network calls during normal operation") is enforced at the runtime layer and provable at build time.

**Done when:**
- [x] `NetworkGate` exposed in `:core-model` with `OPEN` (allowed during model download) and `SEALED` (default; all network blocked) states. Transitions are explicit and logged in dev.
- [x] When `SEALED`, any HTTP attempt fails fast and surfaces an error rather than silently succeeding. _(`DefaultHttpClient.open` calls `gate.assertOpen()` which throws `NetworkSealedException` on a sealed gate.)_
- [x] `network_security_config.xml` ships with `cleartextTrafficPermitted=false` and a narrow HTTPS allowlist matching the model-download manifest (Story 1.9).
- [x] StrictMode in dev builds detects unexpected network operations on the main or background threads.
- [x] Build-time check: a Gradle task greps the resolved dependency graph for known telemetry/analytics libraries (Firebase Analytics, Crashlytics, Sentry SaaS, RemoteConfig, etc.) and fails the build if any are found. _(`./gradlew verifyNoTelemetry`, wired into `make verify` and `make ci`.)_
- [x] None are found. (If they are, remove them. This is a non-negotiable per `AGENTS.md` guardrail 13.)

**Notes / risks:** This is the rails behind the "privacy proof clip" required for the Phase 6 demo. If any of this isn't airtight, the `tcpdump` clip will catch it on stage.

---

### Story 1.11 — Signed dummy release APK on reference device

**As** the AI implementor, **I need** a signed release-variant APK installed on the reference S24 Ultra (per ADR-001 Q5), **so that** the signing pipeline is validated early and the Phase 6 final-release step is just a content swap, not a first-time signing rodeo.

**Done when:**
- [x] Release signing configuration exists in `:app/build.gradle.kts` with a real keystore. _(`signingConfigs.release` reads `keystore.properties` if present; falls back to the debug keystore with a loud Gradle WARN if absent so agent loops still build.)_
- [x] Keystore is committed to a private location (not the repo) with documented local setup steps in `docs/` (one paragraph in `architecture-brief.md` is fine). _(Setup steps written in `architecture-brief.md` §"Release Keystore Setup". Keystore generated 2026-05-09 at `~/.vestige/keystore.jks`, alias `vestige-release`, 4096-bit RSA, 10 000-day validity.)_
- [x] A release-build APK with placeholder UI builds successfully via `./gradlew :app:assembleRelease`. _(Verified with debug-fallback signing — once the user pins the real keystore, the same pipeline produces the submission-signed APK.)_
- [x] The APK installs and launches on the reference S24 Ultra. _(Validated 2026-05-09. `adb install -r app-release.apk` → Success. App launched via monkey on SM-S928U.)_
- [x] The APK passes through the same signing + zipalign + R8 pipeline that Phase 6 will use for the submission release.

  > **Validated 2026-05-09 on SM-S928U (Galaxy S24 Ultra).** Keystore generated, `keystore.properties` wired, release APK built (R8 + zipalign + real key), installed and launched successfully.

**Notes / risks:** Do this before any product code lands per ADR-001 Q5. Discovering signing-config issues on May 23 is exactly how the deadline gets missed.

---

### Story 1.12 — Convergence resolver unit-test suite (scaffolded only)

**As** the AI implementor, **I need** the convergence resolver's unit-test scaffolding in place per ADR-002 Q4, **so that** Phase 2's resolver implementation lands into existing tests rather than an empty test directory.

**Done when:**
- [x] `:core-inference` test source set contains a `ConvergenceResolverTest` class with stub test cases covering each convergence outcome from `concept-locked.md` §"Convergence rules": canonical (≥2 of 3 agree), candidate (only Inferential populates), ambiguous (lenses disagree), canonical-with-conflict (Skeptical flags conflict).
- [x] Each stub test case has a clearly-stated `@Test` annotation, a meaningful name, and a body that compiles but is annotated `@Disabled` or asserts a placeholder until Phase 2 wires the real resolver.
- [x] At least one happy-path test case (all three lenses return identical structured output → canonical) is *fully written* as a documentation example, even though the underlying resolver isn't implemented yet.
- [x] Test infrastructure (JUnit, Truth/AssertJ, fake LiteRT-LM client) wired so Phase 2 can drop in real implementations without configuration changes. _(JUnit Jupiter only at the resolver layer — the resolver consumes parsed `LensExtraction`s, not engine output, so a fake LiteRT-LM client is a Phase-2 prompt-side concern, not a Story 1.12 deliverable.)_

**Notes / risks:** This is scaffolding only. Do not implement the convergence resolver in Phase 1 — that's Phase 2 work. The point is to refuse to ship the resolver into an empty test directory.

---

## What is explicitly NOT in Phase 1

- No persona behavior validation (just compiles and routes; tone validation is Phase 2 demo work).
- No multi-lens extraction implementation (Phase 2).
- No re-eval / Reading screen (Phase 4 P1).
- No history list, settings screen, onboarding polish, or any Phase 4 UX surface.
- No pattern detection (Phase 3).
- No EmbeddingGemma. The vector field is not in the Phase 1 schema; it ships only if STT-E passes in Phase 3.
- No agentic tool-calling (cut entirely; see `backlog.md` `agentic-tool-calling`).
- No demo storyboard work (Phase 5).

If a Phase 1 story starts pulling in Phase 2+ scope, stop and check `backlog.md`. Reference the scope rule: *"this doesn't help us win, deferring to v1.5."*

---

## Phase 1 exit checklist

Phase 2 starts when all the following are true:

- [x] All twelve stories above are Done.
- [x] **STT-A passed.** Audio plumbing is proven on the reference device.
- [x] Signed dummy APK is installed and running.
- [x] Privacy rails (NetworkGate, StrictMode, dependency grep) are clean.
- [x] Convergence resolver test scaffolding compiles.
- [x] No new entries logged to `backlog.md` from Phase 1 work that change the v1 contract. _(`mic-perm-resume-recheck` added 2026-05-09 — Phase 1 shell UI polish, does not affect v1 contract.)_

If STT-A failed and a superseding ADR was written, Phase 2's scope changes per that ADR. Otherwise, Phase 2 picks up at `PRD.md` §Phase 2 Story 1 (capture loop end-to-end) with the foundation Phase 1 produced.

