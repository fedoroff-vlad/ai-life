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

## Native-image scope (path C) — targeted, not blanket
**We do not native-compile everything.** Native-image (ADR-0006 Option C) is a **per-host** decision on a
spectrum, driven by three questions: is the host resident (footprint payoff)? does instant-start matter
(latency payoff)? is the native build tractable (cost)? Whatever native does not fit falls back to
CDS/AOT (LC-3a, re-scoped as the *latency* lever) or stays a plain JVM.

| Layer | Treatment | Why |
|---|---|---|
| **Resident hosts** (Platform-hot · Agent-hot · Domain-MCP-hot · `memory-service` · `llm-gateway`) | **native — primary target** | always in memory → the ~5–10× RSS cut is a permanent saving; this is the footprint win |
| **Cold hosts** (Content · Lifestyle · Brief+Travel · Docs · Finance-aux) | **native optional — for latency, not RAM** | idle = stopped = 0 RAM, so no footprint gain; native's payoff here is instant wake (~50–100 ms vs ~8–20 s). Worth it where instant-start matters and the build is cheap; else **CDS/AOT** |
| **Native-hostile modules** (heavy reflection / dynamic classloading not covered by Spring Boot 4 AOT hints) | **stay JVM + CDS/AOT** | hint-chasing cost exceeds the benefit |
| **One-shot jobs** (`liquibase`) | **plain JVM** | runs at startup then exits — native pointless |
| **Non-JVM backing** (Postgres · Radicale · MinIO · SearXNG · whisper · Grafana) | **not applicable** | not our code, not a JVM |

Two guardrails keep this from over-investing:
- **B first makes C tractable.** Consolidation collapses 47 JVMs → ~12 hosts, so the native decision is
  made over ~12 targets, not 47 — and only ~5 resident ones are native-compiled for footprint.
- **Measurement-first (slice 1).** If the real numbers show 64 GB is already ample after B alone, **C may
  be optional entirely** — or reserved for the few worst offenders. ADR-0006 does not commit to
  "everything native" up front.

## Open questions → deferred to measurement (slices 1/3, Mac)
- **Cold granularity** — 5 cold hosts vs finer. Native start (~50–100 ms) makes coarse grouping cheap, so
  favour fewer hosts unless a measured hotspot argues otherwise.
- **Platform-hot internal split** — if the measurement shows one platform module dominates RSS, split it
  out (same treatment memory-service got up front).
- **In-process vs HTTP inside a host** — a co-hosted call *may* become in-process where the boundary is
  genuinely internal; default is to keep the same localhost HTTP so nothing in the call path changes.

## Slice 1 — measurement harness (spec)
ADR-0006 action item 1: a script that captures **per-process RSS + the total** and the **JVM vs model vs
Postgres** split on the running stack, so the ~10 GB overhead premise is *measured*, not assumed, and the
host groupings above are validated against real numbers. **Authored now, run at deploy** (real RSS needs
the Mac — the dev box has no Docker daemon). Tool: [`scripts/measure-footprint.sh`](../scripts/measure-footprint.sh).
Classification is signal-driven, no embedded topology copy (avoids a second SSOT): container image
`ai-life/*:local` → JVM app (`memory-service` / `llm-gateway` flagged as the isolated singletons),
`pgvector/pgvector` → Postgres, every other image → non-JVM backing; the model is Ollama on the **host**
(not a container), read from `ps`. Cold hosts that are stopped simply don't appear — the snapshot reflects
whatever is actually resident, which is the number that matters.

These four are asserted at deploy, not in CI: `measure-footprint.sh` is a shell harness whose real
numbers need the Mac (no Docker on the dev VDI), so each carries `not yet asserted` until then.

- Scenario: measuring a running stack → a per-process table (name · tier · RSS, sorted desc) **and** a
  split summary (JVM total + count · Postgres · model · backing · grand total) (not yet asserted — mac-gated shell harness).
- Scenario: the local model runs as a host Ollama process → its RSS is captured separately from the
  containers and reported in the `model` line (with the unified-memory caveat noted) (not yet asserted — mac-gated shell harness).
- Scenario: `--json` → the same numbers as one machine-readable object, so slice 3 can diff before/after
  consolidation without re-parsing the table (not yet asserted — mac-gated shell harness).
