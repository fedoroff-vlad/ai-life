#!/usr/bin/env bash
#
# Consistency drift-lint — the MECHANIZABLE half of the change-propagation discipline
# (see CLAUDE.md §Change-propagation map). When one artifact changes, coupled artifacts
# must change with it; this script fails CI when they fall out of sync so the drift can't
# merge silently. The non-mechanizable half (prose, architecture) stays a human checklist
# in the change-map.
#
# Design: fast, no build/Java deps — runs on EVERY push/PR including docs-only ones (docs
# drift is exactly what it catches). Each check is small, explicit and self-describing.
# Extend it by adding a check block below; keep false positives at zero (a noisy lint gets
# ignored) by scanning only files where a match is unambiguously wrong.
#
# Run locally: scripts/check-consistency.sh
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
err() { echo "  ✗ $*" >&2; fail=1; }

# Canonical source of truth for local Ollama model tags = the Mac deploy overlay.
CANON="infra/.env.mac.example"

# ── Check 1: retired model tags must not linger in OPERATIONAL files ──────────────────
# When a model is retired, add its exact tag to RETIRED_TAGS. "Operational" = files that
# actually run or configure the stack. Prose/history (READMEs, plans/, docs/, memory) may
# cite an old tag legitimately ("validated on qwen2.5:7b"), so they are deliberately NOT
# scanned — only these files, where a retired tag means a real stale config.
RETIRED_TAGS="qwen2.5:7b qwen2.5:72b qwen2.5:7b-instruct qwen2.5:72b-instruct"
OPERATIONAL_PATHS="scripts/golden.sh infra/.env.example .github/workflows"
echo "check 1: no retired model tags in operational files"
for tag in $RETIRED_TAGS; do
  hits="$(grep -rnF -- "$tag" $OPERATIONAL_PATHS 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    err "retired model tag '$tag' still referenced in an operational file:"
    echo "$hits" | sed 's/^/        /' >&2
    err "→ point it at the current model (see $CANON), or if the tag is back in use drop it from RETIRED_TAGS in $0"
  fi
done

# ── Check 2: golden.sh must pin a model the canonical deploy actually pulls ────────────
# Ties the golden runner to the SSOT: whatever chat/fast model golden.sh boots the gateway
# with must be a tag the Mac deploy declares (assignment or pull list), so the runner can't
# silently drift onto a model nobody pulls.
echo "check 2: golden.sh gateway models are a subset of the canonical Ollama set ($CANON)"
allowed="$(grep -oE 'qwen[0-9][A-Za-z0-9._:-]*|minicpm-v|nomic-embed-text|bge-m3' "$CANON" | sort -u)"
pinned="$(grep -oE 'LLM_(DEFAULT|FAST)_MODEL=[^ ]+' scripts/golden.sh | sed 's/.*=//' | sort -u || true)"
for m in $pinned; do
  if ! printf '%s\n' "$allowed" | grep -qxF -- "$m"; then
    err "golden.sh pins '$m', which is not in the canonical model set of $CANON"
    err "→ add it to $CANON (and pull it) or fix the pin in scripts/golden.sh"
  fi
done

echo ""
if [ "$fail" -ne 0 ]; then
  echo "consistency check FAILED — resolve the ✗ items above." >&2
  exit 1
fi
echo "consistency check passed."
