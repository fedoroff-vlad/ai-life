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
# silently drift onto a model nobody pulls. Env-indirected models (the openai profile's
# LLM_*_MODEL="$GOLDEN_OPENAI_MODEL", #359) are NOT pins — the operator supplies the tag at
# run time — so values starting with $ / " / ' are excluded; only literal tags are checked.
echo "check 2: golden.sh gateway models are a subset of the canonical Ollama set ($CANON)"
allowed="$(grep -oE 'qwen[0-9][A-Za-z0-9._:-]*|minicpm-v|nomic-embed-text|bge-m3' "$CANON" | sort -u)"
pinned="$(grep -oE 'LLM_(DEFAULT|FAST)_MODEL=[^ ]+' scripts/golden.sh | sed 's/.*=//' | grep -vE '^[$"'\'']' | sort -u || true)"
for m in $pinned; do
  if ! printf '%s\n' "$allowed" | grep -qxF -- "$m"; then
    err "golden.sh pins '$m', which is not in the canonical model set of $CANON"
    err "→ add it to $CANON (and pull it) or fix the pin in scripts/golden.sh"
  fi
done

# ── Check 3: embedding dim ↔ Liquibase context ↔ migration vector(N) move as one ──────
# The change-map `embedding-model-dimension` coupling: swapping the real embedding provider
# changes three knobs that MUST agree — MEMORY_EMBED_DIM, the `embed-NNN` Liquibase context
# that activates the widen migration, and the `vector(N)` the migration ALTERs the column to.
# Drift here is silent and only surfaces as a runtime dim-mismatch on the deploy. We scan the
# canonical real-deploy overlay ($CANON) — the only file that overrides the mock 384 default —
# plus the migration its context activates. Zero false positives: dev/mock (no embed-* context)
# is deliberately not scanned.
echo "check 3: embedding dim ↔ embed-NNN context ↔ migration vector(N) agree in $CANON"
# `|| true` on every substitution below: a non-match is a *finding* we report via err(),
# not a reason for `set -e` to abort the script mid-check.
embed_dim="$(grep -oE '^MEMORY_EMBED_DIM=[0-9]+' "$CANON" | head -1 | sed 's/.*=//' || true)"
ctx_dim="$(grep -oE '^LIQUIBASE_CONTEXTS=[^ ]+' "$CANON" | head -1 | grep -oE 'embed-[0-9]+' | head -1 | sed 's/embed-//' || true)"
if [ -z "$ctx_dim" ]; then
  echo "        (no embed-NNN context active in $CANON — mock/dev dim, nothing to cross-check)"
elif [ "$embed_dim" != "$ctx_dim" ]; then
  err "MEMORY_EMBED_DIM=$embed_dim but LIQUIBASE_CONTEXTS activates embed-$ctx_dim in $CANON"
  err "→ the fail-fast dim check and the widen migration would disagree; make them equal"
else
  migration="$(grep -rlE "contexts: *embed-$ctx_dim( |$)" infra/liquibase/features/ 2>/dev/null | head -1 || true)"
  if [ -z "$migration" ]; then
    err "no Liquibase migration declares 'contexts: embed-$ctx_dim' (activated by $CANON)"
    err "→ add the embed-$ctx_dim widen migration under infra/liquibase/features/ (do NOT amend an existing embed-NNN)"
  elif ! grep -qE "TYPE +vector\($ctx_dim\)" "$migration"; then
    err "$migration is the embed-$ctx_dim migration but does not ALTER the column to vector($ctx_dim)"
    err "→ its 'ALTER … TYPE vector(N)' must match MEMORY_EMBED_DIM=$embed_dim"
  fi
fi

