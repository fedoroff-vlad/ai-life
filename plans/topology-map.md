# Topology map — runtime process grouping (ADR-0006 slice 2)

**Status:** draft (design only, no code). Slice 2 of epic
[#584](https://github.com/fedoroff-vlad/ai-life/issues/584) /
[ADR-0006](adr/ADR-0006-runtime-topology-footprint.md). The real host boundaries are confirmed by the
measurement pass (slice 1/3) on the Mac; this doc proposes the groupings the measurement validates.
**Not a commitment** — ADR-0006 stays Proposed until real RSS numbers land.

**Reads as input, does not restate:** the hot/cold split is owned by
[lifecycle.md](lifecycle.md) §Hot/cold set (LC-1, as-built); the tier taxonomy
(agent / domain-MCP / capability-MCP / platform) by [architecture.md](architecture.md). This doc only
**groups processes**; it never changes a module, a contract, or a test (ADR-0006 boundary).

## The reframe (why this doc exists)
`docker-compose` today runs one JVM **per module**. Module structure (code) and process count (deploy)
are separable — see [ADR-0006](adr/ADR-0006-runtime-topology-footprint.md) §The reframe. This map is the
deploy-time grouping; the module tree stays the SSOT for code.

## Inventory — the real JVM count
Enumerated from `infra/docker-compose.yml` (the runtime SSOT). **47 Spring Boot JVMs** (more than
ADR-0006's "~30+" estimate — reinforces the footprint concern), plus non-JVM backing that is **out of
scope** (it carries no JVM baseline).

| Tier | Count | Members |
|---|---|---|
| Agents (LLM + MCP reasoners) | 14 | briefing · calendar · chef · coach · coordinator · creator · docs · finance · notes · nutritionist · researcher · stylist · tasks · travel |
| Domain-MCP (own a schema) | 12 | mcp-briefing · mcp-caldav · mcp-coach · mcp-creator · mcp-docs · mcp-finance · mcp-ics-import · mcp-money-pro-import · mcp-nutrition · mcp-tasks · mcp-travel · mcp-wardrobe |
| Capability-MCP (schema-less) | 11 | mcp-chart-render · mcp-feeds · mcp-food-data · mcp-image-gen · mcp-market-data · mcp-media-processing · mcp-reddit · mcp-travel-search · mcp-weather · mcp-web · mcp-youtube |
| Platform (Java services) | 10 | calendar-web · conversation-service · gateway-telegram · llm-gateway · media-service · memory-service · notifier-service · orchestrator · profile-service · scheduler-service |
| **Total JVM** | **47** | |
| Non-JVM backing (out of scope) | — | postgres (+backup) · radicale · minio · searxng · whisper · grafana · liquibase (one-shot) · rclone-offsite · tailscale sidecars |

## Grouping principles
1. **A host is a hot/cold unit.** A cold module must not be co-hosted into an always-resident host (that
   would force it resident). Cold modules group by **co-usage affinity** so one cold wake brings up a
   coherent cluster on a single JVM baseline.
2. **Agent tier stays separate from MCP tier** inside the resident set — agents are LLM-bound, MCPs are
   DB-bound; separating them keeps bean-name / property-prefix / classpath merges clean and matches
   ADR-0006's "agent host / domain-MCP host" split.
3. **Isolated singletons never consolidate:** `llm-gateway` (holds/proxies the model, own LC-4 downshift
   lifecycle) and `memory-service` (heavy pgvector + Apache AGE reads on the hot path of every recall /
   ambient write — distinct resource profile, decided isolated up front, 2026-08-28). Non-JVM backing
   (Postgres/Radicale/MinIO/SearXNG/whisper/Grafana) is out of scope entirely.
4. **Native-image (path C, ADR-0006 Option C) targets the resident set first** — always-in-memory hosts
   have the biggest payoff.

## Proposed target topology (47 JVMs → ~12 processes)

### Resident (always in memory) — 5 processes
| Host | JVMs | Members |
|---|---|---|
| **Platform-hot** | 7 → 1 | gateway-telegram · orchestrator · profile-service · notifier-service · scheduler-service · conversation-service · media-service |
| **Agent-hot** | 6 → 1 | calendar-agent · finance-agent · tasks-agent · notes-agent · coordinator-agent · researcher-agent |
| **Domain-MCP-hot** | 5 → 1 | mcp-caldav · mcp-finance · mcp-tasks · mcp-web · mcp-media-processing |
| **memory-service** | isolated | pgvector + AGE reads; own resource profile |
| **llm-gateway** | isolated | model host; own downshift lifecycle |

### Cold (on-demand host-units, started/stopped as a unit)
| Host | Members |
|---|---|
| **Content** | creator-agent · mcp-creator · mcp-youtube · mcp-reddit · mcp-feeds |
| **Lifestyle** | stylist-agent · mcp-wardrobe · mcp-image-gen · nutritionist-agent · chef-agent · mcp-nutrition · mcp-food-data |
| **Brief+Travel** | briefing-agent · mcp-briefing · mcp-weather · travel-agent · mcp-travel · mcp-travel-search |
| **Docs** | docs-agent · mcp-docs (reads the hot `mcp-media-processing`) |
| **Finance-aux** | mcp-market-data · mcp-chart-render · mcp-money-pro-import · mcp-ics-import |
| **Coach** (parked) | coach-agent · mcp-coach — a cold host when the epic thaws (#289) |

`calendar-web` (+ tailscale sidecar) stays on its own opt-in `tunnel` profile — not consolidated.

## RAM projection (to be replaced by slice-1 real numbers)
Per-JVM baseline ~300 MB (ADR-0006); native ~30–60 MB (×5–10).

| | Today | Path B | Path B + C (native resident) |
|---|---|---|---|
| Resident set | ~20 hot JVMs ≈ 6 GB | ~5 processes ≈ 1.5 GB | ~5 × ~50 MB ≈ 0.25 GB |

(Matches lifecycle.md's "hot set ≈ 6 GB" today. The ~4.5 GB freed by B alone is the RAM-for-the-model
goal; C compounds it.)

## Open questions → deferred to measurement (slices 1/3, Mac)
- **Cold granularity** — 5 cold hosts vs finer. Native start (~50–100 ms) makes coarse grouping cheap, so
  favour fewer hosts unless a measured hotspot argues otherwise.
- **Platform-hot internal split** — if the measurement shows one platform module dominates RSS, split it
  out (same treatment memory-service got up front).
- **In-process vs HTTP inside a host** — a co-hosted call *may* become in-process where the boundary is
  genuinely internal; default is to keep the same localhost HTTP so nothing in the call path changes.

## Boundaries (from ADR-0006)
- Domain logic is never rewritten; domain-MCPs keep their schemas + contracts.
- Goldens / E2E must pass against the consolidated (and later native) artifacts, not only the dev topology
  — the guardrail that keeps correctness intact through the move.
- Hardware-gated: real RSS and native builds need the Mac; this design + the slice-1 harness are authored
  now, executed at deploy.
