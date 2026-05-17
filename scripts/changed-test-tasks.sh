#!/usr/bin/env bash
# Outputs Gradle test tasks for modules with files changed vs origin/main.
# Exits 0 with empty output when nothing changed (caller should skip test run).
set -euo pipefail

ALL_TASKS=":core-model:test :core-inference:testDebugUnitTest :core-storage:testDebugUnitTest :app:testDebugUnitTest"

BASE=$(git merge-base HEAD "origin/main" 2>/dev/null \
    || git merge-base HEAD "origin/master" 2>/dev/null)

# No remote base available — can't determine scope safely, run everything.
if [ -z "$BASE" ]; then
    printf '%s\n' "$ALL_TASKS"
    exit 0
fi

# --no-renames treats renames as delete+add so both source and destination
# paths appear; prevents cross-module moves from being detected as app-only.
COMMITTED=$(git diff --name-only --no-renames "${BASE}...HEAD" 2>/dev/null || true)
STAGED=$(git diff --name-only --no-renames --cached 2>/dev/null || true)
UNSTAGED=$(git diff --name-only --no-renames 2>/dev/null || true)
CHANGED=$(printf '%s\n%s\n%s\n' "$COMMITTED" "$STAGED" "$UNSTAGED")

# Root-level Gradle config changes affect all modules — run everything.
if echo "$CHANGED" | grep -qE '^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew[^/]*|gradle/)'; then
    printf '%s\n' "$ALL_TASKS"
    exit 0
fi

has_change() { echo "$CHANGED" | grep -q "^${1}/" ; }

tasks=""
has_change "core-model"     && tasks="$tasks :core-model:test"
has_change "core-inference" && tasks="$tasks :core-inference:testDebugUnitTest"
has_change "core-storage"   && tasks="$tasks :core-storage:testDebugUnitTest"
has_change "app"            && tasks="$tasks :app:testDebugUnitTest"

printf '%s\n' "${tasks# }"
