#!/usr/bin/env bash
#
# Runtime footprint harness — ADR-0006 slice 1 (epic #584).
#
# Captures per-process RSS + the total, and the JVM-vs-model-vs-Postgres split on the RUNNING stack,
# so the "~10 GB of framework overhead competes with the model for RAM" premise is MEASURED, not
# assumed (ADR-0006 is measurement-first), and the host groupings in plans/topology-map.md are
# validated against real numbers.
#
# Authored now, run at deploy: the real RSS numbers need the Mac (the dev box is a Citrix VDI with no
# Docker daemon). Running it here just reports "nothing running" and exits 0 — a measurement tool must
# never fail a pipeline.
#
# Classification is signal-driven — no embedded copy of the topology map (that would be a second SSOT):
#   - container image  ai-life/*:local     -> JVM app  (memory-service / llm-gateway flagged as the
#                                             isolated singletons per topology-map.md)
#   - container image  pgvector/pgvector*  -> Postgres
#   - any other image                      -> non-JVM backing (radicale / minio / searxng / whisper / ...)
#   - the model is Ollama on the HOST (not a container, `brew services start ollama`) -> read from `ps`.
# Cold hosts that are stopped simply don't appear; the snapshot reflects whatever is actually resident,
# which is the number that matters for the RAM-for-the-model goal.
#
# Note on the metric: for containers we read `docker stats` memory usage (the cgroup working set), which
# is the correct RAM-used analog for "how much this process costs"; for the host Ollama we read RSS from
# `ps`. On Apple Silicon the model also occupies unified GPU memory that `ps` RSS may under-report — the
# `model` line prints a caveat so the number is read with that in mind.
#
# Usage:
#   scripts/measure-footprint.sh                 # human table + split summary
#   scripts/measure-footprint.sh --json          # one machine-readable object (for slice-3 before/after diff)
#   COMPOSE_FILE=infra/docker-compose.yml scripts/measure-footprint.sh   # override compose file (unused today; reserved)
#   OLLAMA_PROC=ollama scripts/measure-footprint.sh                      # override the host model process match
#
set -euo pipefail
cd "$(dirname "$0")/.."

JSON=false
for arg in "$@"; do
  case "$arg" in
    --json) JSON=true ;;
    -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
    *) echo "!! unknown arg: $arg (try --help)" >&2; exit 2 ;;
  esac
done

OLLAMA_PROC="${OLLAMA_PROC:-ollama}"

# --- helpers ---------------------------------------------------------------

# Normalise a docker-stats memory token (e.g. "512.4MiB", "1.23GiB", "900KiB", "12B") to MB (float).
to_mb() {
  local raw="$1" num unit
  num="$(printf '%s' "$raw" | sed -E 's/([0-9.]+).*/\1/')"
  unit="$(printf '%s' "$raw" | sed -E 's/[0-9.]+//')"
  case "$unit" in
    GiB|GB) awk -v n="$num" 'BEGIN{printf "%.1f", n*1024}' ;;
    MiB|MB) awk -v n="$num" 'BEGIN{printf "%.1f", n}' ;;
    KiB|KB) awk -v n="$num" 'BEGIN{printf "%.1f", n/1024}' ;;
    B|"")   awk -v n="$num" 'BEGIN{printf "%.1f", n/1048576}' ;;
    *)      awk -v n="$num" 'BEGIN{printf "%.1f", n}' ;;
  esac
}