- Scenario: the stack (or Docker) is down → the script reports only what is running and exits 0 (a
  measurement tool never fails a pipeline), naming what it could not reach (not yet asserted — mac-gated shell harness).

## Slice 3 — consolidation spike (execution notes)
ADR-0006 action item 3, Path B / approach **B1** (owner-chosen): several domain-MCP module contexts in
**one JVM**, each keeping its own port + MCP server, so agents' `/internal/*` URLs and every contract stay
unchanged. The win is the ~300 MB per-process JVM baseline paid **once per host**.

**Prerequisite found by the spike — packaging must decouple "code module" from "runnable process" (3a).**
Each MCP module's *main* Maven artifact is today an executable **Boot fat jar** (classes under
`BOOT-INF/classes`), so a host module cannot depend on it — an in-reactor consumer breaks at the `package`
phase (`test-compile` passes, `verify` fails: `package … does not exist`). Fix: `spring-boot-maven-plugin`
with `<classifier>exec</classifier>` — the module's main artifact stays a **plain classes jar** (dependable
by the host), the executable becomes `<artifact>-exec.jar`, and the module Dockerfile runs that. No runtime
/ contract change; the module still builds + tests green. This is the enabler every consolidation host
needs; rolled out per module as consolidation proceeds.

- **3a (pilot):** `mcp-briefing` + `mcp-travel` switched to the `exec` classifier (pom + Dockerfile). Main
  artifact is now a plain classes jar; Docker runs `-exec.jar`. Both modules build + test green.
- **3b (done):** `deploy/domain-mcp-host` boots both pilot contexts in one JVM (distinct ports), proven by
  `DomainMcpHostFootprintIntegrationTest`.
- **3c (done — resident hot set):** 3a `exec`-classifier rolled out to the resident **Domain-MCP-hot**
  members (`mcp-caldav` · `mcp-finance` · `mcp-tasks` · `mcp-web` · `mcp-media-processing`; pom + Dockerfile),
  and `deploy/domain-mcp-host` repointed from the briefing+travel spike to `RESIDENT_HOT` (those five). The
  footprint IT now boots all five side-by-side. The pilot modules (briefing/travel) keep their `exec`
  packaging and await their cold **Brief+Travel** host. The **RAM delta** (one host vs five JVMs) is the
  next step — it needs the deployable host (env-wired `main`) + `measure-footprint.sh` on the running stack.
- **3d (done — resident Agent-hot set):** 3a `exec`-classifier rolled out to the six agents
  (`calendar` · `finance` · `tasks` · `notes` · `coordinator` · `researcher`); new
  `deploy/agent-host` boots them side-by-side. **The agent tier needed a second collision fixed beyond
  the `application.yml` skip:** every agent's `AGENT.md` landed at `classpath:/AGENT.md`, so co-hosted
  contexts on the shared classloader would all resolve the *same* manifest and the manifest/skills
  consistency check would throw. Fix (owner-chosen): each `AGENT.md` now lands at a per-agent
  `manifest/<name>/AGENT.md` via the module `pom.xml` `<targetPath>` (the file **stays at the module
  root**; only its classpath location changes), and the launcher points each context's
  `agent.manifest-classpath` there. Agents keep their own reactive server + ports; MCP client stays
  enabled at deploy (the IT disables it, tasks-agent's being fail-fast at boot).
- **3e (done — resident Platform-hot set):** 3a `exec`-classifier rolled out to the seven platform
  services (`gateway-telegram` · `orchestrator` · `profile-service` · `notifier-service` ·
  `scheduler-service` · `conversation-service` · `media-service`); new `deploy/platform-host` boots them
  side-by-side. **The platform tier is DB-bound and mixes web stacks, so two things beyond the
  `application.yml` config-name skip were needed:** (i) the launcher **re-supplies `web-application-type`
  per module** (reactive: gateway/orchestrator/notifier; servlet: the rest) because the skip drops each
  module's own yml declaration and `scheduler-service` carries both `spring-web` and (test-only)
  `webflux` — auto-detection would be ambiguous; (ii) the co-residency IT wires the shared Testcontainers
  PG to every context (`ddl-auto=none`) plus a live **MinIO** for media-service's boot-time
  `ensureBucket`, and keeps the two `@Scheduled` ticks quiet (`notifier.held-redrain-enabled=false`,
  far-future `scheduler.tick-millis`); gateway boots token-less (bot + inbox redriver off). The isolated
  singletons `memory-service` + `llm-gateway` are **not** consolidated (§Grouping 3). **This completes the
  resident tier** (Agent-hot 3d + Domain-MCP-hot 3c + Platform-hot 3e). Next: the **RAM delta** across the
  three resident hosts (env-wired `main` + `measure-footprint.sh` on the running stack, Mac) and the cold
  host-units (ADR-0006 item 4).
- **3f (done — first cold host-unit, the Docs unit):** 3a `exec`-classifier rolled out to `docs-agent` +
  `mcp-docs`; new `deploy/docs-host` boots them side-by-side. **This is the first host that MIXES the
  agent tier and its domain-MCP in one JVM** — the cold grouping (co-usage affinity, §Grouping 1), unlike
  the resident tier's agent/MCP split. Same launcher shape as Platform-hot (per-module `web-application-type`
  re-supply — `docs-agent` reactive, `mcp-docs` servlet), plus the agent's `agent.skills-classpath` is
  re-supplied (the config-name skip drops it and the runtime fails startup if `AGENT.md` declares skills the
  empty registry never loaded). Only one `AGENT.md` on the classpath (docs-agent's), so the 3d per-agent
  manifest-path fix is not needed here. `docs-agent`'s MCP client is disabled in the IT (co-residency proof,
  not the agent→MCP SSE binding). **Remaining cold units** (Content, Lifestyle, Brief+Travel, Finance-aux,
  Coach) follow the same mechanism, per-unit lists.
