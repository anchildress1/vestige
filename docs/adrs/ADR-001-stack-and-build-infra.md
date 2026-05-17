# ADR-001 — v1 Stack & Build Infrastructure

**Status:** Accepted
**Date:** 2026-05-08
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes / depends on:** `concept-locked.md`, `PRD.md`, `runtime-research.md`

---

## Context

Vestige ships in 17 days. Most product-shape decisions are already locked in `concept-locked.md` and `PRD.md`. What is **not** recorded anywhere is the build-infrastructure spine the CLI is about to start hammering on, and a handful of plumbing decisions Phase 1 will collide with the moment a module boundary needs to be drawn.

This ADR exists to (1) freeze the locked stack into one signable document the AI handoff can point at, (2) call out the existing `gradle.properties` choices that will bite us before they're someone else's problem, and (3) close seven unresolved architecture questions that block Phase 1.

Constraints driving the answer:

- **17-day deadline.** No refactor budget. Decisions made now ride to submission.
- **Single platform — Android 14+, reference S24 Ultra.** No KMP. No iOS. No web.
- **Zero outbound network at runtime** is a P0 marketing claim. Anything pulling from cloud (analytics, crash reporting SaaS, RemoteConfig) is disqualified.
- **Native code in the path** — LiteRT-LM ships JNI + `.so` files. ABI splits, R8/ProGuard, and APK size aren't optional concerns.
- **JDK 25 toolchain → bytecode 17 target.** Android still caps at 17.

---

## Decision (summary)

Adopt the locked stack from `concept-locked.md` and `runtime-research.md` verbatim. Tighten the build infra per "Build Infrastructure" below. Resolve seven Phase-1-blocking questions per "Open Architecture Questions" below.

---

## Locked Stack (recorded for the handoff)

| Layer | Choice | Source of truth |
|---|---|---|
| Language | Kotlin 2.x | `concept-locked.md` |
| UI | Jetpack Compose + Material 3 (expressive features off) | `concept-locked.md` |
| LLM runtime | LiteRT-LM SDK (`litertlm-android`) — single runtime per `AGENTS.md` guardrail 13 | `runtime-research.md` |
| Model artifact | `litert-community/gemma-4-E4B-it-litert-lm` | `runtime-research.md` |
| Embeddings | EmbeddingGemma 300M via LiteRT — **STT-E passed 2026-05-12, ships in v1.** See addendum below. | `concept-locked.md`, `PRD.md` §P0 |
| Storage | ObjectBox (structured) + markdown source-of-truth + HNSW vector index on `EntryEntity.vector`. | `concept-locked.md` |
| Audio | `AudioRecord` → Gemma 4 native audio modality (no third-party STT) | `concept-locked.md` |
| Distribution | APK via GitHub Releases. No Play Store for v1. | `PRD.md` §Submission |

These are out of scope for this ADR. A future ADR may revisit any of them as a supersedes-001 record.

---

## Build Infrastructure — issues with the current `gradle.properties`

The current file does its job. It also makes several choices that range from "legacy reflex" to "actively going to bite us mid-Phase-2." Listed by severity, not by line order:

### 1. `-XX:+UseParallelGC` — wrong GC for this workload

ParallelGC optimizes throughput for batch jobs at the cost of pause times. The Gradle daemon is a long-lived JVM under interactive load (KSP, Compose compiler, LiteRT artifact packaging), which is exactly the workload G1 was designed for. JDK 25 ships G1 as default and ZGC (generational) as a viable alternative for daemons with >4 GB heap.

**Action:** drop the flag. Let the JVM pick G1. If we want to be intentional, set `-XX:+UseG1GC` and revisit ZGC later.

### 2. `-Xmx4g` with no `-Xms`, no metaspace cap

Compose compiler + KSP2 + ObjectBox processor + AGP all chew metaspace. 4 GB max with no floor means the daemon resizes constantly during cold builds and OOMs late in long sessions.

**Action:** `-Xms2g -Xmx6g -XX:MaxMetaspaceSize=1g`. If the dev machine is RAM-starved, dial down `-Xmx`, but never leave `-Xms` unset on a daemon.

### 3. `org.gradle.configuration-cache=false` — verify against pinned ObjectBox

The comment blames ObjectBox's `PrepareTask`. That was unambiguously true on ObjectBox 4.0–4.3 and incrementally fixed across the 4.4 line. The pinned version is now **5.4.2** (per `AGENTS.md` §VERSIONS), past the window where the original bug was filed — but ObjectBox's plugin has had config-cache regressions in minor releases before, so "almost certainly safe" is not "verified." **Verify against the pinned 5.4.2 plugin in a Phase-1 spike before Phase 2 starts.** If clean, flip the cache on and record the verifying version inline; we will feel the difference on every iteration during Phase 2 onward.

