# STT-D run — 2026-05-12 (GPU, Skeptical-only revision)

## Setup

| Field | Value |
|---|---|
| Date | 2026-05-12 |
| Device | Galaxy S24 Ultra (SM-S928U, Android 16) |
| Model | `gemma-4-E4B-it.litertlm` |
| Backend | GPU (OpenCL delegate) |
| Manifest | `docs/stt-d-manifest.example.txt` (15 A-D entries) |
| Threshold | `ceil(15 × 30%) = 5` entries meaningful divergence |
| SamplerConfig | `topK=1, topP=1.0, temperature=0.0, seed=42` (greedy) |
| maxNumTokens | 4096 |
| `maxAttemptsPerLens` | 2 (back to baseline) |
| Lens prompts | LITERAL + INFERENTIAL restored to baseline; SKEPTICAL revised to 1398 chars (43 chars under the 1441 baseline budget) |
| Harness commit | `2865004` |
| Wall clock | ~9 min |

## Verdict

**Ship.** 11/15 (73%) divergent. **4 Skeptical flags fired with quoted user evidence** — the
adversarial lens is doing real work for the first time on the corpus. `canonical_with_conflict`
verdict is reachable on 2 entries (A4, B2). Demo Chapter 3 has 4 flagged entries to choose
from.

## Headline numbers vs prior runs

| Metric | GPU greedy baseline | GPU Skeptical-only (this run) | Δ |
|---|---|---|---|
| Meaningful divergence | 9/15 (60%) | **11/15 (73%)** | **+13 pp** |
| Skeptical flags fired | **0** | **4** | **+4** |
| `canonical_with_conflict`-eligible entries | 0 | **2** (A4, B2) | +2 |
| Multi-axis divergence entries | 2 | 4 (A1, A3, A6, C1) | +2 |
| Mean per-entry latency | 48s | **33s** | **−31%** |
| Full 3-lens entries | 15/15 | 14/15 (B3 partial) | −1 |
| Timeouts | 0 | 0 | 0 |

(Mean latency dropped because LITERAL + INFERENTIAL went back to their shorter baseline prompts;
SKEPTICAL is also under-budget. Total per-call prefill is smaller than the greedy baseline.)

## The 4 Skeptical flags

| Entry | Flag kind | Quoted evidence (model's `note`) |
|---|---|---|
| A1 | `state-behavior-mismatch` | *"I was fine before it, then completely flattened by 11"* — state change lacks a triggering event |
| A4 | `vocabulary-contradiction` | *"Not tired exactly. More like the battery got yanked after the sync."* — user negated tiredness then reasserted energy |
| B2 | `commitment-without-anchor` | *"Said I would send the invoice today. Instead I reorganized the desktop."* — commitment was not followed by the action |
| C1 | `unsupported-recurrence` | *"Same three criteria"* — claim lacks supporting history |

All 4 fire on patterns the prompt names explicitly with examples. The model is matching the
in-context examples, not generalizing past them — known limitation, documented below.

## A4 + B2 — the `canonical_with_conflict` entries

Both entries have `disagree_fields=[]` AND a Skeptical flag fired. The other two lenses agreed
on the underlying field values; SKEPTICAL annotated the agreement with a contradiction marker.

This is the exact verdict ADR-002 §"Convergence Resolver Contract" promised:

> The convergence resolver writes `canonical_with_conflict` when Literal and Inferential agree
> on the underlying value and Skeptical flags it.

**In the GPU greedy baseline (60%/0 flags), this verdict was unreachable on any entry.** It's
now reachable on A4 and B2.

## Per-entry summary

| ID | Lenses parsed | Latency (s) | Meaningful | Divergence axes |
|---|---|---|---|---|
| A1 | 3/3 | 30.9 | ✅ | `tags` + `state-behavior-mismatch` flag |
| A2 | 3/3 | 24.0 | ❌ | — (lenses converged on `[meeting, late-night]`) |
| A3 | 3/3 | 34.4 | ✅ | `tags` + `energy_descriptor` + INF-only `energy_descriptor` |
| **A4** | 3/3 | 32.3 | ✅ | **`vocabulary-contradiction` flag only — `canonical_with_conflict` eligible** |
| A5 | 3/3 | 29.8 | ❌ | — (no field disagreement, no flag fired) |
| A6 | 3/3 | 28.8 | ✅ | `tags` + `state_shift` |
| B1 | 3/3 | 33.2 | ✅ | `tags` |
| **B2** | 3/3 | 46.1 | ✅ | **`commitment-without-anchor` flag only — `canonical_with_conflict` eligible** |
| B3 | 2/3 | 41.5 | ❌ | — (one lens parse-failed; resolver fell through) |
| C1 | 3/3 | 38.5 | ✅ | `tags` + `unsupported-recurrence` flag |
| C2 | 3/3 | 35.9 | ✅ | `tags` |
| C3 | 3/3 | 33.9 | ❌ | — |
| D1 | 3/3 | 34.7 | ✅ | `tags` |
| D2 | 3/3 | 35.9 | ✅ | `tags` |
| D3 | 3/3 | 35.3 | ✅ | `tags` |

## Known limitation — in-context-example matching

Skeptical fires on the **exact pattern shapes the prompt names**, not on adjacent cases that
share the principle but use different words. Entries that fired Skeptical flags in the
sharpened-prompt experiment (Run 3) but didn't this time:

- A2 `unsupported-recurrence` on "Same thing." — fired in Run 3, didn't here
- A3 `state-behavior-mismatch` on "Three tabs open, zero movement" — fired in Run 3, didn't here
- A6 `state-behavior-mismatch` on "static in my head" — fired in Run 3, didn't here
- B1 `state-behavior-mismatch` on "sacred ritual of closing it" — fired in Run 3, didn't here
- C2 `state-behavior-mismatch` on "looping" — fired in Run 3, didn't here

The longer Run 3 prompts had paragraph-length triggers covering more pattern shapes. The
under-budget revision keeps one-line triggers per flag kind, so the model only matches the
exact example shapes. Expanding the trigger coverage costs FP16 budget — confirmed by the Run 3
parse-failure rate (13% timeouts, 33% partial parses). The trade is: fewer flags, no parse
regression.

If Phase 4 wants more flags, the lever is **Option 2 from the investigation document** —
surfaces per lens, ADR-002 supersede — not more in-context triggers.

## Pipeline

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
  -PinferenceBackend=gpu \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
adb logcat -d -s VestigeSttD > docs/stt-results/stt-d-2026-05-12-gpu-skep.raw.log
```
