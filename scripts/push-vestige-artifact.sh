#!/usr/bin/env bash
# Push a single Vestige artifact (main model, embedding model, or tokenizer) into the
# installed debug app's filesDir/models/ via `run-as`. Skips with a notice when the local
# file is missing and the artifact is flagged optional; exits non-zero when a required
# artifact is missing.
#
# Args: <package> <local-path> <target-filename> <required|optional>
set -euo pipefail

if [ "$#" -ne 4 ]; then
    echo "usage: $0 <package> <local-path> <target-filename> <required|optional>" >&2
    exit 2
fi

package="$1"
local_path="$2"
target_filename="$3"
required="$4"

if [ ! -f "$local_path" ]; then
    if [ "$required" = "required" ]; then
        echo "❌ Required artifact missing: $local_path" >&2
        echo "   The SHA-256 must match core-model/src/main/resources/model/manifest.properties." >&2
        exit 1
    fi
    echo "→ Optional artifact not found: $local_path; skipping."
    exit 0
fi

echo "→ Pushing $target_filename to /data/local/tmp/..."
adb push "$local_path" /data/local/tmp/vestige-artifact-push >/dev/null
echo "→ Streaming into $package files/models/ via run-as..."
adb shell "cat /data/local/tmp/vestige-artifact-push | run-as $package sh -c 'mkdir -p files/models && cat > files/models/$target_filename'"
adb shell "rm /data/local/tmp/vestige-artifact-push"
echo "✅ $target_filename installed."
