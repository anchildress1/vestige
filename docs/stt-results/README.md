# STT evidence archive

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
