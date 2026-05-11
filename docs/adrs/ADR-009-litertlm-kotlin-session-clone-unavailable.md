# ADR-009 — LiteRT-LM Kotlin Session.clone() unavailable in v1 (supersedes ADR-008)

**Status:** Accepted
**Date:** 2026-05-11
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:** `adrs/ADR-008-parallel-lens-execution.md` (entire). The 3-lens parallel-via-Session-cloning design ADR-008 prescribed cannot be built against the Kotlin SDK shipped at submission timebox.
**Restores:** `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Background pass (3 lens calls, sequential)" original sequencing rule.

---

## Context

ADR-008 (2026-05-10) prescribed running the 3-lens background extraction in **parallel** via LiteRT-LM's documented Engine/Session API with Copy-on-Write KV-cache. Stated mechanism: build one base `Session` per entry containing the shared prefix (system + 5 surfaces + entry text + history), clone it three times, append per-lens module suffixes, fire all clones concurrently against the single Engine.

The ADR cited the LiteRT-LM Android getting-started page and the DeepWiki Kotlin API page as evidence the cloning API was reachable from Kotlin. Neither source distinguished between the C++ runtime's session-cloning capability (which exists) and the Kotlin SDK's exposure of that capability (which does not).

Story 2.6.6 (the refactor execution story) opened with the API-feasibility probe as Action Item 6 of ADR-008: *"First failure in Phase 2 implementation that suggests Session cloning isn't working on E4B-audio — STOP and write a superseding ADR rather than silently reverting to sequential."* The probe was run 2026-05-11. This ADR is that supersede.

---

## Evidence

### Pinned dependency (already on latest)

- `com.google.ai.edge.litertlm:litertlm-android:0.11.0` per `gradle/libs.versions.toml`.
- Google's Android Maven `maven-metadata.xml` for the artifact: `<latest>0.11.0</latest>`, `lastUpdated 2026-05-04`. **No newer artifact is published.** Bumping the dep does not unlock cloning.

### Kotlin API surface (from AAR bytecode + upstream `main` source)

`Session.kt` exposes these public methods only:

```
isAlive(): Boolean
close()
runPrefill(List<InputData>)
runDecode(): String
generateContent(List<InputData>): String
generateContentStream(List<InputData>, ResponseCallback)
cancelProcess()
```

`SessionConfig` constructor takes only a `SamplerConfig`. No parent-Session reference. `Engine.createSession(SessionConfig)` builds a fresh Session bound to the Engine; no parent-Session arg.

**No `clone()`. No `fork()`. No `copy()`. No `branch()`. No `duplicate()`.**

The upstream repo's `main` branch (`kotlin/java/com/google/ai/edge/litertlm/Session.kt`) is identical in surface to the 0.11.0 AAR. The capability is not staged in `main` waiting for a release cut.

### JNI bridge confirms it at the native binding

`LiteRtLmJni.kt` native signatures:

```
nativeCreateSession(engineHandle: Long, SamplerConfig): Long
nativeDeleteSession(Long)
nativeRunPrefill(Long, InputData[])
nativeRunDecode(Long): String
nativeGenerateContent(Long, InputData[]): String
nativeGenerateContentStream(Long, InputData[], JniInferenceCallback)
nativeCancelProcess(Long)
```

`nativeCreateSession` takes only `(engineHandle, SamplerConfig)`. No parent-session arg. No `nativeCloneSession` symbol. The native library `libLiteRt.so` may expose cloning at the C++ layer (per `runtime/core/session_advanced.cc`), but the JNI surface in the Android AAR does not bind to it.

### Upstream signal — no Kotlin clone landing in the submission window

- **Issue #1226** ("Session Advanced" Android support) — open since 2026-01-15. Google maintainer (2026-01-20): *"Android will use it when it is ready."* No timeline given. Last activity 2026-02-27.
- **PR #1515** ("kotlin: add engine mode and session clone API surface") — external contributor's 8-PR chain (#1508–#1516) implementing the Kotlin clone surface. **Closed unmerged 2026-03-09** (`merged: false`). No replacement PR has landed; no Google-authored alternative shipped in the two months between then and today.
- **Issue #2160** ("When will Session::Clone / Conversation::Clone be available for iOS?") — open. Confirms cloning exists at the C++ layer and is not yet exposed to platform SDKs.

The clean inference: a Kotlin clone API is on the upstream roadmap but is not landing inside the 13-day submission window remaining (deadline 2026-05-24).

---

## Decision

1. **ADR-008 is fully superseded.** The "parallel via Session cloning" design is not buildable against `litertlm-android:0.11.0` and no later artifact is available. The ADR remains in the tree as a historical record of the design that was attempted; its status header now points to this ADR.

2. **ADR-002's original sequential rule is restored as the v1 contract.** Three lens calls, sequential, against the same Engine. The "30–90s ceiling" timeout guard returns to its original role rather than acting as a parallel-call backstop. Latency budget reverts to the per-lens device-record measurements already captured under Story 2.7 (~5–7 s per lens on E4B GPU after the `libOpenCL.so` manifest fix; per-entry 25–55 s).

3. **Story 2.6.6 is deferred to backlog.** Marked superseded-and-deferred in `docs/stories/phase-2-core-loop.md`. The work itself is not abandoned — it lives in `backlog.md` under `parallel-lens-execution-via-clone` with a precise unblock condition.

4. **`AppContainer` row terminology returns to ModelHandle.** ADR-008 §"AppContainer Update" had renamed `ModelHandle` to "Engine wrapper" semantics in `architecture-brief.md`. The rename was harmless in isolation, but the row's `InferenceCoordinator` description bakes in the "builds a base Session per entry, forks 3 cloned Sessions, fires all three in parallel" prose that no longer reflects shipping code. Both rows revert to their pre-ADR-008 description: one loaded model handle, sequential 3-lens iteration through `Engine.createConversation()` / `createSession()` as already implemented in `BackgroundExtractionWorker`.

5. **No SDK probe is repeated in Phase 4/5.** Re-running the API check costs CPU and decision energy with no decision to make until upstream ships the Kotlin clone surface. The revival trigger is observation of an upstream signal (release notes, merged PR, maintainer message on #1226), not a calendar tick.

---

## Revival triggers (post-v1)

Open this ADR for amendment-by-supersede if **any one** of these lands:

| Trigger | Signal source |
|---|---|
| New `litertlm-android` release (>0.11.0) | Google Android Maven `maven-metadata.xml` lastUpdated changes |
| Upstream `main` branch adds `Session.clone()` / parent-Session `SessionConfig` | `kotlin/java/com/google/ai/edge/litertlm/Session.kt` HEAD includes the method |
| Issue #1226 closes with a "shipped" comment | Maintainer comment confirming Android availability |
| PR #1515 or a Google-authored equivalent merges | `merged: true` on the relevant PR |

When one lands, a new ADR (likely ADR-N where N is the next free integer) supersedes both ADR-008 and this ADR with a fresh decision. Do not edit either historical record.

---

## What does NOT change

- ADR-002's convergence resolver contract (per-field agreement predicates, candidate/ambiguous/canonical verdicts). The resolver is sequential-vs-parallel-agnostic.
- ADR-002 §"Why three calls and not one combined call" — independence-of-rollouts argument is intact; the rollouts simply happen in series rather than concurrently for v1.
- ADR-001 Q3 retry-based recovery contract (`extraction_status` / `attempt_count` / `last_error`).
- ADR-004 conditional foreground service lifecycle. The notification stays visible for the (longer) sequential extraction's duration. The state machine is unaffected.
- ADR-005's STT-B v1 single-turn scope choice. The foreground prompt shape is unrelated to background sequencing.
- AGENTS.md guardrail 13 (one inference runtime in v1: LiteRT-LM). Unchanged.
- The convergence verdicts users see in v1 are byte-identical to what ADR-008 would have produced — only the wall-clock differs.

---

## Consequences

**Easier:**
- Zero refactor risk. `BackgroundExtractionWorker.kt`'s sequential loop ships as-is. The lens-failure isolation, retry policy, parse-fail "no opinion" handling, and per-lens diagnostic logging all stay correct.
- Single KV-cache footprint. RAM headroom is exactly what Story 2.7 measured on the reference device. No new investigation needed for the 8 GB min-spec target.
- Documentation set is internally consistent again. ADR-002's body, `architecture-brief.md`, and shipping code converge on one rule.

**Harder:**
- Per-entry wall-clock stays at the sequential floor (~25–55 s on E4B GPU per Story 2.7's device record). ADR-008's "queue drains in real-time under ADHD-cadence capture" outcome is forfeited for v1.
- Demo storyboard (forthcoming `demo-storyboard.md`, Phase 5) must show the background "Reading the entry" notification beat with the longer duration. The notification UX from ADR-004 still works; it's just on screen longer.
- The "parallel lens execution" technical-walkthrough beat is off the table for the submission video. The agentic-as-product story still lands via convergence + per-field verdicts; the demo just doesn't get the wall-clock dramatic-reduction moment.

**Not actually harder (worth saying out loud):**
- The "queue grows under ADHD cadence" concern in ADR-008 was hypothetical. No measured device data shows captures arriving fast enough to outpace a 25–55 s sequential extraction. Single-turn-per-capture (ADR-005) caps the input rate at one entry per user-initiated record session. If queue depth turns out to bite in actual use, revisit then with measurements.

---

## Action Items

1. [x] ADR-008 status header set to **Superseded by ADR-009**; body untouched per AGENTS.md guardrail 23.
2. [x] ADR-002 §"Background pass" — add a 2026-05-11 stacked supersede note pointing to this ADR, restoring sequential as the v1 rule. Original "sequential" prose remains historically intact below the note.
3. [x] `architecture-brief.md` §"AppContainer Ownership" — `ModelHandle` row description reverts to "loaded LiteRT-LM model handle, single handle across the app" wording; `InferenceCoordinator` row reverts to sequential-extraction prose.
4. [x] `docs/stories/phase-2-core-loop.md` Story 2.6.6 — marked **Superseded by ADR-009 — deferred to backlog**. References-line at top dropped ADR-008 (now historical-only). Phase 2 acceptance-criteria latency row updated. Exit-checklist row records the explicit fallback.
5. [x] `docs/backlog.md` — new row `parallel-lens-execution-via-clone`, tier `v1.5`, area `inference`, with the SDK-gap reasoning and the revival-trigger reference back to this ADR.
6. [x] `docs/README.md` + `docs/PRD.md` §"Authoritative documents" — add ADR-009 entry; ADR-008 entry annotated as superseded.
7. [ ] If a revival trigger fires post-submission, write the supersede ADR (do not edit this one).

---

## Sources

Primary evidence used to write this ADR. All accessed 2026-05-11.

- **AAR bytecode probe** — `com.google.ai.edge.litertlm:litertlm-android:0.11.0`, unpacked from the Gradle cache; `javap` on `Session`, `Engine`, `LiteRtLmJni`, `SessionConfig`. No clone/fork/copy symbol anywhere on the class or JNI surface.
- **Maven metadata** — `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml`. Confirms 0.11.0 is `<latest>` and `<release>`, `lastUpdated 2026-05-04`.
- **Upstream HEAD source** — `kotlin/java/com/google/ai/edge/litertlm/Session.kt` on `google-ai-edge/LiteRT-LM` `main`. No clone method.
- **Upstream Issue #1226** ("Session Advanced" Android support, open). Maintainer @whhone 2026-01-20: *"Android will use it when it is ready."*
- **Upstream PR #1515** ("kotlin: add engine mode and session clone API surface"). Closed unmerged 2026-03-09.
- **Upstream Issue #2160** ("When will Session::Clone / Conversation::Clone be available for iOS?"). Open. Confirms platform-SDK gap is broader than Android.