# ── Check 4: plans/INDEX.md stays a statusless map — no dates / completion emoji ───────
# INDEX is the "what a file covers / when to read it" map; progress status belongs in exactly
# one place each — STATUS.md (in-flight), roadmap.md (stages/epics), or the ADR header.
# Restating status in INDEX is a top drift source (e.g. an epic marked COMPLETE here while the
# ADR moved on — the drift caught 2026-08-08). We forbid the two UNAMBIGUOUS status smells:
# ISO dates and ✅/❌/🚧 completion emoji — every past drift instance carried one. Word tokens
# (DONE/shipped/COMPLETE) are deliberately NOT grepped: they occur legitimately as scope
# ("shipped work lives in HISTORY") or in the header rule text — the header rule + review cover
# those. Scans only INDEX.md, so zero false positives elsewhere.
echo "check 4: plans/INDEX.md carries no dates / completion emoji (statusless map)"
index_smells="$(grep -nE '20[0-9]{2}-[0-9]{2}-[0-9]{2}|✅|❌|🚧' plans/INDEX.md 2>/dev/null || true)"
if [ -n "$index_smells" ]; then
  err "plans/INDEX.md contains status/date markers — it must stay a statusless map:"
  echo "$index_smells" | sed 's/^/        /' >&2
  err "→ move the status to STATUS.md / roadmap.md / the ADR header; leave only scope + 'read when' here"
fi

# ── Check 5: a capability /internal/* passthrough client is shared, not copy-pasted ───
# The duplication that bit us 2026-08-14: the mcp-web/weather/chart/media-processing
# `/internal/*` passthrough clients were byte-identical copies pasted into every agent that
# needed them (WebSearchClient ×7, ChartRenderClient ×2, GeocodeClient ×2, CaptionClient ×3).
# A capability HTTP client is SHARED CODE — it belongs in libs/agent-runtime/http with an opt-in
# @Bean per consumer (MediaStoreClient/ChartRenderClient/GeocodeClient/WebSearchClient/CaptionClient
# do this). So the same `/internal/*` URI literal appearing in >1 agent MODULE means a client was
# copy-pasted instead of lifted. We scan only `domains/*/*-agent/src/main` (the MCP servers that
# *define* /internal controllers live in mcp-*/ and shared/mcp/, not *-agent; the shared clients
# live in libs/, not scanned) so a hit is unambiguously an agent-side copy.
# ALLOWLIST: a deliberate cross-domain DOMAIN read (not a capability) that legitimately hits another
# domain's /internal endpoint — prefer an inter-agent action over the hub, but until then list it here
# with a why so the guard stays green and still catches real capability copies.
# - /internal/events — mcp-caldav's calendar-DOMAIN read (not a capability, so nothing to lift into
#   agent-runtime). calendar-agent reads its OWN domain-MCP for the create_event double-booking sanity
#   check (#485); briefing-agent reads it cross-domain for the morning digest's calendar section (could
#   later move onto a calendar `brief`/hub action, like finance's spend read did).
echo "check 5: capability /internal/* passthrough clients are shared, not per-agent copies"
ALLOWLIST_URIS="/internal/events"
dupes="$(
  for d in domains/*/*-agent; do
    [ -d "$d/src/main" ] || continue
    mod="${d##*/}"
    grep -rhoE '"/internal/[a-z0-9/_-]+"' "$d/src/main" 2>/dev/null | tr -d '"' | sort -u \
      | sed "s#\$#\t$mod#" || true
  done | awk -F'\t' '{n[$1]++; who[$1]=who[$1]" "$2} END{for(u in n) if(n[u]>1) print u"|"who[u]}'
)"
while IFS='|' read -r uri who; do
  [ -z "$uri" ] && continue
  case " $ALLOWLIST_URIS " in *" $uri "*) continue ;; esac
  err "capability passthrough '$uri' is called from >1 agent module (copy-pasted client):$who"
  err "→ lift ONE shared client into libs/agent-runtime/http (see ChartRender/Geocode/WebSearch/Caption) + an opt-in @Bean per agent; or, if it's a deliberate cross-domain domain read, add it to ALLOWLIST_URIS in $0 with a why"
done <<< "$dupes"

# ── Check 6: every libs/* module is in the ci.yml pre-install list ────────────────────
# The PR CI pre-installs the whole libs/* layer into .m2 so a GIB-pruned, -T2-parallel reactor can
# resolve any lib SNAPSHOT order-independently (see .github/workflows/ci.yml). The list is hand-maintained,
# so a NEW lib silently drops off it — invisible until a PR drags a dependent of that lib into the parallel
# reactor and the build dies on "Could not find artifact …" (bit us 2026-08-15: libs/sharing was missing,
# a platform-common change pulled calendar-web in, and its sharing dep failed to resolve under -T2). This
# check fails the moment a libs/* module (a dir with a pom.xml) is absent from that -pl list.
echo "check 6: every libs/* module is in the ci.yml pre-install list"
preinstall="$(grep -oE '\-pl libs/[a-zA-Z0-9,/_-]+' .github/workflows/ci.yml | head -1 | sed 's/^-pl //' || true)"
if [ -z "$preinstall" ]; then
  err "could not find the 'mvn … install -pl libs/…' pre-install list in .github/workflows/ci.yml"
