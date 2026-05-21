# STT evidence archive

## STT-H — single-pass prompt rebuild + 3-lens convergence

Captures of `SinglePassExtractionTuningTest` over a 12-entry diagnostic corpus (inlined in the
test). Pure observation — no assertions. Records the rebuilt 3-lens × surfaces extraction after
the prompt rebuild: examples stripped from every lens + shared surface, `energy_descriptor` capped
to a one/two-word state (A/B/C rules), single-word tag rule, and the Literal-corroboration gate on
`energy_descriptor` / `state_shift`. Set the test's `lenses` list to one lens to tune it in
isolation, or all three to capture convergence.

### Filename format

`stt-h-<YYYY-MM-DD>.md` (GPU text backend; multiple same-day runs get a `-N` suffix)

### Contents

1. **Setup** — date, device, model, backend, harness, branch, question
2. **Verdict** — parse rate, determinism, gate behaviour, collateral
3. **Measured numbers** — model calls, parse-fails/retries, mean + range per-entry latency, prompt size, wall clock, reproducibility
4. **Per-entry table** — latency, resolved energy + verdict, state_shift + verdict, deterministic template label
5. **Notable** — Skeptical flags, recurrence-without-history, the template-label catch-all
6. **Pipeline** — exact command + raw-log capture

### Log tag

`VestigeTuning` — one `TUNING_PROMPT` line (composed prompt size) + one `TUNING {…}` line per entry
with `elapsedMs`, `modelCalls`, per-lens `lensMs` (latency/attempts), per-lens energy/state_shift,
the resolved fields with verdicts + flags, and the deterministic template label.

---

## STT-F — concurrent-inference viability

One-off feasibility capture of `RamWallClockProbeTest` against the on-device Engine. Answered
whether `litertlm-android:0.11.0` supports concurrent independent conversations on one Engine
(the Story 2.6.6 / 2.19 Path C premise). Verdict: **no — single-session enforced**; v1 ships
sequential. See `stt-f-2026-05-17.md`. Not a recurring corpus run — re-capture only if the SDK
is bumped past 0.11.0.

### Log tag

`VestigeLiteRtLm` + `VestigeBackgroundExtraction` — RAM/wall-clock probe line plus the per-lens
`FAILED_PRECONDITION` evidence.

---

## STT-E — embedding retrieval comparison

Captures of `SttEEmbeddingComparisonTest` runs against the 18-entry corpus from
`docs/stt-e-manifest.example.txt`. Compares hybrid retrieval (tag + keyword + recency + cosine)
against a tag-only baseline across four scenario queries.

### Filename format

`stt-e-<YYYY-MM-DD>.md` (no backend suffix — embedder runs on CPU only)

### Contents

Each capture file includes:

1. **Setup** — date, device, embedding model, manifest, pass threshold, context
2. **Verdict** — win count vs threshold, pass/fail
3. **Per-query table** — baseline relevant/5, hybrid relevant/5, novel entries, outcome
4. **Raw evidence** — per-query top-5 lists and observations
5. **Pipeline** — exact commands to reproduce

### Log tag

`VestigeSttE` — emits per-query baseline and hybrid top-5 with relevant counts and novel relevant
entry IDs, plus a final `STT-E win-rate: N/M queries` summary line.

---

## STT-D — lens divergence

Captures of `SttDLensDivergenceTest` runs against the canonical-plus-extras corpus from
`docs/stt-d-manifest.example.txt`. One file per backend per device-run.

Captures of `SttDLensDivergenceTest` runs against the canonical-plus-extras corpus from
`docs/stt-d-manifest.example.txt`. One file per backend per device-run.

## Filename format

`stt-d-<YYYY-MM-DD>-<backend>.md`

- `<backend>` ∈ `cpu`, `gpu`
- Multiple runs on the same day get a suffix: `stt-d-2026-05-12-cpu-2.md`

## Contents

Each capture file includes:

1. **Run header** — date, device, model artifact, manifest path, backend, threshold
2. **Per-entry table** — id, lenses parsed, latency, divergence kind, meaningful (✓ / ✗)
3. **Raw per-lens evidence** — full fields map + Skeptical flags as emitted by the harness `RAW`
   lines (one block per entry, one row per lens)
4. **Divergence summary** — disagreement fields, inferential-only fields, Skeptical-flag reasons
5. **Verdict** — divergent count vs threshold, pass/fail, observations

## Capture pipeline

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PmanifestPath=/data/local/tmp/stt-d-manifest.txt \
  -PinferenceBackend=cpu \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.SttDLensDivergenceTest
adb logcat -d -s VestigeSttD > docs/stt-results/stt-d-$(date +%F)-cpu.raw.log
```

The harness emits three line types under tag `VestigeSttD`:

- `ENTRY id=<id> elapsed=Nms lenses_parsed=N/3 model_calls=N` — per-entry header
- `RAW id=<id> lens=<LENS> attempts=N err=<msg> fields=<map> flags=<list>` — full per-lens output
- `DIVERGENCE id=<id> disagree_fields=… inferential_only=… skeptical_flags_kept=… meaningful=…`

Plus a final `=== STT-D summary: N/M divergent ===` block.
