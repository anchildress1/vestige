#!/usr/bin/env bash

set -euo pipefail

failures=0

check_command() {
  local name="$1"
  local install_hint="$2"

  if command -v "$name" >/dev/null 2>&1; then
    printf 'ok   %s -> %s\n' "$name" "$(command -v "$name")"
  else
    printf 'miss %s -> %s\n' "$name" "$install_hint"
    failures=$((failures + 1))
  fi
}

check_path() {
  local name="$1"
  local value="${!name:-}"
  local hint="$2"

  if [[ -n "$value" && -d "$value" ]]; then
    printf 'ok   %s -> %s\n' "$name" "$value"
  else
    printf 'miss %s -> %s\n' "$name" "$hint"
    failures=$((failures + 1))
  fi
}

printf 'Vestige environment doctor\n\n'

check_path "JAVA_HOME" "export JAVA_HOME=\$(/usr/libexec/java_home -v 25), or use /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
check_path "ANDROID_HOME" "export ANDROID_HOME=\$HOME/Library/Android/sdk"

check_command "adb" "add \$ANDROID_HOME/platform-tools to PATH"
check_command "ktlint" "brew install ktlint"
check_command "detekt" "brew install detekt"
check_command "gitleaks" "brew install gitleaks"
check_command "actionlint" "brew install actionlint"
check_command "lefthook" "brew install lefthook"
check_command "gh" "brew install gh"

if [[ "$failures" -gt 0 ]]; then
  printf '\n%d issue(s) found. Fix those before trusting local build results.\n' "$failures"
  exit 1
fi

printf '\nAll required local tools are visible.\n'