else
  for d in libs/*/; do
    name="${d%/}"                       # e.g. libs/sharing
    [ -f "$name/pom.xml" ] || continue
    case ",$preinstall," in
      *",$name,"*) : ;;
      *) err "libs module '$name' is missing from the ci.yml pre-install -pl list"
         err "→ add '$name' to the pre-install step in .github/workflows/ci.yml (a new lib must join it, or PRs that drag a dependent into the -T2 reactor fail to resolve its SNAPSHOT)" ;;
    esac
  done
fi

# ── Check 7: untrusted-ingestion synthesis flows frame retrieved content as data ──────
# The change-map `ingestion-source` coupling (#599; architecture.md §Security). A flow that folds
# text from an untrusted external source into an LLM turn must include UntrustedContent.GUARD as a
# system prompt, so an injected instruction in a fetched page / OCR'd document is not obeyed (the
# outbound confirm-gate only stops the *act*; this stops the *obey*). We flag any *-agent flow that
# both makes an LLM call (.coordinate( or .chat() AND ingests untrusted external content — web
# (WebSearchClient / PageFetchClient) or OCR (OcrClient) — but never references UntrustedContent.GUARD.
# ALLOWLIST = a flow that provably never feeds the retrieved text to the LLM (e.g. uses the client for a
# deterministic, non-prompt path); a flow that DOES must gain GUARD, not an allowlist entry. A listed
# file that HAS GUARD is a stale entry and also fails — so the list can only shrink.
echo "check 7: untrusted-ingestion flows include UntrustedContent.GUARD (injection doctrine, #599)"
INGEST_MARKERS='WebSearchClient|PageFetchClient|OcrClient|MediaFetchClient|MediaProcessingClient'
# Empty — every untrusted-ingestion flow now carries GUARD (researcher web + the 5 web flows + docs OCR).
GUARD_ALLOWLIST=""
for f in $(grep -rlE '\.coordinate\(|\.chat\(' domains/*/*-agent/src/main --include=*.java 2>/dev/null || true); do
  grep -qE "$INGEST_MARKERS" "$f" || continue
  has_guard=no; grep -q 'UntrustedContent.GUARD' "$f" && has_guard=yes
  listed=no; printf '%s\n' "$GUARD_ALLOWLIST" | grep -qxF "$f" && listed=yes
  if [ "$has_guard" = no ] && [ "$listed" = no ]; then
    err "untrusted-ingestion flow '$f' makes an LLM call over web/OCR content but omits UntrustedContent.GUARD"
    err "→ include UntrustedContent.GUARD as a system prompt + add a Golden…InjectionResistanceTest for a new mechanism (#599); or, if it genuinely never feeds retrieved text to the LLM, allowlist it in GUARD_ALLOWLIST in $0 with a why"
  elif [ "$has_guard" = yes ] && [ "$listed" = yes ]; then
    err "'$f' now includes UntrustedContent.GUARD but is still in GUARD_ALLOWLIST — remove the stale entry in $0 (the list only shrinks)"
  fi
done

