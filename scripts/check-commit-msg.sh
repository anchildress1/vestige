#!/usr/bin/env bash
# Enforce Conventional Commits with lowercase type and a non-empty subject.
# Replaces commitlint so the repo doesn't drag in a node toolchain.
#
# Usage:
#   scripts/check-commit-msg.sh <commit-msg-file>           # lefthook commit-msg
#   scripts/check-commit-msg.sh --range <base>..<head>      # CI over PR commits

set -euo pipefail

# Conventional Commits subject: type(scope)?!?: subject (any case, must start with a letter)
readonly TYPES='feat|fix|chore|docs|style|refactor|test|ci|perf|build|revert'
readonly PATTERN="^(${TYPES})(\\([a-z0-9._/-]+\\))?!?: [A-Za-z].+$"

reject() {
  local subject="$1"
  cat >&2 <<EOF
✗ Commit subject does not match Conventional Commits with lowercase type and a letter-led subject.

  Got:      ${subject}
  Expected: <type>(<scope>)?: <subject>
  Types:    feat, fix, chore, docs, style, refactor, test, ci, perf, build, revert
  Subject:  must start with a letter (proper nouns and acronyms are allowed to be capitalized)

Examples:
  feat: capture loop with detective persona
  fix(audio): drop pcm buffer after extraction
  chore: bump kotlin to 2.3.21

Spec: https://www.conventionalcommits.org
EOF
  exit 1
}

is_passthrough() {
  local subject="$1"
  case "$subject" in
    # Default git-generated subjects for merges, squash-merges, and reverts.
    "Merge "*|"Squashed commit "*|"Revert "*) return 0 ;;
    # release-please managed subjects.
    "chore: release main"|"chore(release): "*) return 0 ;;
    *) return 1 ;;
  esac
}

check_subject() {
  local subject="$1"
  is_passthrough "$subject" && return 0
  [[ "$subject" =~ $PATTERN ]] || reject "$subject"
}

if [[ "${1:-}" == "--range" ]]; then
  range="${2:?range required, e.g. origin/main..HEAD}"
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    check_subject "$line"
  done < <(git log "$range" --format='%s')
else
  msg_file="${1:?commit message file required}"
  subject="$(head -n1 "$msg_file")"
  check_subject "$subject"
fi
