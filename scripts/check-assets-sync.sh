#!/usr/bin/env bash
# Fails when app/src/main/assets/ and docs/github/ have drifted apart.
#
# Those two directories hold the SAME files: assets/ is what the app uploads into every new
# repo at runtime, docs/github/ is the readable copy this documentation links to. A reader who
# follows docs/github/ and gets a different file than the agents actually run is worse served
# than one with no documentation at all, so the rule is byte-for-byte identity — and this script
# is what enforces it, locally and in CI.
#
# The pair list is DERIVED from NetworkModule.BOOTSTRAP_ASSETS rather than repeated here: a list
# maintained by hand is one more thing to forget when an asset is added, which is the exact
# failure this guards against.
set -uo pipefail

module="app/src/main/java/ir/mahditavakoli/mia/network/NetworkModule.kt"
assets_dir="app/src/main/assets"
docs_dir="docs/github"

if [ ! -f "$module" ]; then
  echo "✗ Cannot find $module — run this from the repository root." >&2
  exit 1
fi

# "asset-name" to ".github/path/in/the/new/repo"  →  two space-separated fields.
pairs="$(
  awk '/BOOTSTRAP_ASSETS = listOf\(/ { inside = 1; next }
       inside && /^[[:space:]]*\)/     { inside = 0 }
       inside                          { print }' "$module" \
  | sed -n 's/^[[:space:]]*"\([^"]*\)"[[:space:]]*to[[:space:]]*"\([^"]*\)".*/\1 \2/p'
)"

if [ -z "$pairs" ]; then
  echo "✗ Could not read BOOTSTRAP_ASSETS out of $module — has the list moved?" >&2
  exit 1
fi

failed=0
checked=""
while read -r asset repo_path; do
  [ -n "$asset" ] || continue
  # docs/github/ mirrors the repo layout minus the .github/ prefix, so .github/scripts/x.js
  # lives at docs/github/scripts/x.js and a root file like AGENTS.md at docs/github/AGENTS.md.
  doc_path="$docs_dir/${repo_path#.github/}"
  asset_path="$assets_dir/$asset"
  checked="$checked $doc_path"

  if [ ! -f "$asset_path" ]; then
    echo "✗ $asset_path is registered in BOOTSTRAP_ASSETS but does not exist." >&2
    failed=1
    continue
  fi
  if [ ! -f "$doc_path" ]; then
    echo "✗ $doc_path is missing. Copy it: cp $asset_path $doc_path" >&2
    failed=1
    continue
  fi
  if ! diff -q "$asset_path" "$doc_path" >/dev/null; then
    echo "✗ $asset_path and $doc_path have drifted apart:" >&2
    diff -u "$doc_path" "$asset_path" | head -n 40 >&2
    echo "  Fix by copying whichever one is right: cp $asset_path $doc_path" >&2
    failed=1
    continue
  fi
  echo "✓ $asset  ==  ${doc_path#"$docs_dir/"}"
done <<< "$pairs"

# The other direction: a file in docs/github/ that no asset claims means an asset was added to
# the docs but never registered, so the app would never upload it.
#
# Two files in docs/github/ are deliberately not managed pairs and are skipped: README.md, which
# documents the directory, and migrate-agent-model.sh, a maintenance tool run by a human against
# already-bootstrapped repos — it is never uploaded into one.
docs_only="README.md migrate-agent-model.sh"
while read -r orphan; do
  case " $checked " in
    *" $orphan "*) continue ;;
  esac
  case " $docs_only " in
    *" $(basename "$orphan") "*) continue ;;
  esac
  echo "✗ $orphan has no matching entry in BOOTSTRAP_ASSETS — register it in $module" >&2
  echo "  (or delete it, if it is not meant to be uploaded to generated repos)." >&2
  failed=1
done < <(find "$docs_dir" -type f | sort)

if [ "$failed" -ne 0 ]; then
  echo >&2
  echo "assets ↔ docs are out of sync. See docs/github/README.md §9." >&2
  exit 1
fi

echo "All managed files are byte-for-byte identical."
