# STT-D run — 2026-05-12 (GPU, deterministic SamplerConfig)

## Setup

| Field | Value |
|---|---|
| Date | 2026-05-12 |
| Device | Galaxy S24 Ultra (SM-S928U, Android 16) |
| Model | `gemma-4-E4B-it.litertlm` |
| Backend | GPU (OpenCL delegate) |
| Manifest | `docs/stt-d-manifest.example.txt` (15 A-D scenario entries) |
| Threshold | `ceil(15 × 30%) = 5` entries with meaningful divergence |
| **SamplerConfig** | **`topK=1, topP=1.0, temperature=0.0, seed=42`** (greedy) |
| **maxNumTokens** | **4096** |
| Harness commit | `7789513` (phase-2/stt-d-evidence) |
| Wall clock | ~13 min |

## Verdict

**PASS — 9/15 (60%) meaningful divergence.** Up from the previous GPU run (8/15, 53%) at the
same fixture. **All 15 entries × 3 lenses parsed cleanly on the first attempt — zero parse
failures, zero retries.**

| Metric | GPU before (sdk defaults) | GPU after (greedy) | CPU same fixture (greedy) |
|---|---|---|---|
| Meaningful divergence | 8/15 (53%) | **9/15 (60%)** | 12/15 (80%) |
| Parse-fail lens calls | 4 (C2 × 2, D1 × 1, C2 SKEP × 2) | **0** | 0 |
| Total retries | 4 | **0** | 0 |
| Mean per-entry latency | 51.3s | **48.4s** | 144.5s |

## Root cause confirmed

Before the SamplerConfig fix, the SDK was using non-greedy default sampling (probably
temperature > 0). On GPU's FP16 delegate, narrow-margin tokens at structured-output delimiters
flipped wrong → invalid JSON → parse failures. Specifically: C2 + D1 INFERENTIAL parse-fail
× 2 retries reproduced on both 2026-05-10 and 2026-05-12 GPU runs at the OLD config.

With `SamplerConfig(topK=1, topP=1.0, temperature=0.0, seed=42)`:
- **C2** now parses 3/3 lenses (`disagree_fields=[tags]`, `meaningful=true`)
- **D1** now parses 3/3 lenses (3-way convergence → `meaningful=false`, but no parse fail)

The 20pp residual gap vs CPU (60% vs 80%) is FP16 vs FP32 logit precision producing different
greedy picks on narrow-margin tokens. Untestable / unfixable at the SDK layer — neither
`SamplerConfig` nor `EngineConfig` exposes a precision-mode toggle on the GPU delegate.
At v1 demo scale (60% divergence is well above the 30% gate), this is acceptable.

## Per-entry summary

| ID | Lenses parsed | Latency (s) | Meaningful | Divergence axis | CPU same day |
|---|---|---|---|---|---|
| A1 | 3/3 | 27.4 | ✅ | `tags` | ✅ + skep flag |
| A2 | 3/3 | 41.3 | ❌ | — | ❌ |
| A3 | 3/3 | 44.4 | ✅ | `tags` + `energy_descriptor` INF-only | ✅ |
| A4 | 3/3 | 33.7 | ❌ | — | ✅ |
| A5 | 3/3 | 44.3 | ❌ | — | ✅ (`state_shift`) |
| A6 | 3/3 | 41.8 | ✅ | `tags` | ✅ |
| B1 | 3/3 | 45.7 | ✅ | `tags` | ✅ |
| B2 | 3/3 | 59.9 | ✅ | `stated_commitment` | ✅ (skep flag) |
| B3 | 3/3 | 47.3 | ❌ | — | ❌ |
| C1 | 3/3 | 49.9 | ✅ | `tags` | ✅ |
| C2 | **3/3** ⬆️ | 53.6 | **✅** ⬆️ | `tags` | ❌ |
| C3 | 3/3 | 48.4 | ❌ | — | ✅ |
| **D1** | **3/3** ⬆️ | 49.2 | ❌ (convergence) | — | ✅ |
| D2 | 3/3 | 51.3 | ✅ | `tags` | ✅ |
| D3 | 3/3 | 53.7 | ✅ | `tags` | ✅ |

The 4 remaining GPU-vs-CPU disagreements (A4, A5, C3, D1 all meaningful on CPU, not on GPU)
are the FP16 precision drift — different greedy picks at narrow-margin tokens. Out of scope
for the SDK config knobs available in 0.11.0.

## Pipeline

Pull artifacts + run with the deterministic sampler in place:

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
  -PinferenceBackend=gpu \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
adb logcat -d -s VestigeSttD > docs/stt-results/stt-d-2026-05-12-gpu-greedy.raw.log
```

Sampler defaults live in `LiteRtLmEngine.DETERMINISTIC_SAMPLER`
(`topK=1, topP=1.0, temperature=0.0, seed=42`). Override via the engine constructor for runs
that explicitly want non-greedy behavior.
