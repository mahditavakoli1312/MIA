#!/usr/bin/env bash
# Repoint already-bootstrapped repos at a current model.
#
# MIA pushes the AI-team files into every repo it creates, so the model id is baked into the
# copy each repo owns. When a model goes away — as `stealth/ox-alpha` and the `openai/gpt-oss-*`
# defaults before it both did — those repos keep asking for it and every @tec / @po / @qc run
# fails. This rewrites the id in place, in every repo that still carries a dead one.
#
# Setting an AGENT_MODEL Actions *variable* is not enough on its own: TEC reads it through
# OpenCode and needs the provider prefix, while the PO/QC script sends it straight to the
# provider's API and must not have one. A single variable cannot satisfy both, so the files are
# what get fixed.
#
# Usage:
#   ./migrate-agent-model.sh owner/repo [owner/repo ...]   # named repos
#   ./migrate-agent-model.sh --all                         # every repo you own
#   ./migrate-agent-model.sh --dry-run --all               # show what would change
#   TO=z-ai/glm-5.2:free ./migrate-agent-model.sh --all    # a different target model
#   FROM=some/old-model ./migrate-agent-model.sh --all     # one specific id instead of the list
#   TO=MiniMax-M3 PROVIDER=minimax ./migrate-agent-model.sh --all   # move to a paid MiniMax account
#
# PROVIDER must match TO: it is the prefix OpenCode gets (`openrouter/` vs `minimax/`) and the
# `AGENT_PROVIDER` default the files carry. Repos bootstrapped before MiniMax support have no
# AGENT_PROVIDER default at all, so this script adds nothing there and they keep calling
# OpenRouter — for those, copy docs/github/workflows/ + docs/github/scripts/ over the repo's
# .github/ instead, which is the refresh described below. A MiniMax target also needs a
# MINIMAX_API_KEY Actions secret on each repo; this script does not touch secrets.
#
# Only the model id is rewritten; prose comments around it were written about the old model and
# are left alone. To refresh a repo completely instead, copy docs/github/workflows/ and
# docs/github/scripts/ over its .github/ — that also brings every other fix since it was created.
#
# Requires the `gh` CLI (and `jq`), logged in with repo write access. Commits straight to the
# default branch, one commit per file, and skips anything that does not contain a dead id.

set -euo pipefail

TO="${TO:-minimax/minimax-m3:free}"
# The service TO lives on: the OpenCode prefix, and the AGENT_PROVIDER default written below.
PROVIDER="${PROVIDER:-openrouter}"
# Every model MIA has defaulted to and OpenRouter has since withdrawn, newest first.
if [ -n "${FROM:-}" ]; then
  FROMS=("$FROM")
else
  FROMS=("stealth/ox-alpha" "openai/gpt-oss-120b:free" "openai/gpt-oss-20b:free")
fi
PATHS=(
  ".github/workflows/agent-issue-worker.yml"
  ".github/workflows/ai-role-review.yml"
  ".github/scripts/ai-role-review.js"
  ".github/scripts/token-usage.js"
)

dry_run=false
repos=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=true ;;
    --all)     while read -r r; do repos+=("$r"); done < <(gh repo list --limit 200 --json nameWithOwner -q '.[].nameWithOwner') ;;
    -*)        echo "unknown flag: $arg" >&2; exit 2 ;;
    *)         repos+=("$arg") ;;
  esac
done

if [ ${#repos[@]} -eq 0 ]; then
  echo "usage: $0 [--dry-run] (--all | owner/repo ...)" >&2
  exit 2
fi

# `openrouter/<id>` (OpenCode's provider/model form) and the bare `<id>` (the raw provider API
# form) both appear, so the prefixed one is parked behind a placeholder while the bare one is
# replaced — otherwise the second pass would rewrite the tail of the first.
#
# Any AGENT_PROVIDER default present is repointed too, so the prefix and the provider name can
# never drift apart. Files without one are left as they are; see the header.
rewrite() {
  local body; body="$(cat)"
  local from
  for from in "${FROMS[@]}"; do
    body="$(printf '%s' "$body" | sed \
      -e "s|openrouter/${from}|@@PREFIXED@@|g" \
      -e "s|${from}|${TO}|g" \
      -e "s|@@PREFIXED@@|${PROVIDER}/${TO}|g")"
  done
  body="$(printf '%s' "$body" | sed \
    -e "s#\(vars\.AGENT_PROVIDER *|| *'\)[^']*\('\)#\1${PROVIDER}\2#g" \
    -e "s#\(process\.env\.AGENT_PROVIDER *|| *\"\)[^\"]*\(\"\)#\1${PROVIDER}\2#g")"
  printf '%s' "$body"
}

# Prints the number of dead ids in the text on stdin.
count_dead() {
  local body; body="$(cat)"
  local total=0 from n
  for from in "${FROMS[@]}"; do
    n="$(printf '%s' "$body" | grep -c -- "$from" || true)"
    total=$((total + n))
  done
  printf '%s' "$total"
}

changed_repos=0
for repo in "${repos[@]}"; do
  repo_touched=false
  files_found=false
  for path in "${PATHS[@]}"; do
    meta="$(gh api "repos/${repo}/contents/${path}" 2>/dev/null || true)"
    # A 404 body is still JSON, so presence is decided by the sha, not by a non-empty reply.
    sha="$(printf '%s' "$meta" | jq -r '.sha // empty' 2>/dev/null || true)"
    [ -n "$sha" ] || continue                        # file not in this repo — older bootstrap
    files_found=true
    before="$(printf '%s' "$meta" | jq -r '.content' | tr -d '\n' | openssl base64 -d -A)"
    hits="$(printf '%s' "$before" | count_dead)"
    [ "$hits" -gt 0 ] || continue
    after="$(printf '%s' "$before" | rewrite)"

    echo "  ${repo}/${path}: ${hits} occurrence(s)"
    repo_touched=true
    if $dry_run; then continue; fi

    gh api -X PUT "repos/${repo}/contents/${path}" \
      -f message="chore(mia): point the AI team at ${TO}" \
      -f sha="$sha" \
      -f content="$(printf '%s' "$after" | openssl base64 -A)" >/dev/null
  done
  if $repo_touched; then
    changed_repos=$((changed_repos + 1))
  elif $files_found; then
    echo "  ${repo}: already up to date"
  else
    echo "  ${repo}: no MIA workflow files — skipped"
  fi
done

echo
if $dry_run; then echo "dry run — nothing was pushed."; fi
echo "repos needing the change: ${changed_repos}"
