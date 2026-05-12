# STT-D run — 2026-05-12 (GPU, Skeptical-only revision, rerun #2)

Second of two back-to-back runs validating Factor 4 (run-to-run consistency) of the multi-factor
rubric introduced in ADR-002 §"Addendum (2026-05-12, sixth)". Identical configuration and
identical device session to rerun #1; this run was started immediately after rerun #1 finished
(adb logcat cleared, no process restart in between).

## Setup

| Field | Value |
|---|---|
| Date | 2026-05-12 |
| Device | Galaxy S24 Ultra (SM-S928U, Android 16) |
| Model | `gemma-4-E4B-it.litertlm` |
| Backend | GPU (OpenCL delegate) |
| Manifest | `docs/stt-d-manifest.example.txt` (15 A–D entries) |
| SamplerConfig | `topK=1, topP=1.0, temperature=0.0, seed=42` (greedy) |
| maxNumTokens | 4096 |
| `maxAttemptsPerLens` | 2 |
| Harness commit | `b621a8d` |
| Wall clock | 9m 42s |

## Verdict

**Pass.** Output verdict byte-identical to rerun #1 at the per-entry level: same 11 meaningful
entries, same 4 Skeptical flags with the same quoted evidence and same `note` text, same A4 +
B2 `canonical_with_conflict` reachability, same B3 partial-parse miss. Greedy decoding with
`seed=42` is genuinely deterministic on this engine + this corpus.

## Per-entry summary

| ID | Lenses | Latency (s) | Meaningful | Divergence axes |
|---|---|---|---|---|
| A1 | 3/3 | 32.5 | ✅ | `tags` + `state-behavior-mismatch` flag |
| A2 | 3/3 | 31.7 | ❌ | — |
| A3 | 3/3 | 38.2 | ✅ | `tags` + `energy_descriptor` + INF-only `energy_descriptor` |
| **A4** | 3/3 | 41.0 | ✅ | **`vocabulary-contradiction` flag only — `canonical_with_conflict` eligible** |
| A5 | 3/3 | 33.2 | ❌ | — |
| A6 | 3/3 | 33.9 | ✅ | `tags` + `state_shift` |
| B1 | 3/3 | 35.6 | ✅ | `tags` |
| **B2** | 3/3 | 46.1 | ✅ | **`commitment-without-anchor` flag only — `canonical_with_conflict` eligible** |
| B3 | 2/3 | 43.6 | ❌ | partial parse |
| C1 | 3/3 | 40.0 | ✅ | `tags` + `unsupported-recurrence` flag |
| C2 | 3/3 | 36.8 | ✅ | `tags` |
| C3 | 3/3 | 35.9 | ❌ | — |
| D1 | 3/3 | 34.8 | ✅ | `tags` |
| D2 | 3/3 | 36.9 | ✅ | `tags` |
| D3 | 3/3 | 36.9 | ✅ | `tags` |

Mean latency: 37.1 s. Min 31.7 s, max 46.1 s. ~13% slower than rerun #1 — wall-clock noise
(JIT / GPU thermal / OS scheduling), not output divergence.

## Factor 4 — run-to-run consistency (rerun #1 vs rerun #2)

| Quantity | Rerun #1 | Rerun #2 | Δ |
|---|---|---|---|
| Meaningful set | {A1, A3, A4, A6, B1, B2, C1, C2, D1, D2, D3} | identical | 0 entries differ |
| Meaningful-set Jaccard | — | — | **1.0** (cut ≥ 0.75) ✅ |
| Skeptical flag count | 4 | 4 | **0** (cut ≤ 1) ✅ |
| Skeptical flag kinds | state-behavior-mismatch, vocabulary-contradiction, commitment-without-anchor, unsupported-recurrence | identical kinds + identical evidence quotes + identical `note` strings | 0 |
| `canonical_with_conflict`-eligible | A4, B2 | A4, B2 | 0 |
| Partial-parse entries | B3 | B3 | 0 |
| Mean per-entry latency | 32.8 s | 37.1 s | +13% (engine non-determinism in *wall clock*, not output) |

**Factor 4: PASS.** Both consistency cuts cleared by the maximum possible margin (perfect
overlap, zero flag-count delta).

## Rubric verdict — all four factors

| Factor | Cut | Result |
|---|---|---|
| 1. Meaningful divergence ≥ 50% | 50% | 73% ✅ |
| 2. `canonical_with_conflict` reachable ≥ 2 | 2 | A4 + B2 = 2 ✅ |
| 3. Parse stability ≥ 90% lens-calls, 0 timeouts | 90% / 0 | 97.8% (44/45), 0 ✅ |
| 4. Run-to-run consistency Jaccard ≥ 0.75, flag delta ≤ 1 | 0.75 / 1 | 1.0 / 0 ✅ |

Architecture validated. ADR-002 §"Addendum (2026-05-12, seventh)" records the verdict and
ties off the rubric revision started in the sixth addendum.

## Pipeline

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
  -PinferenceBackend=gpu \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
adb logcat -d -s VestigeSttD > docs/stt-results/stt-d-2026-05-12-gpu-skep-rerun2.raw.log
```