# Classify a container into a tier from its image + name.
classify() {
  local image="$1" name="$2"
  case "$image" in
    ai-life/*)
      case "$name" in
        *memory-service*|*llm-gateway*) echo "jvm-singleton" ;;
        *) echo "jvm-app" ;;
      esac ;;
    pgvector/pgvector*) echo "postgres" ;;
    *) echo "backing" ;;
  esac
}

# --- gather ----------------------------------------------------------------

DOCKER_OK=true
if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  DOCKER_OK=false
fi

# rows: "name<TAB>tier<TAB>mb"
ROWS=""
if $DOCKER_OK; then
  # name -> image map for running containers
  declare -A IMAGE_OF=()
  while IFS=$'\t' read -r cname cimage; do
    [[ -z "$cname" ]] && continue
    IMAGE_OF["$cname"]="$cimage"
  done < <(docker ps --format '{{.Names}}\t{{.Image}}')

  while IFS=$'\t' read -r cname cmem; do
    [[ -z "$cname" ]] && continue
    local_image="${IMAGE_OF[$cname]:-unknown}"
    tier="$(classify "$local_image" "$cname")"
    # docker stats MemUsage is "used / limit" — keep the used token only.
    cmem_used="${cmem%% /*}"
    mb="$(to_mb "$cmem_used")"
    ROWS+="${cname}\t${tier}\t${mb}"$'\n'
  done < <(docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}')
fi

# host model (Ollama) RSS in MB — sum over matching processes; 0 if none.
MODEL_MB="$(ps -Ao rss,comm 2>/dev/null | awk -v p="$OLLAMA_PROC" 'tolower($2) ~ tolower(p) {s+=$1} END{printf "%.1f", s/1024}' || true)"
if [[ -z "$MODEL_MB" ]]; then MODEL_MB="0.0"; fi

# --- aggregate -------------------------------------------------------------

sum_tier() { # $1 = tier
  printf '%b' "$ROWS" | awk -F'\t' -v t="$1" '$2==t {s+=$3} END{printf "%.1f", s+0}'
}
count_tier() { printf '%b' "$ROWS" | awk -F'\t' -v t="$1" '$2==t {c++} END{print c+0}'; }

JVM_APP_MB="$(sum_tier jvm-app)";        JVM_APP_N="$(count_tier jvm-app)"
JVM_SGL_MB="$(sum_tier jvm-singleton)";  JVM_SGL_N="$(count_tier jvm-singleton)"
PG_MB="$(sum_tier postgres)"
BACK_MB="$(sum_tier backing)";           BACK_N="$(count_tier backing)"
JVM_MB="$(awk -v a="$JVM_APP_MB" -v b="$JVM_SGL_MB" 'BEGIN{printf "%.1f", a+b}')"
JVM_N=$(( JVM_APP_N + JVM_SGL_N ))
GRAND_MB="$(awk -v j="$JVM_MB" -v p="$PG_MB" -v m="$MODEL_MB" -v b="$BACK_MB" 'BEGIN{printf "%.1f", j+p+m+b}')"

HOST="$(hostname 2>/dev/null || echo unknown)"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- render ----------------------------------------------------------------

if $JSON; then
  # per-process array + the split summary, one object.
  procs_json="$(printf '%b' "$ROWS" | awk -F'\t' 'NF==3{
    if(n++) printf ",";
    printf "{\"name\":\"%s\",\"tier\":\"%s\",\"mb\":%s}", $1,$2,$3
  }')"
  cat <<EOF
{
  "timestamp": "$TS",
  "host": "$HOST",
  "docker_available": $DOCKER_OK,
  "processes": [$procs_json],
  "split": {
    "jvm_mb": $JVM_MB, "jvm_count": $JVM_N,
    "jvm_app_mb": $JVM_APP_MB, "jvm_app_count": $JVM_APP_N,
    "jvm_singleton_mb": $JVM_SGL_MB, "jvm_singleton_count": $JVM_SGL_N,
    "postgres_mb": $PG_MB,
    "model_mb": $MODEL_MB,
    "backing_mb": $BACK_MB, "backing_count": $BACK_N,
    "grand_total_mb": $GRAND_MB
  }
}
EOF
  exit 0
fi

echo "ai-life runtime footprint — $TS — host $HOST"
if ! $DOCKER_OK; then
  echo "!! docker unavailable — no containers measured (expected on the dev VDI; run at deploy on the Mac)."
fi
echo ""
printf '%-34s  %-14s  %10s\n' "PROCESS" "TIER" "RSS (MB)"
printf '%-34s  %-14s  %10s\n' "----------------------------------" "--------------" "----------"
if [[ -n "$ROWS" ]]; then
  printf '%b' "$ROWS" | sort -t$'\t' -k3 -rn | awk -F'\t' '{printf "%-34s  %-14s  %10.1f\n", $1,$2,$3}'
fi
# the host model as a synthetic row so the table is complete
printf '%-34s  %-14s  %10.1f\n' "ollama (host model)" "model" "$MODEL_MB"
echo ""
echo "── split ──────────────────────────────────────────────"
printf '  %-24s %10.1f MB  (%d JVMs)\n' "JVM apps"          "$JVM_APP_MB" "$JVM_APP_N"
printf '  %-24s %10.1f MB  (%d JVMs)\n' "JVM singletons"    "$JVM_SGL_MB" "$JVM_SGL_N"
printf '  %-24s %10.1f MB  (%d JVMs total)\n' "JVM subtotal" "$JVM_MB" "$JVM_N"
printf '  %-24s %10.1f MB\n' "Postgres"          "$PG_MB"
printf '  %-24s %10.1f MB  (unified GPU mem may exceed this on Apple Silicon)\n' "Model (Ollama host)" "$MODEL_MB"
printf '  %-24s %10.1f MB  (%d containers)\n' "Backing (non-JVM)" "$BACK_MB" "$BACK_N"
printf '  %-24s %10.1f MB\n' "GRAND TOTAL"       "$GRAND_MB"
echo ""
echo "ADR-0006 premise check: JVM overhead ($JVM_MB MB across $JVM_N JVMs) vs model ($MODEL_MB MB)."
echo "Consolidation target (topology-map.md): 47 JVMs → ~12 hosts. Re-run after each consolidation slice to diff."
