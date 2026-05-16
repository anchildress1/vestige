# ADR-012 — GPU inference performance gaps: sampler library + engine pre-warm

**Status:** Accepted  
**Date:** 2026-05-15  
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.

---

## Context

After switching `LiteRtLmEngine` to `BackendChoice.Gpu` (2026-05-15), logcat revealed two separate performance gaps:

**Gap 1 — GPU sampler not used.**  
Engine init logs show:
```
OpenCL sampler not available, falling back to statically linked C API
Could not load shared library libLiteRtTopKOpenClSampler.so: dlopen failed: library not found
GPU sampler unavailable. Falling back to CPU sampling.
```
`libLiteRtTopKOpenClSampler.so` ships in the LiteRT-LM SDK's `prebuilt/` directory but is not packaged in the APK. With `topK=1, temperature=0` (greedy decode) the impact is minimal — CPU argmax is trivially fast — but the library should be present so the sampler path is correct when sampling parameters change.

**Gap 2 — 15.2-second cold engine init blocks first call.**  
```
D VestigeLiteRtLm: Engine initialized in 15229ms
```
`ensureBackgroundEngineInitialized()` is lazy — it fires on the first foreground or background inference call, making the user wait 15+ seconds on first tap. Subsequent calls in the same session are 7–11 seconds (GPU inference, expected for E4B at this token volume). The 15s stall is avoidable.

---

## Decisions

### 1 — Bundle `libLiteRtTopKOpenClSampler.so`

Extract `libLiteRtTopKOpenClSampler.so` from the LiteRT-LM SDK `prebuilt/arm64-v8a/` directory and add it to `app/src/main/jniLibs/arm64-v8a/`. No code changes required — the native loader picks it up automatically via `LD_LIBRARY_PATH`.

If `libLiteRtTopKWebGpuSampler.so` is also present in prebuilt, bundle it too. The engine prefers OpenCL; WebGPU is the fallback chain.

### 2 — Pre-warm engine on model-ready transition

Add a background pre-warm launch to `AppContainer.refreshModelReadiness()`. When the model transitions to `Ready`, kick off `ensureBackgroundEngineInitialized()` immediately. The existing double-check mutex makes repeat calls free — no guard needed at call sites.

```kotlin
if (current is ModelReadiness.Ready) {
    scope.launch { ensureBackgroundEngineInitialized() }
}
```

This fires on `ON_RESUME` when the model is present, giving the engine the time between app open and first user tap to complete initialization in the background.

**Both fixes share the same root cause:** the GPU path was added (2026-05-15) without the packaging and lifecycle wiring it needs. They ship together.

---

## Deferred

| item | tier | reason |
|---|---|---|
| Persistent foreground service to keep engine alive across process death | v2 | Battery cost, `startForegroundService` restriction complexity, and lmkd interaction require a dedicated ADR. Pre-warm covers the demo window. |

---

### Addendum (2026-05-15) — Decision 1 blocked: sampler absent from SDK v0.11.0

`libLiteRtTopKOpenClSampler.so` and `libLiteRtTopKWebGpuSampler.so` are **not present** in the `litertlm-android-0.11.0` AAR. The Gradle cache at `~/.gradle/caches/9.5.0/transforms/*/transformed/litertlm-android-0.11.0/jni/arm64-v8a/` contains only:

- `libLiteRt.so`
- `libLiteRtClGlAccelerator.so`
- `liblitertlm_jni.so`

The `prebuilt/` directory referenced in the original decision does not exist in this release. Decision 1 is deferred until the SDK ships the sampler library or an explicit download path is available. No jniLibs addition lands with this ADR.

---

### Addendum (2026-05-16) — GPU sampler platform scope + MTP available + CPU fallback is a bug

**GPU sampler export bug is macOS/Windows only (GitHub Issue #2073).** Android ships all 7 required C ABI exports from `libLiteRtTopKOpenClSampler.so`. The Android GPU sampler fallback ("GPU sampler unavailable. Falling back to CPU sampling.") is caused exclusively by the `.so` being absent from the AAR — documented in the 2026-05-15 addendum above — not by the export-count defect. If a future SDK release restores the `.so` to the AAR, Android GPU sampling works correctly without any code change.

**MTP Single Position is available in the pinned SDK.** `litertlm-android:0.11.0` ships Single Position MTP, documented at >2x decode speedup. The `mtp-speculative-decoding` backlog entry listed "ExperimentalFlags not yet stable" as its unblock condition — that condition no longer applies at this version. Story 2.15 carries enablement.

**CPU fallback is a regression, not a documented limitation.** The 24–33s foreground latency figures recorded in Phase 2 were measured before `BackendChoice.GPU` was wired. With GPU active and the Decision 2 pre-warm in place, all inference paths run on GPU. Any logcat line indicating CPU fallback is a bug to fix at root per AGENTS.md §"Atomic correctness" — do not document it as a known constraint and move on.

---

## Consequences

- Engine init stall moves to app-open background (hidden from user on any session where model was already present at launch).
- GPU sampler path deferred — `libLiteRtTopKOpenClSampler.so` absent from SDK v0.11.0 (see Addendum).
- 7–11s per-call inference latency is GPU-on-E4B baseline — not a gap, not addressed here.