If the bug recurs, leave the flag off but add `org.gradle.configuration-cache.problems=warn` in dev so we can see what other tasks are waiting for it.

**Action:** time-boxed verification task in Phase 1. Outcome recorded inline in this file.

**Verification result (2026-05-09, ObjectBox plugin 5.4.2, Gradle 9.1.0):** the bug recurs. `:core-storage:objectboxPrepareBuild` (type `io.objectbox.gradle.PrepareTask`) still serializes a `Project` instance, which the configuration cache rejects. Build completes only because Gradle stores the entry "with problems" rather than failing — turning it on as-is would mask real cache misses behind those two known violations. Flag stays `org.gradle.configuration-cache=false` with `org.gradle.configuration-cache.problems=warn` in `gradle.properties` so other regressions remain visible. Re-evaluate on the next ObjectBox plugin bump.

### 4. ObjectBox annotation processing — Kapt, not KSP

Earlier drafts considered a Phase-1 KSP2 smoke test against the ObjectBox processor. Do not spend the build window there. ObjectBox runs on Kapt for v1.

`ksp.useKSP2=true` may stay in `gradle.properties` for any *other* KSP-using processor in the build. ObjectBox itself bypasses KSP entirely.

**Action:** none — recorded for the handoff so a future agent doesn't reopen the question. Revisit only when ObjectBox ships first-class KSP2 support and we want the Kapt removal.

### 5. Missing modern AGP perf flags

The file is missing several near-free wins:

- `android.nonFinalResIds=true` — speeds up incremental R8 / app-link processing on AGP 8+.
- `android.experimental.enableNewResourceShrinker.preciseShrinking=true` — bigger size savings on release APK, which matters because the first-launch model download is already huge.
- `kotlin.parallel.tasks.in.project=true` — multi-module Compose builds benefit measurably.
- `org.gradle.workers.max=<CPU/2>` — explicit beats default. Default is full CPU which thrashes on shared dev machines.

**Action:** add. None of these change behavior risk meaningfully.

### 6. Missing — daemon health and reproducibility

- No `org.gradle.daemon=true` (default true, but explicit is the convention).
- No `org.gradle.vfs.watch=true` (default true on macOS/Linux; explicit on Windows matters).
- No `kotlin.daemon.jvmargs` — the Kotlin daemon inherits Gradle's, which means our metaspace fix above only works if Kotlin's daemon respects it. Mirror the args explicitly to be safe.

**Action:** add for explicitness.

### 7. Java 25 toolchain → target 17 — fine, but pin it

JDK 25 toolchain compiling to bytecode 17 is correct for AGP. The risk is an environment where `JAVA_HOME` is something else and the toolchain isn't actually pinned in `build.gradle.kts`. Use Gradle's foojay-resolver-convention plugin and `kotlin { jvmToolchain(25) }` so a fresh clone does not depend on whatever JDK happens to be on the developer's machine.

**Action:** confirmed in build files, not just `gradle.properties`.

### Refactored `gradle.properties` (proposed)

```properties
# JVM — daemon-friendly heap and GC for KSP/Compose/ObjectBox
org.gradle.jvmargs=-Xms2g -Xmx6g -XX:MaxMetaspaceSize=1g -XX:+UseG1GC -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xms1g -Xmx4g -XX:MaxMetaspaceSize=512m -XX:+UseG1GC

# Build perf
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.vfs.watch=true
org.gradle.workers.max=6

# Configuration cache — verify ObjectBox plugin version before flipping on.
# Pinned-off at v1 cut. Re-evaluate when bumping ObjectBox plugin.
org.gradle.configuration-cache=false
org.gradle.configuration-cache.problems=warn

# AndroidX + AGP modern defaults
android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=true
android.experimental.enableNewResourceShrinker.preciseShrinking=true

# Kotlin
kotlin.code.style=official
kotlin.incremental=true
kotlin.parallel.tasks.in.project=true

# KSP — KSP2 stays on for any future KSP-using processor.
# ObjectBox itself uses Kapt, so this flag does not affect entity processing.
ksp.useKSP2=true
```

---

## Open Architecture Questions (must close before Phase 1 ends)

These are not in the locked spec. Phase 1 will pick a default whether we record one or not — better to pick deliberately.

### Q1. Module layout

**Proposal:** four-module split.

| Module | Owns |
|---|---|
| `:app` | Compose UI, navigation, DI graph composition root |
| `:core-model` | Domain types (Entry, Tag, Pattern, ConvergenceResult), no Android deps |
| `:core-inference` | LiteRT-LM wrapper, audio pipeline, prompt composition (lens × surface), 3-lens convergence resolver |
| `:core-storage` | ObjectBox entities, markdown read/write, hybrid retrieval |

**Why not single-module:** the inference layer needs to be testable on JVM without an emulator. Module split is the only honest way to enforce that boundary.
**Why not more than four:** any additional split is invisible-in-the-demo polish.

