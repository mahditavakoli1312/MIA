#!/usr/bin/env bash
# Pushes the AI-team files into a repo MIA has already created.
#
# The MIA app does this itself (TeamFilesUpdater, "update the team files" in the app), but that
# path needs a rebuilt and reinstalled app, and a repo whose agents are broken cannot wait for
# one. This is the same operation from a laptop: every untyped entry in NetworkModule's
# BOOTSTRAP_ASSETS — the workflows and the scripts, never a project-type file like AGENTS.md or
# the design system, which belong to the project and may have been edited — copied from
# app/src/main/assets/ into the target repo's .github/.
#
# It commits one file at a time through the Contents API, because that is the only way to write
# to .github/workflows/ with a token that has `workflow` scope, and because a file that is
# already identical is then skipped rather than committed as a no-op.
#
#   ./scripts/push-team-files.sh <owner>/<repo> [--dry-run]
#
# Needs `gh` authenticated with a token carrying `repo` and `workflow` scope:
#   gh auth login --scopes repo,workflow
#
# It does NOT touch repo variables or secrets, so a per-role model chosen in the app is
# overwritten by the literal defaults in these files. Re-apply the model from the app afterwards
# if you had pointed a role somewhere else (see docs/github/migrate-agent-model.sh).
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

target="${1:-}"
dry_run=""
[ "${2:-}" = "--dry-run" ] && dry_run="yes"

if [ -z "$target" ]; then
  echo "usage: $0 <owner>/<repo> [--dry-run]" >&2
  exit 1
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "✗ The GitHub CLI (gh) is not installed." >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "✗ gh is not logged in. Run: gh auth login --scopes repo,workflow" >&2
  exit 1
fi

module="app/src/main/java/ir/mahditavakoli/mia/network/NetworkModule.kt"
assets_dir="app/src/main/assets"

# Untyped entries only — `Bundled("asset", "path")` with no ProjectType. Those are the team
# files every project gets, and the only ones it is safe to overwrite in a live repo.
pairs="$(
  awk '/BOOTSTRAP_ASSETS = listOf\(/ { inside = 1; next }
       inside && /^[[:space:]]*\)/     { inside = 0 }
       inside                          { print }' "$module" \
  | sed -n 's/^[[:space:]]*Bundled("\([^"]*\)",[[:space:]]*"\([^"]*\)").*/\1 \2/p'
)"

if [ -z "$pairs" ]; then
  echo "✗ Could not read BOOTSTRAP_ASSETS out of $module — has the list moved?" >&2
  exit 1
fi

pushed=0
same=0
failed=0

while read -r asset repo_path; do
  [ -n "$asset" ] || continue
  local_file="$assets_dir/$asset"
  if [ ! -f "$local_file" ]; then
    echo "✗ $local_file does not exist" >&2
    failed=$((failed + 1))
    continue
  fi

  # The remote blob sha, needed to update rather than create. Empty means the file is new.
  sha="$(gh api "repos/$target/contents/$repo_path" --jq .sha 2>/dev/null || true)"

  # Skip a file that is already byte-for-byte what we would push. git's blob sha is over
  # "blob <size>\0<content>", which is exactly what the Contents API reports.
  if [ -n "$sha" ] && [ "$(git hash-object "$local_file")" = "$sha" ]; then
    echo "=  $repo_path (already current)"
    same=$((same + 1))
    continue
  fi

  if [ -n "$dry_run" ]; then
    echo "→  $repo_path (would ${sha:+update}${sha:-create})"
    pushed=$((pushed + 1))
    continue
  fi

  if gh api -X PUT "repos/$target/contents/$repo_path" \
       -f message="mia: refresh $repo_path from the MIA team files" \
       -f content="$(base64 < "$local_file" | tr -d '\n')" \
       ${sha:+-f sha="$sha"} >/dev/null 2>&1; then
    echo "✓  $repo_path"
    pushed=$((pushed + 1))
  else
    echo "✗  $repo_path — the push was refused." >&2
    echo "   A workflow file needs a token with \`workflow\` scope: gh auth refresh -s workflow" >&2
    failed=$((failed + 1))
  fi
done <<< "$pairs"

echo
echo "$pushed pushed, $same already current, $failed failed."
if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "The agents in $target now run the files in this checkout."
