#!/usr/bin/env bash
# Sequential runner for every instrumented test class on the connected device.
# Each test class needs different instrumentation args; gradle can't run them all in
# one invocation without arg conflicts (manifestPath, modelPath, audioPath collide).
# Logs land under docs/stt-results/full-suite-<YYYY-MM-DD>/<class>.{log,verdict}.
set -uo pipefail

DATE="$(date -u +%Y-%m-%d)"
RESULTS_DIR="docs/stt-results/full-suite-${DATE}"
mkdir -p "$RESULTS_DIR"
SUMMARY="$RESULTS_DIR/SUMMARY.tsv"
printf "class\tverdict\twall_clock\tnotes\n" > "$SUMMARY"

MODEL=/data/local/tmp/gemma-4-E4B-it.litertlm
AUDIO=/data/local/tmp/stt-b/stt-b1.wav
C_MANIFEST=/data/local/tmp/stt-c-manifest.txt
D_MANIFEST=/data/local/tmp/stt-d-manifest.txt
E_MANIFEST=/data/local/tmp/stt-e-manifest.txt
EMB_MODEL=/data/local/tmp/vestige-emb/embeddinggemma-300M_seq512_mixed-precision.tflite
EMB_TOKENIZER=/data/local/tmp/vestige-emb/sentencepiece.model

run_test() {
  local class="$1"
  shift
  local args=("$@")
  local log="$RESULTS_DIR/${class}.gradle.raw.log"
  local logcat="$RESULTS_DIR/${class}.logcat.raw.log"
  local start=$(date +%s)
  echo "================================================================"
  echo "[$(date -u +%H:%M:%S)] $class"
  echo "================================================================"
  adb logcat -c
  if ./gradlew :app:connectedDebugAndroidTest \
       -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige."$class" \
       "${args[@]}" \
       > "$log" 2>&1; then
    verdict="PASS"
  else
    verdict="FAIL"
  fi
  local end=$(date +%s)
  local wall=$(( end - start ))
  adb logcat -d > "$logcat"
  # Notes come from the JUnit `Finished N tests` line + counters, not from the streaming
  # `Tests N/M completed. (0 skipped) (0 failed)` chatter that contains the literal substring
  # "skipped" on every clean run.
  local notes=""
  local finished
  finished=$(/usr/bin/grep -oE "Finished [0-9]+ tests" "$log" 2>/dev/null | head -1)
  if [[ -n "$finished" ]]; then
    notes="${notes}${finished// /-};"
  fi
  if /usr/bin/grep -q "AssumptionViolatedException" "$log" 2>/dev/null; then
    notes="${notes}assumeTrue-violated;"
  fi
  printf "%s\t%s\t%ds\t%s\n" "$class" "$verdict" "$wall" "$notes" | tee -a "$SUMMARY"
}

run_test SttAAudioPlumbingTest \
  -PmodelPath=$MODEL \
  -PaudioPath=$AUDIO

run_test LiteRtLmTextSmokeTest \
  -PmodelPath=$MODEL

run_test LiteRtLmStreamingTextSmokeTest \
  -PmodelPath=$MODEL

run_test PersonaToneSmokeTest \
  -PmodelPath=$MODEL

run_test PerCapturePersonaSmokeTest \
  -PmodelPath=$MODEL \
  -PaudioPath=$AUDIO

run_test GoblinHoursAddendumSmokeTest \
  -PmodelPath=$MODEL \
  -PaudioPath=$AUDIO

# PatternEngineSmokeTest omitted from future suite runs — the 14m 33s CPU wall clock
# (6 entries × 3 lenses × ~47s) is the single largest cost in the suite and the test's
# pattern-detection signal (1× template_recurrence(4) over the corpus) is captured in the
# 2026-05-12 archive. Re-enable when there's a reason to re-validate the full Phase-3 stack.
# To run on demand:
#   ./gradlew :app:connectedDebugAndroidTest \
#     -PmodelPath=$MODEL -PmanifestPath=$C_MANIFEST -PinferenceBackend=gpu \
#     -Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.PatternEngineSmokeTest

run_test SttCTagStabilityTest \
  -PmodelPath=$MODEL \
  -PmanifestPath=$C_MANIFEST

run_test SttDLensDivergenceTest \
  -PmodelPath=$MODEL \
  -PmanifestPath=$D_MANIFEST \
  -PinferenceBackend=gpu

run_test EmbeddingGemmaSmokeTest \
  -PembeddingModelPath=$EMB_MODEL \
  -PembeddingTokenizerPath=$EMB_TOKENIZER

run_test SttEEmbeddingComparisonTest \
  -PembeddingModelPath=$EMB_MODEL \
  -PembeddingTokenizerPath=$EMB_TOKENIZER \
  -PmanifestPath=$E_MANIFEST

echo ""
echo "================================================================"
echo "SUMMARY"
echo "================================================================"
cat "$SUMMARY"