### Q2. DI framework — Hilt vs. manual

**Proposal:** **manual constructor injection with a single `AppContainer`.** Hilt buys nothing for a four-module app with one Activity, and adds KSP processing time we don't have. If Phase 4 grows scope, we revisit. Until then, no annotations.

**`AppContainer` ownership (so Phase 1 doesn't re-derive this):**

| Singleton | Lifecycle | Owns |
|---|---|---|
| `ModelArtifactStore` | Process-scoped | Model file path, download state, SHA-256 verification, re-download/delete (Q6) |
| `NetworkGate` | Process-scoped | Sole HTTP/download path. `OPEN` only during model download, `SEALED` otherwise (Q7) |
| `ModelHandle` | Process-scoped, lazy-init after artifact verified | Loaded LiteRT-LM model + KV cache. One-and-only-one across the app. |
| `SessionState` | Active capture session (foreground only) | Multi-turn history, persona override, current chunk counter |
| `EntryStore` | Process-scoped | ObjectBox box for `Entry` + markdown writer. Single point of writes. |
| `PatternStore` | Process-scoped | ObjectBox box for `Pattern`, dismissal/snooze/mark-resolved state (P0 per `PRD.md` §Patterns) |
| `RetrievalRepo` | Process-scoped | Hybrid retrieval (keyword + tags + recency, vector if STT-E passes) |
| `InferenceCoordinator` | Process-scoped | Composes prompts, dispatches lens calls sequentially, runs convergence resolver. The orchestrator from ADR-002. |

`AppContainer` is constructed once in `Application.onCreate`. UI components receive it through composition locals or constructor params from the Activity. **No service locator pattern** — explicit dependency wiring or it doesn't compile.

### Q3. Background extraction — retry-based recovery, not in-flight survival

The 3-lens extraction runs after the foreground response is acknowledged. The earlier framing ("must survive process death") oversold what's possible: a hot model handle plus mid-rollout state cannot literally resume across an OS kill. The honest framing is **retry-based recovery** — the entry is durably saved before the background pass starts, and the background pass is restartable from scratch if it didn't finish.

**Proposal:** `kotlinx.coroutines` with an application-scoped `SupervisorJob` for the session's background passes. **No `WorkManager`** — WorkManager is for cross-process scheduled work; our extraction is in-process and tied to the hot model handle.

**Persisted on every `Entry` row:**

| Field | Type | Purpose |
|---|---|---|
| `extraction_status` | enum: `PENDING` / `RUNNING` / `COMPLETED` / `TIMED_OUT` / `FAILED` | Drives the cold-start recovery sweep and UI surfacing |
| `attempt_count` | int | Retry budget. Cap at 3; after that, leave as `FAILED` and suggest manual re-eval |
| `last_error` | nullable string | Compact reason (timeout / parse-fail / OOM / lens-error). Surfaced on entry detail's debug section |

**Lifecycle:**
- Foreground call commits the entry row with `extraction_status=PENDING` before the user gets the follow-up.
- Background pass flips to `RUNNING` at start, `COMPLETED`/`TIMED_OUT`/`FAILED` at end.
- On cold start, the inference module sweeps for `PENDING` or `RUNNING` rows (the latter implies a kill mid-flight) and re-runs the 3-lens pass before accepting new user input. UI shows: `Resuming reading on {N} entries.`
- `attempt_count` increments on every retry. `FAILED` rows past the cap stay `FAILED` until the user taps re-eval.

This is **not** the same as the user-tappable Re-eval (which compares new convergence to a prior canonical extraction). Recovery just finishes work that was interrupted before any canonical record existed.

### Q4. Audio chunking across 30s boundaries

STT-A says this must work. The 30s constraint is a runtime cap — `AudioRecord` segments get fed to Gemma 4 in 30-second windows. The model has no memory of audio across calls, only of text.

**Audio normalization before inference:** Google's Gemma audio guide specifies mono-channel audio, 16 kHz sample rate, 32-bit float samples normalized to `[-1, 1]`, with 30 seconds max per clip. Capture from `AudioRecord`, then downmix/resample/normalize inside `:core-inference` before handing audio to LiteRT-LM.

**Android API packing is the STT-A validation:** LiteRT-LM's Android docs expose `Content.AudioBytes(audioBytes)` and `Content.AudioFile(path)`, but the guide does not specify whether `AudioBytes` should be encoded audio bytes, raw float waveform bytes, or another packed representation for Gemma 4. STT-A must test and record the exact working path. If `AudioFile` is the reliable path, write a temp WAV from normalized samples, pass the file path, then delete it immediately after inference. Do not persist audio as product data.

**Contract (reconciles `PRD.md` §"Voice capture" acceptance criterion, two cases):**

**Case A — capture ≤30s (the common path):**
- Single audio call. Gemma 4 returns transcription + follow-up in one structured response.
- This is the literal reading of the PRD acceptance criterion.

**Case B — capture >30s (the long-dump path):**
- Audio is split into N sequential 30s chunks (the last chunk shorter).
- Chunks 1 through N−1 are **transcription-only calls.** No follow-up is generated; the model is asked for transcription only.
- The final chunk's call receives the concatenated transcript-so-far as context and produces transcription + follow-up in one structured response, exactly as Case A does.
- Net effect: the user sees one coherent follow-up, generated against the full entry, regardless of length.

**"Final chunk" detection:** user taps Stop, or silence-detection at the end of a chunk fires (silence detection deferred to v2; v1 = explicit Stop only).

**Validation:** STT-A measures whether mid-sentence chunk boundaries lose meaning. If they do, the fallback is overlapping windows (last 1.5s of chunk N replayed at start of chunk N+1) with deduplication on the transcript.

**Story 1.3 text-only smoke test result (2026-05-09, S24 Ultra):**

| Field | Value |
|---|---|
| Backend | CPU |
| Model load | 11,305 ms |
| Inference (32-char prompt → 2-char reply) | 3,261 ms |
| SHA-256 pinned | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |

**STT-A device-test record (Phase 1, on-device, human-run):** the harness in `:core-inference` (`SttAProbe`) and the instrumented test at `:app/src/androidTest/.../SttAAudioPlumbingTest.kt` exercise three handoffs against Gemma 4 E4B on the S24 Ultra: `Content.AudioBytes(float32-LE bytes)`, `Content.AudioFile(<existing WAV>)`, and `transcribeViaTempWav` (PCM_FLOAT WAV written + handed off + deleted in-call). After running, fill in below. If neither byte-pack works and the file path is the only viable handoff, the temp-WAV lifecycle is the canonical path for v1.

| Field | Value |
|---|---|
| Device | S24 Ultra (12 GB / SD8 Gen 3), SM-S928U, Android 16 |
| LiteRT-LM version | 0.11.0 |
| Date run | 2026-05-09 |
| AudioBytes float32-LE result | **FAILED** — `MA_INVALID_DATA (-10)` from miniaudio; raw float32-LE bytes are not a supported audio container |
| AudioFile WAV (IEEE_FLOAT) result | **FAILED** — same `MA_INVALID_DATA`; LiteRT-LM 0.11.0 miniaudio rejects `WAVE_FORMAT_IEEE_FLOAT` (format code 3) |
| AudioFile WAV (PCM_S16LE) result | **COHERENT** — produced a coherent transcription visually matching the spoken content |
| Working path chosen | `Content.AudioFile(path)` with a PCM_S16LE (format code 1) WAV at 16 kHz mono |
| End-to-end latency, 24 s clip (sendMessageContents) | ~19,400–20,800 ms on CPU backend |
| Backend selected | CPU |

**Consequence:** `WavWriter` updated to emit PCM_S16LE (format 1, 16-bit int, 16 kHz) instead of IEEE_FLOAT. Float32 samples from `AudioCapture` are scaled to `[-32767, 32767]` on write. `Content.AudioBytes` is dead for this SDK version — the only live path is `Content.AudioFile` via a temp PCM_S16LE WAV.

### Addendum (2026-05-10) — GPU backend works on the same artifact

The `litert-community/gemma-4-E4B-it-litert-lm` artifact is GPU-compatible. Original "CPU" was a measurement choice, not an artifact constraint. Unblock: AndroidManifest `<uses-native-library>` for `libOpenCL.so` + `libvndksupport.so` (Android 12+ vendor-namespace isolation; per `https://ai.google.dev/edge/litert-lm/android`).

| Field | GPU (S24 Ultra, 2026-05-10) | CPU baseline |
|---|---|---|
| Engine init (cold) | ~19,700 ms | ~11,500 ms |
| Per-lens call (clean) | ~8–13 s | ~35–55 s |
| Per-entry (3 lenses, clean) | ~25–37 s | ~127–161 s |
| STT-D verdict | 4/6 meaningful | 5/6 meaningful |
| Parse failures | C2 + D1 non-Literal × 2 retries | 0 |

**Consequence:** GPU is the working backend on OpenCL-vendor-namespace devices. CPU stays as fallback via `-PinferenceBackend=cpu`. ADR-002 §"Latency budget" 1–5 s target still unmet — NPU and prompt-shrinkage are the remaining levers, unchanged on Phase 4/5.

### Q5. Distribution & signing

GitHub Releases sideload means a single signing key, stored somewhere. **Not** in the repo. **Not** in plain text on the dev machine.

**Proposal:** `keystore.properties` ignored by git, keystore file in `~/.vestige/keystore.jks`, password from macOS Keychain via a Gradle init script. CI is not signing v1 because there is no CI yet — local signing only. Document the recovery procedure (lost keystore = user has to uninstall to install the next version) in the README.

**Phase 1 validation gate:** build a **signed dummy release APK** in Phase 1 — empty Activity, signed with the real key, installed on the reference S24 Ultra. This proves the signing pipeline works while there is still time to fix Keychain integration, ProGuard surprises, or `applicationId` collisions. Discovering signing is broken in Phase 6 is a category of failure we can prevent for an hour of work now.

### Q6. Model artifact storage, integrity, retry, APK size

The 3.66 GB E4B model is the largest single asset in the build. How it ships affects APK size, first-launch UX, and the privacy claim's edge cases.

**Decisions:**

- **APK does not bundle the model.** A multi-GB APK is hostile sideload UX and brittle release plumbing. The model downloads on first launch over Wi-Fi, per `concept-locked.md` §Onboarding.
- **Storage location:** internal app storage (`Context.filesDir/models/gemma-4-e4b-it.litertlm`). Not external storage — the model is bound to the app's private sandbox; uninstall removes it.
- **Integrity check on every load:** SHA-256 of the file is computed once after download, persisted in `SharedPreferences`, and verified on every cold start. Mismatch → mark `corrupt`, surface "Model file unreadable. Re-download from settings." per `ux-copy.md` §Error States.
- **Download source:** direct from Hugging Face (`litert-community/gemma-4-E4B-it-litert-lm`) over HTTPS. No proxy controlled by us, no analytics layer in between.
- **Retry policy:** exponential backoff on transient errors (network, partial read), capped at 3 attempts before surfacing the appropriate error state. Resume support via HTTP `Range` header if the artifact host supports it; otherwise restart from byte 0. Validate which during the Phase-1 download spike.
- **Retry-state to UX-copy mapping (per `ux-copy.md` §Error States):**
    - Transient network error / partial read failures → `Network choked.`
    - Stalled transfer (no bytes for >30s) → `Download stalled. Retry.`
    - Checksum mismatch on a completed download → `Model file unreadable. Re-download from settings.`
    The error catalog stays simple in copy; the retry state machine lives in `ModelArtifactStore`.
- **Embedding model (STT-E contingent):** if EmbeddingGemma ships, it follows the same storage and integrity contract — separate file, separate checksum, separate retry budget. The submitted APK has exactly one schema shape: if STT-E passes before implementation, vector fields/entities are included; if STT-E fails, they are absent until v1.5. No runtime-conditional ObjectBox schema games. We are building an app, not a migration haunted house.

### Addendum (2026-05-12) — STT-E passed, EmbeddingGemma ships

`SttEEmbeddingComparisonTest` ran four cohort queries (aftermath / invoice / decision-spiral / late-night) against the 18-entry corpus on the reference S24 Ultra. Hybrid (tag + keyword + recency + EmbeddingGemma cosine) beat the tag-only baseline on 3 of 4 queries; tied on 1; lost on 0. The 50% pass-rate threshold is cleared.

| Query | Baseline relevant in top-5 | Hybrid relevant in top-5 |
|---|---|---|
| Q_aftermath | 2/6 | 3/6 |
| Q_invoice | 3/3 | 3/3 |
| Q_decision | 1/3 | 2/3 |
| Q_lateNight | 1/3 | 2/3 |

Storage row in §"Locked Stack" now records a ships-in-v1 schema with the HNSW vector index on `EntryEntity.vector` (one vector per entry, embedded from distilled fields — see `architecture-brief.md` §"Embedding Strategy"). The minimum-free-storage requirement bumps from 6 GB to 7 GB (model 3.66 GB + EmbeddingGemma artifact ~180 MB + tokenizer ~5 MB + headroom). `backlog.md` `embeddings-fallback` closes out — there is no v1.5 fallback to keep open.

**Note (2026-05-16):** STT-E was run with the embedding source bug present (`entry.entryText` verbatim, not distilled fields). The pass verdict still justifies shipping EmbeddingGemma in v1, but the benchmark numbers above reflect noisy embeddings. Re-run completed after Story 3.11 — see Addendum (2026-05-16) below.

### Addendum (2026-05-16) — STT-E re-run on corrected embedding source

`SttEEmbeddingComparisonTest` re-ran the same four cohort queries against the same 18-entry corpus on the reference S24 Ultra after Story 3.11 corrected the embedding source from `entry.entryText` verbatim to the distilled triple (`summary + themes + rawHighlights`). Result: hybrid won 3/4 queries; tied 1; lost 0. Verdict unchanged.

| Query | Baseline relevant in top-5 | Hybrid relevant in top-5 | Novel relevant (hybrid-only) | Outcome |
|---|---|---|---|---|
| Q_aftermath | 2/6 | 3/6 | A2, A1 | Win |
| Q_invoice | 3/3 | 3/3 | — | Tie |
| Q_decision | 1/3 | 2/3 | C2 | Win |
| Q_lateNight | 1/3 | 2/3 | D2 | Win |

The 50% gate is cleared on corrected vectors. Phase 4 is unblocked. The tie on Q_invoice is structurally expected — tag recall is already perfect at 3/3 for that cohort; cosine adds nothing on exact-match queries.

### Q7. Privacy / network enforcement (the P0 marketing claim has to be code, not vibes)

`PRD.md` §P0 promises zero outbound network calls during normal operation. The model download is the sole network event. Enforcement mechanism beyond a code review:

- **`network_security_config.xml`** with `cleartextTrafficPermitted=false` and a narrow download allowlist. Hugging Face model downloads may redirect through LFS/Xet artifact hosts, so the Phase-1 download spike must record the exact redirect chain for the pinned artifact before the network allowlist is locked. **Probe result (2026-05-09):** `huggingface.co` resolver → `cas-bridge.xethub.hf.co`, `Content-Length=3659530240`, SHA-256 `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`. Do not use a single `huggingface.co` allowlist and then discover the real download host in front of a judge. Very cinematic, wrong genre.
- **`NetworkGate` abstraction** — a single small interface in `:core-inference` (or its own `:core-net` module if Phase 1 wants the boundary) that owns the OkHttp `Dispatcher` and any other outbound primitives. Two states: `OPEN` (only valid during the model-download flow) and `SEALED` (default, asserts on any outbound call). `SEALED` is set the moment the download completes; the rest of the process runs against the sealed gate. All HTTP clients in the app obtain their executor from `NetworkGate`.

  **v1 minimum acceptance** (do not let this become a side quest):
    - One download client path inside `NetworkGate`. No other outbound primitives ship in v1.
    - A checked-in model manifest records artifact repo, filename, expected byte size, SHA-256, and the allowed HTTPS hosts observed during the Phase-1 download probe.
    - No direct `OkHttpClient` / `URL.openConnection` / `HttpURLConnection` construction outside `NetworkGate`. Enforce with a CI grep step over the source tree — a custom Detekt rule is post-v1 unless plain grep is demonstrably insufficient.
    - StrictMode `detectNetwork()` enabled in dev builds outside the explicit download path.
    - Phase-1 dependency grep for `firebase`, `crashlytics`, `analytics`, `segment`, `mixpanel` against the resolved manifest.
    - Phase-6 `tcpdump` proof clip on the reference device, included in the demo video chapter on privacy.

  Anything beyond this is v1.5.
- **No analytics, no crash reporting SaaS, no RemoteConfig.** Crash reports go to a local log file the user can export from settings. ProGuard rules verified to not pull in surprise telemetry from transitive deps — Phase 1 task to grep dependency manifests for `firebase`, `crashlytics`, `analytics`, `segment`, `mixpanel`.
- **StrictMode in dev builds** with `detectNetwork()` enabled outside the explicit download path; a violation throws and fails the build's instrumented tests.
- **Verification artifact for the demo:** a chunked screen recording of `tcpdump` on the device showing zero outbound traffic during a normal capture session. This is the literal verification step a judge would run; we run it ourselves first.

These are not optional. The privacy claim *is* the differentiator — failing it on a single Firebase analytics dep would invalidate the entire pitch.

### Q8. Recording cancellation contract

**Context.** The POC design shows a `DISCARD · NO SAVE` secondary affordance on the active-recording screen, sitting under the primary `STOP · FILE IT` action. The capture spec had no formal contract for what tapping it does. Closing this gap now, before Phase 2 wires the capture loop, so the implementation does not invent behavior.

**Decision.** Tapping the discard affordance during an active recording:

1. Stops `AudioRecord` immediately.
2. Destroys the in-flight audio buffer in the same call stack as the discard action — no deferred cleanup, no staging-buffer escape.
3. Returns the user to the idle Capture screen.
4. Persists nothing — no `Entry` row, no markdown file, no transient cache, no retry queue.
5. Invokes no LiteRT-LM call. The Gemma 4 runtime never sees the bytes.
6. Provides no undo, no recently-discarded list, no recovery path.

The cost of accidental discard is at most 30 seconds of re-recording. The cost of leaking unwanted audio into the model or persistence layer is the privacy claim. The tradeoff is the right way around.

**UI affordance.** A secondary text link styled per `design-guidelines.md` §"Capture Screen / Discard" appears at the bottom of the recording screen, visually subordinate to the primary `STOP · FILE IT` action:

```
              [ STOP · FILE IT ]            ← primary, full-width pill

               DISCARD · NO SAVE             ← secondary, muted text link
```

Single-tap behavior. No confirmation dialog, no long-press, no two-tap arming. Rationale: the affordance is already small, subordinate, and below the primary action. Adding a confirmation flow imposes friction on every cancel for the rare-event case of accidental tap. The buffer is gone the instant the tap registers.

Visual register: muted/mist-gray text (`#7B8497`), no destructive accent color. Discard is calm, not alarming — it's the escape hatch, not the nuclear option. The destructive purple accent stays reserved for ~~`Delete entry` and~~ `Wipe everything`.

**State machine.** `CaptureSession` from Phase 2 Story 2.1 gains a `DISCARDED` terminal state.

```
RECORDING → DISCARDED → IDLE
```

`DISCARDED` is terminal for this session — no rehydration path. The only exit is `IDLE`, which clears the session entirely.

**Audio byte lifecycle.** The buffer is destroyed synchronously with the discard tap. The `AudioRecord` instance is stopped, the underlying byte buffer is overwritten or freed, and the JNI hold is released. If JNI release does not complete within 100ms, log the failure and force-release the handle. Audio bytes are gone from process memory before the screen returns to idle.

**No silent saves.** Unlike the foreground-call audio (which lives briefly in `AudioCapture` until the model call returns), discarded audio never reaches the staging buffer used for multi-chunk concatenation (per Q4 Case B). The discard action takes precedence over all in-flight audio handling. A user 25s into a 30s chunk who taps discard does not leak the 25s into a "partial chunk" staging area.

**No toast, no confirmation.** Per `ux-copy.md` §"Capture Screen — Discard": no snackbar after discard, no "Discarded." confirmation, no "Undo" affordance. The screen simply returns to idle and the patterns peek / status row resume their normal state. Silent dismissal is consistent with the non-recoverable contract.

**Availability window.** Discard is only available during the foreground recording state (`CaptureSession.state == RECORDING`). Once the user taps `STOP · FILE IT`, the foreground inference call is in flight and the screen no longer shows the discard affordance. Cancelling the foreground call after it has been issued is out of scope.

**Multi-chunk sessions (>30s).** For a session that has already produced one or more prior chunks (per Q4 Case B), tapping discard on the current chunk discards: the in-flight current chunk's audio buffer, the accumulated transcription text from prior chunks in the same session, and the session's `CaptureSession` object. The session is discarded *as a whole*, not per-chunk.

**Out of scope for this Q.**
- Cancelling a foreground model call after `STOP · FILE IT` has been tapped (audio already submitted).
- Cancelling background extraction after the capture loop has saved the entry.
- Cancelling typed entries — different affordance, separate spec.

**Revisit when:** post-submission, if user research surfaces accidental-discard at meaningful rate → consider a 3-second snackbar with `Undo`. Note: this would change the privacy contract and requires a superseding ADR.

### Addendum (2026-05-16) — Official Android SDK API reference: key findings

**Source:** `https://ai.google.dev/edge/litert-lm/android` (last updated 2026-03-30 UTC). This is the authoritative Kotlin API surface for `litertlm-android`. Implementors should consult this page for API shapes before coding against the SDK.

**Findings that affect v1 implementation:**

**Audio modality requires explicit `audioBackend` in `EngineConfig`.** The official pattern:
```kotlin
EngineConfig(
    modelPath = "...",
    backend = Backend.GPU(),
    audioBackend = Backend.CPU(), // without this, audio bytes silently fail
)
```
`AppContainer.backgroundEngineFactory` already sets `audioBackend = BackendChoice.Cpu` with a comment referencing LiteRT-LM issue #2056 (GPU audio path SIGSEGVs in `mel_filterbank.cc`). Correctly implemented — this entry is for reference.

**GPU backend requires native library declarations in `AndroidManifest.xml`.** Official requirement per the Android docs (also in the 2026-05-10 addendum above):
```xml
<application>
    <uses-native-library android:name="libvndksupport.so" android:required="false"/>
    <uses-native-library android:name="libOpenCL.so" android:required="false"/>
</application>
```
Without these, Android 12+'s vendor namespace isolation blocks GPU access and the engine silently falls back to CPU. Already present in the manifest; recorded here with the official source for future reference.

**Streaming API is `sendMessageAsync(contents): Flow<Message>`.** Official recommended path for coroutine users. Already wrapped in `LiteRtLmEngine.streamText(prompt: String): Flow<String>` for text. **Multimodal streaming already exists on the pinned 0.11.0** — a 2026-05-16 AAR bytecode probe confirms `Conversation.sendMessageAsync(Contents, Map): Flow<Message>`. (An earlier draft of this addendum claimed it "does not yet exist"; that was wrong — corrected in place per the 2026-05-16 ADR-discipline waiver for mistakes.) Story 2.16 is a thin wrapper: add `streamMessageContents(parts): Flow<String>` calling `conversation.sendMessageAsync(Contents.of(*parts))`, mirroring `streamText` — no new SDK API.

**`ConversationConfig.systemInstruction` is the official system-prompt surface.** Current implementation passes `Contents.of("")` (empty system instruction) and stacks the full system prompt into the message body. This works but bypasses the SDK's intended instruction/message separation. Post-v1 consideration only — changing it during submission window is unnecessary risk.

**`latest.release` is an available Maven coordinate.** The doc shows `implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")`. Vestige pins `0.11.0` explicitly in `gradle/libs.versions.toml`, which is correct for a submission-deadline build. Story 2.14 carries the SDK upgrade probe. The 2026-05-16 bytecode probe of the pinned `0.11.0` confirmed `Engine.createSession()` / `createConversation()` (concurrent independent contexts) are already present — there is no `Session.clone()` gate and no newer artifact is needed; see ADR-008 §Correction (2026-05-16).

**Tool use API exists (`@Tool`, `@ToolParam`, `OpenApiTool`).** Out of scope per `concept-locked.md` §"Out of scope — multi-step agentic tool chains". Documented here so a future ADR doesn't re-research from scratch.

---

## Trade-off Analysis

The dominant trade-off is **deadline vs. correctness**. Every flag and every module boundary above was picked to reduce the chance of a Phase-2 reroll, not to optimize a long-lived codebase. Specifically:

- Manual DI over Hilt costs us cleanliness and saves us a Kapt/KSP processor and 30+ seconds per cold build.
- ObjectBox on Kapt is the boring choice over KSP2 — silent codegen drift is a real risk on KSP2 + ObjectBox; Kapt is slower per-build but predictable.
- Configuration cache stays off until proven safe — the cost of a wrong "on" is a confusing Phase-2 build failure that masquerades as a code bug.
- Four modules over one — the only one of these decisions that buys us long-term flexibility, and it's free because we'll need the testability boundary anyway.
- Model artifact integrity, signing, and network enforcement (Q5–Q7) are deliberately front-loaded into Phase 1. Each one is the kind of thing that fails late and costs a day to fix; Phase-1 verification costs an hour and a half.

---

## Consequences

**Easier:**
- AI handoff has a single doc to point at for build/infra questions.
- Phase 1 can start without bikeshedding module layout or DI choice.
- The gradle config is no longer a museum of legacy reflexes.

**Harder:**
- Configuration-cache verification, signed dummy APK, and network-enforcement plumbing all add Phase-1 setup tasks that weren't in the schedule. Budget one day.
- Manual DI means we wire `AppContainer` by hand. Tedious. Worth it for the build-time win.
- Keystore management requires Ashley to actually do the Keychain step instead of a plaintext password. Annoying but non-negotiable.
- `extraction_status` / `attempt_count` / `last_error` on every `Entry` row means the schema versioning matters from day one. ObjectBox handles this, but migrations have to be tested.

**Revisit when:**
- ObjectBox plugin ships a config-cache-safe release → flip the cache on.
- Phase 4 adds enough surface that manual DI starts hurting → introduce Hilt then, not preemptively.
- Post-submission, if v1.5 adds CI → keystore strategy becomes a real ADR of its own.

---

## Action Items

**Ordering note.** PRD §"Build philosophy: build first, test at failure zones" replaces the prior Phase-0 validation phase. Build directly. Risk is mitigated through the five stop-and-test points (STT-A–E) embedded in phases 1–3. STT-A (audio plumbing, Phase 1) is the existential one — time-box hard. The Phase 1 items below run as Phase 1, not as a separate validation gate.

1. [ ] Replace `gradle.properties` with the proposed file above.
2. [ ] Phase-1 smoke test: configuration cache against the pinned ObjectBox plugin version. If clean, flip the flag and record version here.
3. [x] Create `architecture-brief.md` with the four-module layout and `AppContainer` ownership from Q1/Q2, the audio-chunking contract from Q4, and the background-extraction recovery contract from Q3.
4. [ ] **Phase 1:** build a signed dummy release APK and install it on the reference S24 Ultra (per Q5). Do this before any product code lands.
5. [ ] **Phase 1:** add `network_security_config.xml`, StrictMode network detection in dev builds, and grep transitive deps for telemetry libraries (per Q7).
6. [ ] **Phase 1 (storage + load contract only):** implement the `ModelArtifactStore` interface — file-on-disk location, SHA-256 verification on load, corruption surfacing, retry-with-backoff for downloads. Onboarding UX (Wi-Fi gate, progress UI, copy strings) is Phase 4 work and stays out of Phase 1's scope. Embedding artifact wiring stays gated on STT-E.
7. [ ] Add `extraction_status`, `attempt_count`, `last_error` to the ObjectBox `Entry` entity in Phase 1 (per Q3). Cold-start sweep ships in the same phase.
8. [x] Update root README to point to this ADR for stack/infra and remove stale missing-doc references.