- **3g (done — second cold host-unit, the Content unit):** 3a `exec`-classifier rolled out to `creator-agent`
  + its domain-MCP `mcp-creator` + the three trend capability-MCPs it binds (`mcp-youtube` · `mcp-reddit` ·
  `mcp-feeds`); new `deploy/content-host` boots all five side-by-side. **Same shape as the Docs unit (3f) — a
  single agent + its MCPs — now over a larger cluster (5 JVMs → 1).** Launcher re-supplies per-module
  `web-application-type` (`creator-agent` + the three capability-MCPs webflux-only → reactive; `mcp-creator`
  carries both `spring-web` and `webflux`, like `mcp-docs`, so it is pinned servlet) **and** the agent's
  `agent.skills-classpath` (the config-name skip drops it and the runtime fails startup if `AGENT.md` declares
  skills the empty registry never loaded); single agent → no manifest-path collision. Each MCP re-supplies its
  MCP-server identity in the IT (config-name skip dropped it). `ContentHostFootprintIntegrationTest` boots all
  five side-by-side (creator-agent's MCP client off = co-residency proof, not the agent→MCP SSE bindings).
  **Remaining cold units** (Lifestyle, Brief+Travel, Finance-aux, Coach) follow the same mechanism, per-unit
  lists.
- **3h (done — third cold host-unit, the Lifestyle unit; first cold unit with >1 agent):** 3a
  `exec`-classifier rolled out to three agents (`stylist` · `nutritionist` · `chef`) + their domain-MCPs
  (`mcp-wardrobe` · `mcp-nutrition`) + the two capability-MCPs they bind (`mcp-image-gen` · `mcp-food-data`);
  new `deploy/lifestyle-host` boots all seven side-by-side (7 JVMs → 1). **Because three agent contexts share
  the classloader, this unit carries the resident tier's per-agent manifest-path fix (#584 3d):** each
  agent's `AGENT.md` now lands at `manifest/<name>/AGENT.md` via the module `pom.xml` `<targetPath>` (file
  stays at module root) + each `application.yml` `manifest-classpath` repointed, and the launcher points each
  context there so it resolves its own persona (skills classpath stays shared — nutritionist + chef both scan
  `skills/nutrition/*`). Launcher re-supplies per-module `web-application-type` (three agents + two
  capability-MCPs webflux-only → reactive; `mcp-wardrobe` + `mcp-nutrition` carry both `spring-web`+`webflux`
  like `mcp-docs`/`mcp-creator` → pinned servlet) + each agent's `agent.skills-classpath`.
  `LifestyleHostFootprintIntegrationTest` boots all seven, asserting each agent loaded its own persona (agents'
  MCP clients off = co-residency proof). **Remaining cold units** (Brief+Travel, Finance-aux, Coach) follow
  the same mechanism, per-unit lists.
