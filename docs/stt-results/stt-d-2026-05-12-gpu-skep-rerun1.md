# STT-D run — 2026-05-12 (GPU, Skeptical-only revision, rerun #1)

First of two back-to-back runs validating Factor 4 (run-to-run consistency) of the multi-factor
rubric introduced in ADR-002 §"Addendum (2026-05-12, sixth)". Configuration is byte-identical
to the shipped gpu-skep run (`docs/stt-results/stt-d-2026-05-12-gpu-skep.md`).

## Setup

| Field | Value |
|---|---|
| Date | 2026-05-12 |
| Device | Galaxy S24 Ultra (SM-S928U, Android 16) |
| Model | `gemma-4-E4B-it.litertlm` |
| Backend | GPU (OpenCL delegate) |
| Manifest | `docs/stt-d-manifest.example.txt` (15 A–D entries, md5 `2380b8c4091eac2c73281de3261e14c9`) |
| SamplerConfig | `topK=1, topP=1.0, temperature=0.0, seed=42` (greedy) |
| maxNumTokens | 4096 |
| `maxAttemptsPerLens` | 2 |
| Lens prompts | LITERAL + INFERENTIAL baselines; SKEPTICAL revised (1398 chars, under 1441 budget) |
| Harness commit | `b621a8d` |
| Wall clock | 8m 40s |

## Verdict

**Pass.** 11/15 (73%) meaningful divergence, 4 Skeptical flags fired with quoted evidence,
A4 + B2 reachable `canonical_with_conflict`, 14/15 full 3-of-3 parse (B3 partial), 0 timeouts.
Output verdict identical to the prior gpu-skep archive entry-by-entry.

## Per-entry summary

| ID | Lenses | Latency (s) | Meaningful | Divergence axes |
|---|---|---|---|---|
| A1 | 3/3 | 30.6 | ✅ | `tags` + `state-behavior-mismatch` flag |
| A2 | 3/3 | 24.7 | ❌ | — |
| A3 | 3/3 | 31.6 | ✅ | `tags` + `energy_descriptor` + INF-only `energy_descriptor` |
| **A4** | 3/3 | 32.1 | ✅ | **`vocabulary-contradiction` flag only — `canonical_with_conflict` eligible** |
| A5 | 3/3 | 30.7 | ❌ | — |
| A6 | 3/3 | 26.0 | ✅ | `tags` + `state_shift` |
| B1 | 3/3 | 29.7 | ✅ | `tags` |
| **B2** | 3/3 | 39.7 | ✅ | **`commitment-without-anchor` flag only — `canonical_with_conflict` eligible** |
| B3 | 2/3 | 35.5 | ❌ | partial parse |
| C1 | 3/3 | 35.6 | ✅ | `tags` + `unsupported-recurrence` flag |
| C2 | 3/3 | 33.1 | ✅ | `tags` |
| C3 | 3/3 | 29.3 | ❌ | — |
| D1 | 3/3 | 44.0 | ✅ | `tags` |
| D2 | 3/3 | 36.3 | ✅ | `tags` |
| D3 | 3/3 | 33.2 | ✅ | `tags` |

Mean latency: 32.8 s. Min 24.7 s, max 44.0 s.

## Skeptical flags (quoted user evidence)

| Entry | Flag kind | Quote |
|---|---|---|
| A1 | `state-behavior-mismatch` | *"I was fine before it, then completely flattened by 11"* |
| A4 | `vocabulary-contradiction` | *"Not tired exactly. More like the battery got yanked after the sync."* |
| B2 | `commitment-without-anchor` | *"Said I would send the invoice today. Instead I reorganized the desktop."* |
| C1 | `unsupported-recurrence` | *"Same three criteria"* |

## Rubric factors (this run alone)

| Factor | Cut | Result |
|---|---|---|
| 1. Meaningful divergence ≥ 50% | 50% | 73% ✅ |
| 2. `canonical_with_conflict` reachable ≥ 2 | 2 | A4 + B2 = 2 ✅ |
| 3. Parse stability ≥ 90% lens-calls, 0 timeouts | 90% / 0 | 97.8% (44/45), 0 ✅ |
| 4. Run-to-run consistency | see rerun2 | rerun2 below ✅ |

## Pipeline

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
  -PinferenceBackend=gpu \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
adb logcat -d -s VestigeSttD > docs/stt-results/stt-d-2026-05-12-gpu-skep-rerun1.raw.log
```
