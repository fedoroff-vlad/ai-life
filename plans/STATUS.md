# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ Mac deployment + hot/cold lifecycle — [lifecycle.md](lifecycle.md) (owner-signed 2026-07-10).**
  Target: Mac Studio M4 Max 64/512 running ai-life 24/7; always-on **hot** set + auto **cold** start/stop
  via a new `platform/supervisor`, plus dynamic ai-life model downshift (32B↔14B) when the separate
  coder tenant runs. Decisions 1–5 signed (socket-proxy · instant cold-start via CDS/AOT · TTL env ·
  creator cold · deploy-mvp first). **`deploy-mvp`:** Mac/Ollama env profile (`.env.mac.example`) ✅, per-JVM
  heap caps (`-Xmx768m` via an `x-jvm-opts` anchor on all 44 in-house services, compose-config validated) ✅,
  `infra/README` refresh ✅ — **remaining = first real `docker compose --profile hot up` + cross-domain smoke
  on the Mac** (config authored blind; the Mac isn't purchased yet — hardware-blocked).
  **LC-1 compose hot/cold profiles ✅ 2026-07-15** (27 hot / 24 cold / 2 tunnel; finance aux MCPs decoupled —
  see [lifecycle.md](lifecycle.md) §Hot/cold set). **Next actionable = LC-2 supervisor + socket-proxy**
  (LC-2.5 cold-tolerant discovery → LC-3(+3a AOT) → LC-4 model-manager → LC-5). coach-agent parked (Backlog).

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **(Optional) fast/slow test split** — surefire unit vs failsafe container ITs, to speed the local inner loop; low value since full `verify` runs the same tests and iterating already uses `-Dtest=Class`. Pick up only if the dev loop hurts.

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295, **off-site DB backup replication** (daily local dumps ship 2026-07-13; a second host over Tailscale or a cloud bucket is the follow-up — see [infra/README.md](../infra/README.md) §Database backups).
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
