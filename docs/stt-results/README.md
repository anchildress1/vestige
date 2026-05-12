# STT-D evidence archive

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