- **3i (done — fourth cold host-unit, the Brief+Travel unit; multi-agent):** the two agents
  (`briefing` · `travel`) + their domain-MCPs (`mcp-briefing` · `mcp-travel`) + the two capability-MCPs they
  bind (`mcp-weather` · `mcp-travel-search`); new `deploy/brief-travel-host` boots all six side-by-side
  (6 JVMs → 1). `mcp-briefing` + `mcp-travel` already carried the `exec`-classifier from the **3a pilot**; 3i
  adds it to the other four (both agents + the two capability-MCPs). Multi-agent → carries the per-agent
  manifest-path fix (3d) exactly as Lifestyle (3h): each agent's `AGENT.md` → `manifest/<name>/AGENT.md` (pom
  `<targetPath>` + main & test yml `manifest-classpath` repointed). Web-type re-supply: agents +
  capability-MCPs reactive; `mcp-briefing` + `mcp-travel` carry both `spring-web`+`webflux` → pinned servlet.
  `BriefTravelHostFootprintIntegrationTest` boots all six, asserting each agent loaded its own persona.
  **Remaining cold units** (Finance-aux, Coach) follow the same mechanism, per-unit lists.

- Scenario: a co-hosted module is built → its main artifact is a plain classes jar (no `BOOT-INF`)
  and an `-exec` executable jar is attached alongside (not yet asserted — build-time packaging property, no runtime test; verified by the reactor build).
- Scenario: the host boots the five resident Domain-MCP-hot contexts in one JVM → all five are live on
  distinct ports, each with only its own module's application bean (asserted by `DomainMcpHostFootprintIntegrationTest`).
- Scenario: the host boots the six resident Agent-hot contexts in one JVM → all six are live on distinct
  ports, each with only its own application bean **and its own AGENT.md persona** (proving both the
  `application.yml` skip and the per-agent manifest-path fix) (asserted by `AgentHostFootprintIntegrationTest`).
- Scenario: the host boots the seven resident Platform-hot contexts in one JVM → all seven are live on
  distinct ports, each context carrying only its own application bean, with mixed reactive/servlet web
  stacks side-by-side and media-service's boot-time bucket-ensure satisfied by a live MinIO (asserted by
  `PlatformHostFootprintIntegrationTest`).
- Scenario: the first cold host-unit boots an agent and its domain-MCP in one JVM → `docs-agent` +
  `mcp-docs` are both live on distinct ports, each context carrying only its own application bean, proving
  the agent+MCP co-hosting the cold grouping needs (asserted by `DocsHostFootprintIntegrationTest`).
- Scenario: the second cold host-unit boots an agent and its four MCPs in one JVM → `creator-agent` +
  `mcp-creator` + `mcp-youtube` + `mcp-reddit` + `mcp-feeds` are all live on distinct ports, each context
  carrying only its own application bean, proving the agent+MCP co-hosting scales past the two-context Docs
  pilot to a five-context cluster (asserted by `ContentHostFootprintIntegrationTest`).
- Scenario: the third cold host-unit boots three agents and their four MCPs in one JVM → `stylist-agent` +
  `nutritionist-agent` + `chef-agent` + `mcp-wardrobe` + `mcp-image-gen` + `mcp-nutrition` + `mcp-food-data`
  are all live on distinct ports, each context carrying only its own application bean **and each agent its
  own AGENT.md persona** (proving the per-agent manifest-path fix on the first multi-agent cold unit)
  (asserted by `LifestyleHostFootprintIntegrationTest`).
- Scenario: the fourth cold host-unit boots two agents and their four MCPs in one JVM → `briefing-agent` +
  `travel-agent` + `mcp-briefing` + `mcp-travel` + `mcp-weather` + `mcp-travel-search` are all live on
  distinct ports, each context carrying only its own application bean **and each agent its own AGENT.md
  persona** (asserted by `BriefTravelHostFootprintIntegrationTest`).

## Boundaries (from ADR-0006)
- Domain logic is never rewritten; domain-MCPs keep their schemas + contracts.
- Goldens / E2E must pass against the consolidated (and later native) artifacts, not only the dev topology
  — the guardrail that keeps correctness intact through the move.
- Hardware-gated: real RSS and native builds need the Mac; this design + the slice-1 harness are authored
  now, executed at deploy.
