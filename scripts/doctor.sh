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

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
  if [[ "$java_version" == *\"25* ]]; then
    printf 'ok   JDK version -> %s\n' "$java_version"
  else
    printf 'miss JDK version -> expected 25, got %s\n' "$java_version"
    failures=$((failures + 1))
  fi
fi

if [[ -n "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$ANDROID_HOME/platforms/android-35" ]]; then
    printf 'ok   Android platform -> android-35\n'
  else
    printf 'miss Android platform -> install SDK Platform 35\n'
    failures=$((failures + 1))
  fi

  if [[ -d "$ANDROID_HOME/build-tools" ]] && find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | grep -q .; then
    latest_build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sed 's#.*/##' | sort | tail -n 1)"
    printf 'ok   Android build-tools -> %s\n' "$latest_build_tools"
  else
    printf 'miss Android build-tools -> install Android SDK build-tools\n'
    failures=$((failures + 1))
  fi
fi

check_command "adb" "add \$ANDROID_HOME/platform-tools to PATH"
check_command "ktlint" "brew install ktlint"
check_command "detekt" "brew install detekt"
check_command "gitleaks" "brew install gitleaks"
check_command "actionlint" "brew install actionlint"
check_command "lefthook" "brew install lefthook"
check_command "gh" "brew install gh"

if [[ "$failures" -gt 0 ]]; then
  printf '\n%d issue(s) found. Fix those before trusting local build results.\n' "$failures"
  printf 'CI parity target: JDK 25, Android SDK platform 35, Gradle wrapper 9.1.0.\n'
  exit 1
fi

printf '\nAll required local tools are visible.\n'
printf 'CI parity target: JDK 25, Android SDK platform 35, Gradle wrapper 9.1.0.\n'
