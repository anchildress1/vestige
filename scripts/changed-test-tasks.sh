#!/usr/bin/env bash
# Outputs Gradle test tasks for modules with files changed vs origin/main.
# Exits 0 with empty output when nothing changed (caller should skip test run).
set -euo pipefail

BASE=$(git merge-base HEAD "origin/main" 2>/dev/null \
    || git merge-base HEAD "origin/master" 2>/dev/null \
    || git rev-parse "HEAD^" 2>/dev/null \
    || git rev-parse HEAD)

COMMITTED=$(git diff --name-only "${BASE}...HEAD" 2>/dev/null || true)
STAGED=$(git diff --name-only --cached 2>/dev/null || true)
UNSTAGED=$(git diff --name-only 2>/dev/null || true)
CHANGED=$(printf '%s\n%s\n%s\n' "$COMMITTED" "$STAGED" "$UNSTAGED")

ALL_TASKS=":core-model:test :core-inference:testDebugUnitTest :core-storage:testDebugUnitTest :app:testDebugUnitTest"

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