# ── Check 8: every intent skill is referenced in its agent's routing golden ───────────
# The change-map `intent-skill-routing-golden` coupling (#543). A cue-routed agent's
# Golden*RoutingTest hardcodes its routable intent-skill set (a SKILLS allowlist + a loadXSkills name
# list) separate from the classpath SKILL.md files production loads — so adding an intent skill silently
# leaves the golden stale (hit adding task-delete, #486). An **intent skill** = a SKILL.md with NO
# non-empty `triggers:` list (trigger-less → routed by the LLM classifier; a skill WITH triggers is
# deterministic, not the router's job). Each such skill's name must appear (quoted) in a
# Golden*RoutingTest under its domain. ALLOWLIST = trigger-less skills that are still NOT intent-router
# choices — attachment/photo-driven pre-checks invoked deterministically on a media upload (a photo →
# receipt-parser / style-analyst / wardrobe-cataloguer / doc-archiver), so they never reach the text
# classifier. A domain with no routing golden at all (researcher single-skill, coach parked) is skipped.
echo "check 8: intent skills (trigger-less) appear in their agent's Golden*RoutingTest (#543)"
ROUTING_GOLDEN_ALLOWLIST="doc-archiver receipt-parser style-analyst wardrobe-cataloguer"
for sk in $(git ls-files 'domains/*/skills/*/SKILL.md'); do
  # Skip trigger skills (a non-empty `triggers:` list — inline [x] or a `- ` item under it).
  is_trig="$(awk '
    /^---/ {f++; if(f==2) exit; next}
    f==1 && /^triggers:[ \t]*\[[ \t]*[^] \t]/ {print "T"; exit}
    f==1 && /^triggers:[ \t]*$/ {p=1; next}
    f==1 && p && /^[ \t]*-/ {print "T"; exit}
    f==1 && p && /^[^ \t]/ {p=0}
  ' "$sk")"
  [ -n "$is_trig" ] && continue
  name="$(awk -F': ' '/^name:/{print $2; exit}' "$sk")"
  case " $ROUTING_GOLDEN_ALLOWLIST " in *" $name "*) continue ;; esac
  domain="$(printf '%s' "$sk" | sed -E 's#domains/([^/]+)/skills/.*#\1#')"
  goldens="$(git ls-files | grep -E "^domains/$domain/.*/Golden[A-Za-z]*RoutingTest\.java$" || true)"
  [ -z "$goldens" ] && continue   # no routing golden in this domain — coupling does not apply
  if ! printf '%s\n' $goldens | xargs grep -lqF "\"$name\"" 2>/dev/null; then
    err "intent skill '$name' ($sk) is trigger-less but not referenced in its routing golden:"
    echo "$goldens" | sed 's/^/        /' >&2
    err "→ add \"$name\" to the SKILLS set + loadXSkills list (+ a behaviour case) in that Golden*RoutingTest (#543); or, if it's an attachment/deterministic flow (not an intent-router choice), add it to ROUTING_GOLDEN_ALLOWLIST in $0 with a why"
  fi
done

# ── Check 9: every `*Test` named in a LIVE plan file resolves to a real test class ─────
# The change-map `plan-test-reference` coupling. A plan's WHEN/THEN `Scenario:` should name the
# test that asserts it ("asserted by `XTest`" — the travel.md convention); when that test is
# renamed or removed, the reference rots silently (hit when the stylist HTML renderer lifted to
# libs/doc-render: HtmlStylistRenderer→HtmlDocRenderer, and plans still named HtmlStylistRendererTest).
# Phase 1 (cheap, here): every backticked `…Test` in a live plan must resolve to a `<name>.java` in
# the tree. HISTORY.md is EXCLUDED — it is an archive (out of the session reading order per CLAUDE.md)
# that legitimately names classes by the name they had at the time. Phase 2 (strict, later, gated on
# the backfill): also require each `Scenario:` in a changed plan to carry an `(asserted by `XTest`)` link.
echo "check 9: *Test references in live plans resolve to a real test class (plan-test-reference)"
ALL_TESTS="$(git ls-files | grep -E '/[A-Za-z0-9]*Test\.java$' | sed -E 's#.*/##; s#\.java$##' | sort -u)"
# Documented placeholders used in authoring guidance ("asserted by `XTest`") — not real classes.
PLACEHOLDER_TESTS="XTest"
for pf in $(git ls-files 'plans/*.md' | grep -vx 'plans/HISTORY.md'); do
  refs="$(grep -oE '`[A-Z][A-Za-z0-9]*Test`' "$pf" 2>/dev/null | tr -d '`' | sort -u || true)"
  for t in $refs; do
    case " $PLACEHOLDER_TESTS " in *" $t "*) continue ;; esac
    printf '%s\n' "$ALL_TESTS" | grep -qxF "$t" && continue
    err "plan '$pf' references test '$t' which no longer exists (renamed/removed)"
    err "→ update the reference to the current test class, or drop it (HISTORY.md is exempt as an archive)"
  done
done

echo ""
if [ "$fail" -ne 0 ]; then
  echo "consistency check FAILED — resolve the ✗ items above." >&2
  exit 1
fi
echo "consistency check passed."
