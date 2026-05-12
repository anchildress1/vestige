# STT-D run — 2026-05-12 (GPU, sharpened lens framings + 3 retries)

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
| `maxAttemptsPerLens` | **3** (bumped from 2) |
| Lens prompts | `7a4b929` — sharpened LITERAL / INFERENTIAL / SKEPTICAL framings |
| Harness commit | `337edfa` |
| Wall clock | ~25 min |

## Verdict

**PASS — 13/15 (87%) meaningful divergence.** Significantly higher than:
- Same backend pre-fix (8/15, 53%)
- Same backend with greedy alone (9/15, 60%)
- CPU same fixture (12/15, 80%)

The signal is also **structurally richer** than prior runs — 8 entries diverge on multiple
axes (`tags + state_shift`, `tags + Skeptical flag`, etc.) and 7 Skeptical flags fired
(vs 2 before).

## Per-entry summary

| ID | Lenses parsed | Latency (s) | Meaningful | Divergence axes |
|---|---|---|---|---|
| A1 | 2/3 | 105.2 | ✅ | `tags` + `state-behavior-mismatch` flag |
| A2 | 3/3 | 54.0 | ✅ | `tags` + `state_shift` + `unsupported-recurrence` flag |
| A3 | 2/3 | 97.7 | ✅ | `tags` + `state-behavior-mismatch` flag |
| **A4** | 3/3 | 56.9 | ✅ | **`tags` + `energy_descriptor` + `state_shift` + `vocabulary-contradiction` flag (4 axes)** |
| A5 | 2/3 | 128.0 | ✅ | `tags` |
| A6 | 3/3 | 62.9 | ✅ | `tags` + `state_shift` + `state-behavior-mismatch` flag |
| B1 | 3/3 | 62.7 | ✅ | `tags` + `state-behavior-mismatch` flag |
| B2 | 3/3 | 69.2 | ✅ | `tags` + `stated_commitment` + `commitment-without-anchor` flag |
| B3 | 3/3 | 49.4 | ✅ | `tags` + INF-only `tags` |
| **C1** | **0/3** | **308.7** | ❌ | **TIMED OUT** — runaway generation, single model call exceeded 5-min budget |
| C2 | 2/3 | 91.0 | ✅ | `tags` + `state-behavior-mismatch` flag |
| C3 | 3/3 | 51.7 | ✅ | `tags` |
| D1 | 3/3 | 62.5 | ✅ | `tags` |
| **D2** | **1/3** | 128.1 | ❌ | only 1 lens parsed — no triangulation possible |
| D3 | 2/3 | 94.3 | ✅ | `tags` |

**Latency**: min 49.4s, max 308.7s (C1 timeout), mean 88.1s. About 1.8× the previous GPU
greedy run (48s mean) — cost of richer prompts + retry budget.

## Sample of richer divergence — A4

Before (greedy with old prompts):
```
LITERAL     tags=[battery-yanked]
INFERENTIAL tags=[battery-yanked]
SKEPTICAL   tags=[battery-drain, sync]
```
Divergence: `tags` only. 1 axis.

After (sharpened prompts):
```
LITERAL     tags=[battery-yanked, sync] energy=null state_shift=false flags=[]
INFERENTIAL tags=[tired, battery-yanked, sync] energy=tired state_shift=false flags=[]
SKEPTICAL   tags=[battery-yanked, sync] energy=null state_shift=true
            flags=[vocabulary-contradiction:"Not tired exactly":
                   The user's energy state is immediately contradicted by 'battery got yanked'.]
```
Divergence: 4 axes — `tags` (INF adds `tired`), `energy_descriptor` (INF only), `state_shift`
(SKEP only), and a Skeptical flag with quoted evidence. This is the demo signal — the
architecture surfacing real interpretive disagreement on a single entry.

## Sample of richer divergence — A2

Before (greedy with old prompts):
```
All three lenses returned tags=[meeting, late-night], everything else null. Not meaningful.
```

After (sharpened):
```
LITERAL     tags=[tuesday-meeting, concrete-in-my-limbs, late-night]
INFERENTIAL tags=[meeting, concrete-shoes]
SKEPTICAL   tags=[tuesday, meeting, same-thing, normal, concrete, limbs, late-night, lost-plot]
            flags=[unsupported-recurrence:"Same thing.":
                   The recurrence claim lacks supporting history.]
```
Divergence: `tags` (three completely different tag sets, including LITERAL's verbatim
`concrete-in-my-limbs` and INFERENTIAL's pattern label `concrete-shoes`), `state_shift`, and a
Skeptical flag.

## The trade

**Cost:**
- Mean latency 1.8× (48s → 88s). Still under ADR-002's 30–90s background budget for most entries.
- 2 entries lost / partial (C1 timed out at 308s; D2 only 1/3 lenses parsed) — ~13% of corpus.
- Worst-case per-entry latency at the 5-min ceiling.

**Gained:**
- +27 pp meaningful divergence (60% → 87%).
- 8 entries with multi-axis divergence (was 2).
- 7 Skeptical flags fired with quoted evidence (was 2 before greedy, 0 with greedy alone).
- A2 + A4 + A6 + B1 + B2 — all entries the demo would highlight — now produce rich,
  defensible divergence signal.

## Known issues to track

1. **C1 runaway generation.** First lens generated for 5 minutes without completing (single
   model call exceeded `PER_ENTRY_TIMEOUT_MS = 300_000`). Likely a `maxNumTokens=4096` ceiling
   interaction — the model didn't naturally terminate the structured output. Worth a future
   investigation; not blocking for v1.
2. **D2 partial parse (1/3 lenses).** With sharpened prompts the FP16 jitter affects multiple
   lenses on a single entry. 3 retries doesn't fully absorb it. Resolver runs on 1 lens →
   entry saves with all-`candidate` confidence per ADR-002 §"Edge case — lens errors mid-call".
3. **Latency variance.** A1 (105s), A3 (98s), A5 (128s), D2 (128s) all hit the retry path.
   Production user impact: occasional ~2-min background extraction instead of the typical
   50-60s. Acceptable for the demo; worth monitoring on real entries post-launch.

## Pipeline

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
  -PinferenceBackend=gpu \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
adb logcat -d -s VestigeSttD > docs/stt-results/stt-d-2026-05-12-gpu-sharp.raw.log
```
