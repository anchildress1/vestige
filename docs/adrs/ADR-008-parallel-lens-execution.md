# ADR-008 — Parallel 3-Lens Execution via LiteRT-LM Engine/Session Pattern

**Status:** Accepted. Corrected 2026-05-16 — see [§Correction](#correction-2026-05-16--mechanism-and-performance-premise). The decision (concurrent multi-context 3-lens on one Engine) stands; the mechanism is `Engine.createSession()` / `createConversation()`, **not** `Session.clone()` + CoW prefix-fork.
**Date:** 2026-05-10
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Depends on:** `adrs/ADR-002-multi-lens-extraction-pattern.md` (supersedes the "sequential" sequencing rule)
**Supersedes:** ADR-002 §"Background pass (3 lens calls, sequential)" — that rule assumed one model handle forced serialization. LiteRT-LM's Engine → multi-Session API invalidates that assumption.

> **Correction note (2026-05-16).** A prior ADR (ADR-009, since **deleted as a mistake**) claimed `litertlm-android:0.11.0` could not do this because it lacks `Session.clone()`. That probe was mis-scoped: it searched for a method literally named `clone()`. A direct AAR bytecode probe of the pinned `0.11.0` artifact found `Engine.createSession(SessionConfig)` and `Engine.createConversation(ConversationConfig)` — one Engine drives many **independent** contexts. The capability this ADR depends on shipped in 0.11.0 the whole time. The original `Session.clone()` + Copy-on-Write **shared-prefix** mechanism below was never the SDK shape and is corrected in [§Correction](#correction-2026-05-16--mechanism-and-performance-premise); read the body's clone/CoW/wall-clock specifics through that section.

---

## Correction (2026-05-16) — mechanism and performance premise

This ADR's **decision stands**: run the 3 lenses concurrently against one loaded Engine instead of serially. What was wrong is the *how* and the *how-much*, corrected here. The body below is the original 2026-05-10 rationale; where it says "clone", "CoW", "shared prefix computed once", or quotes a ~3× wall-clock, read it through this section.

**Mechanism.** The SDK has no `Session.clone()` and no parent-Session / CoW prefix-fork. The pinned `litertlm-android:0.11.0` AAR exposes `Engine.createSession(SessionConfig)` and `Engine.createConversation(ConversationConfig)`: one Engine → many **independent** contexts. Concurrency is per-context, not per-clone. There is **no shared-prefix-computed-once optimization** — each lens context composes and prefills its own prefix.

**Performance.** Because the prefix is not shared, the "3× via CoW" wall-clock math in §Wall-Clock Math / §RAM Math does **not** hold as written. Worse, a single GPU serializes work at the hardware command queue, so three concurrent contexts do not execute in true parallel on-device. The real, defensible win is **eliminating Kotlin-layer mutex blocking** — a foreground capture can preempt an in-flight background lens instead of waiting on a shared `Mutex`. Net background wall-clock improvement is **unmeasured** and may be modest. Concurrent-context RAM on the reference S24 Ultra (E4B weights shared; per-context KV is not) is also **unmeasured**.

**Adoption gate.** Whether v1 actually runs the background pass concurrently is **not decided here** — it is Story 2.6.6 / Story 2.19's call, made on the RAM + wall-clock measurement above against the 17-day timebox and the demo gate. This ADR establishes only that the SDK does not block it. Until that measurement, the shipped path remains ADR-002 sequential; that is a scope position, not an SDK limitation.

**ADR-009 is deleted.** Its "clone unavailable → impossible → revert to sequential" conclusion was a mis-scoped-probe mistake, not a design change. Per AGENTS.md ADR discipline waiver for mistakes (operator decision 2026-05-16), it was removed outright rather than left as a superseding record.

---

## Context

ADR-002's background extraction runs three lens calls (Literal, Inferential, Skeptical) per entry. The original rule was **sequential**, on the assumption that a single loaded model handle could not serve parallel calls without OOM or runtime-layer serialization.

Empirical reality on E4B + GPU: ~5–7s per call. Sequential 3-lens = ~15–21s per entry. Under ADHD-cadence capture, the extraction queue grows faster than it drains. That kills the demo, the UX, and the "Vestige reads your entry" beat.

LiteRT-LM's documented Android API supports the **Engine/Session pattern** with **Copy-on-Write KV-cache**, which lifts the assumption ADR-002 was built on:

- **Engine** = singleton; owns model weights, multi-modality encoders, all expensive shared resources.
- **Session** = per-task; manages its own state, history, KV-cache.
- **Session cloning** = fork a Session from a shared prompt prefix; the clone references the parent's KV-cache via CoW until divergent generation forces a page copy.
- Multiple Sessions can share one Engine.

This is the exact shape we need: shared prefix (system + persona-free extraction role + 5 surface modules + entry text + retrieved history) computed **once**, then 3 forks for the 3 lens module suffixes running in parallel.

---

## Decision

Run the 3-lens extraction in **parallel** via LiteRT-LM Session cloning. One Engine, one base Session per entry, three cloned Sessions for the three lenses.

The independence-of-lenses argument from ADR-002 §"Why three calls and not one combined call" still holds — each lens runs its own rollout, generating tokens against its own divergent KV-cache state. Session cloning gives us independence AND parallelism.

---

## Execution Contract

```
On extraction trigger for entry E:

1. (Once per process) Engine.load(model_artifact) — startup; owns 3.66 GB weights.

2. (Per entry) Build base Session with the shared prefix:
     [system role — extraction-only, no persona]
     [surface module — behavioral]
     [surface module — state]
     [surface module — vocabulary]
     [surface module — commitment]
     [surface module — recurrence]
     [retrieved history chunks]
     [entry_text]
     [output schema reminder]
   Compute base Session's KV-cache for that prefix. One pass.

3. Clone base Session 3 times. Each clone gets:
     - Reference to base KV-cache pages (CoW; no copy until write)
     - Lens module suffix appended (literal | inferential | skeptical)

4. Fire all 3 clones in parallel. Each generates its lens-specific output.

5. Collect 3 LensResult tuples. Hand to convergence resolver per ADR-002 §"Convergence Resolver Contract."

6. (When extraction completes for entry E) Discard the 3 cloned Sessions + the base Session. Engine stays loaded.
```

---

## RAM Math

| Component | Cost |
|---|---|
| Engine (model weights) | 3.66 GB — loaded once at process start |
| Base Session KV-cache (shared prefix) | one allocation; bounded by the 5 surface modules + entry text + history |
| Per-lens cloned Session | CoW reference + lens-specific suffix + output tokens. Bounded by output budget. |
| **Estimated peak per-entry runtime overhead** | ~500 MB–1 GB total across the 3 clones, on top of the Engine |

Fits comfortably on the reference S24 Ultra (12 GB). Fits on the 8 GB minimum spec with OS headroom. No need for 3 separate model loads (would have been ~11 GB and dead on min spec).

---

## Wall-Clock Math

| | Sequential (old) | Parallel via CoW (new) |
|---|---|---|
| Per-lens call latency on E4B GPU | ~5–7s | ~5–7s |
| 3-lens total | ~15–21s | ~5–7s + parallelization overhead |
| Queue under ADHD cadence | grows | drains in real-time |

Per-entry extraction drops from ~15–21s to ~7–9s. Queue concern dissolves. The "Reading the entry" notification from ADR-004 stays visible for one parallel call's duration instead of three sequential calls.

---

## What This Supersedes in ADR-002

| ADR-002 location | Old rule | New rule |
|---|---|---|
| §"Background pass (3 lens calls, sequential)" | "lens calls run sequentially, not in parallel. E4B holds one model handle on-device — parallel calls would either OOM or serialize at the runtime layer anyway, with worse error surfaces." | Lens calls run in parallel via Session cloning per this ADR. Engine is the single shared model handle. Sessions are per-lens. |
| §"Why three calls and not one combined call" | Statistical independence requires three rollouts | Still true. Cloned Sessions are three independent rollouts on top of a shared prefix — the divergence happens at the lens-module token; pre-divergence prefix KV is shared, post-divergence KV diverges per-lens. Independence preserved. |
| §"Token budget" | "Phase 0 must measure actual token counts of `(lens + 5 surfaces + output reminder)`" | Same constraint applies, but the shared-prefix design means the surface modules' KV is computed once per entry, not three times. Smaller wall-clock cost for the shared portion. |
| §"Edge case — lens errors mid-call" | Surviving 2 lenses, convergence threshold becomes "both must agree" | Unchanged. Per-clone failure handled the same way — if a cloned Session errors, the surviving Session results feed the resolver. |
| §"Retry-based recovery (per ADR-001 Q3)" | Cold-start sweep re-runs the 3-lens pass | Unchanged. On retry, build a fresh base Session, fork 3 times, fire parallel. Same outcome contract. |

---

## What This Does NOT Change

- ADR-002's convergence resolver contract (still deterministic Kotlin code, not a model call).
- ADR-002's per-field agreement predicates (`template_label`, `tags`, `energy_descriptor`, `recurrence_link`, `stated_commitment`, `entry_observations`, `confidence`).
- ADR-002's foreground prompt contract (single-turn-per-capture per the STT-B fallback; persona module; structured `{transcription, follow_up}` output).
- ADR-002's per-entry observation generation prompt (one short model call after resolver; runs on the Engine like the lens calls, but as its own Session — not cloned from the lens base).
- ADR-001 Q3's retry-based recovery contract (`extraction_status` / `attempt_count` / `last_error`).
- ADR-004's conditional foreground service lifecycle (still promotes on `RUNNING`, demotes after keep-alive). The notification stays visible for shorter wall-clock now, which is fine.
- AGENTS.md guardrail 13 (single inference runtime). Engine/Session is one runtime; the Sessions are not separate runtimes.

---

## AppContainer Update

`architecture-brief.md` §"AppContainer Ownership" gets one rename and one clarification:

| Singleton | Was | Now |
|---|---|---|
| `ModelHandle` | "Loaded LiteRT-LM model + KV cache. One-and-only-one across the app." | Becomes the **Engine wrapper**. Owns the loaded LiteRT-LM Engine + multi-modality encoders. One per process. Sessions are constructed against this Engine on demand by `InferenceCoordinator`. |
| `InferenceCoordinator` | "Composes prompts, dispatches lens calls sequentially, runs convergence resolver." | "Composes prompts, builds the base Session for each entry, forks 3 cloned Sessions for the lenses, fires all three in parallel, collects results, runs convergence resolver." |

---

## Consequences

**Easier:**
- Per-entry extraction wall-clock drops ~3× without dropping the convergence architecture.
- Demo queue drains naturally; no "Reading 3 entries queued" backlog at ADHD cadence.
- ADR-004's transient notification stays visible for one parallel call's worth of time, not three sequential — feels less heavy.
- Shared-prefix KV computation is faster than 3× redundant prefix passes.

**Harder:**
- `InferenceCoordinator` needs Session lifecycle management (create base, clone, fire-parallel, collect, discard). More code than a sequential loop.
- Failure modes per Session need handling (any one clone could error; resolver handles 2-of-3 fallback per ADR-002).
- LiteRT-LM Session cloning is documented but Vestige hasn't run it on E4B-it-litert-lm specifically. Implementation must surface any "this artifact doesn't support cloning" error fast and loudly; do not silently fall back to sequential.

**Revisit when:**
- LiteRT-LM Session cloning fails on the E4B audio artifact during Phase 2 — fall back to ADR-002's sequential rule with a written supersede note.
- A future model artifact requires more than one Engine (e.g., EmbeddingGemma loaded alongside Gemma 4 if STT-E passes) — that's a separate AppContainer addition, not a change here.

---

## Addendum (2026-05-17) — Operator adoption; implemented mechanism

The §Correction (2026-05-16) framed v1 adoption of concurrent multi-context as a measured scope call owned by Story 2.6.6 / 2.19. **On 2026-05-17 the operator made that call by directive ("we do not want to ship sequential"; streaming required).** Concurrency is now implemented; this addendum records the realized mechanism additively (the prior decision/correction text is unchanged).

**Realized mechanism.** `LiteRtLmEngine`'s exclusive per-call `Mutex` is replaced with a readers/writer guard (`withEngine`): inference calls are concurrent "readers", each opening its own **independent** SDK conversation off the single Engine (`createConversation`; no `Session.clone()`, no CoW shared prefix — consistent with §Correction); `close()` is the exclusive "writer" that stops admitting calls and drains in-flight ones before freeing the native handle. `BackgroundExtractionWorker` fans the three lenses out (`coroutineScope { … async … }.awaitAll()`); `runLens` is pure (no shared state, no per-lens listener) so concurrent lenses cannot race; `modelCallCount` / `lastError` are derived post-fan-out; a `CopyOnWriteArrayList` accumulator preserves partial-results-on-timeout under structured-concurrency cancellation; `RUNNING` is emitted once at fan-out. The same engine change makes the foreground path non-blocking on background (Story 2.19 — there is no longer a shared exclusive call mutex).

**Performance premise (unchanged from §Correction).** A single GPU still serializes at its hardware command queue, so this is **not** a literal 3× wall-clock speedup — the win is non-blocking preemption / structure. Net concurrent-context RAM on the reference S24 Ultra and realized background wall-clock remain **unmeasured**; that figure is a manual-check stop tracked on Story 2.6.6 / 2.19 and is not self-reported. JVM tier is fully covered (incl. a max-in-flight probe proving genuine concurrency); on-device RAM/wall-clock is the remaining open measurement.

---

## Action Items

1. [ ] Phase 2 Story 2.6 — rewrite to build base Session + 3 cloned Sessions + fire parallel. Drop the "sequential, not parallel" line. Reference this ADR.
2. [ ] `architecture-brief.md` §"AppContainer Ownership" — rename `ModelHandle` row to **Engine** semantics; expand `InferenceCoordinator` row per this ADR.
3. [ ] ADR-002 §"Background pass" — add a header note "Superseded by ADR-008 for sequencing. Independence rationale below is preserved; sequencing is now parallel via Session cloning."
4. [ ] ADR-002 §"Why three calls and not one combined call" — add a footer note that Session cloning preserves the independence argument while enabling parallelism.
5. [ ] README + PRD §"Authoritative documents" — add ADR-008 entry.
6. [ ] First failure in Phase 2 implementation that suggests Session cloning isn't working on E4B-audio — STOP and write a superseding ADR rather than silently reverting to sequential.

---

## Sources

- [LiteRT-LM Overview — Google AI for Developers](https://ai.google.dev/edge/litert-lm/overview)
- [Get Started with LiteRT-LM on Android](https://ai.google.dev/edge/litert-lm/android)
- [LiteRT-LM Kotlin API getting-started (GitHub)](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)
- [LiteRT-LM Kotlin and Android API — DeepWiki](https://deepwiki.com/google-ai-edge/LiteRT-LM/4.6-kotlin-and-android-api)
- [LiteRT-LM repo](https://github.com/google-ai-edge/LiteRT-LM)
