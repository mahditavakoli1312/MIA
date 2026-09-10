#!/usr/bin/env bash
# Fails when the agent team can leave a task with nobody working on it.
#
# The system this repo installs into a generated project has one property everything else rests
# on: every open issue is in exactly one state, every state has one owner and at least one
# automatic exit, and no role may speak without naming who acts next. Break any part of that and
# nothing fails at runtime — an issue simply stops moving, which looks exactly like an idle repo
# until somebody asks, a week later, why the feature never shipped.
#
# That is what this script is for. It is the same idea as check-assets-sync.sh: a property no one
# can hold in their head, checked by a machine, with a message that says how to fix it.
#
# It runs the suite in scripts/tests/, which checks:
#   1. every state in agent-voice.js STATES has an owner and a way out;
#   2. every label the workflows create is one of those states (a label with no state is a state
#      nobody watches) — and vice versa;
#   3. postComment cannot be called without a handoff;
#   4. handoff() and parseHandoff() agree, including on text that would break an HTML comment;
#   5. every give-up path (QC's skip, the PO's giveUp, triage's four moves, the brief audit,
#      the unblock sweep) hands the work to somebody;
#   6. the shepherd cannot open, close or merge anything, and a brief is only ever closed after
#      an explicit yes.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

if ! command -v node >/dev/null 2>&1; then
  echo "✗ node is not installed — this check needs Node 20 or newer." >&2
  exit 1
fi

# `node --test <dir>` changed meaning between releases; the glob works on every version that has
# the test runner at all.
shopt -s nullglob 2>/dev/null || true
tests=(scripts/tests/*.test.js)
if [ "${#tests[@]}" -eq 0 ]; then
  echo "✗ No tests found under scripts/tests/ — the loop-closure check would pass vacuously." >&2
  exit 1
fi

if ! node --test "${tests[@]}"; then
  echo >&2
  echo "✗ The agent loop has a hole in it." >&2
  echo >&2
  echo "  Each failure above names the state, the function or the call site. The rule behind all" >&2
  echo "  of them is the same: a task must never be left without an owner and a next action." >&2
  echo "  See docs/agent-humanity-prompts.md and docs/github/README.md." >&2
  exit 1
fi

echo "✓ Every state has an owner and an exit, and no role can speak without naming the next one."
