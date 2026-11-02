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
Enumerated from `infra/docker-compose.yml` (the runtime SSOT). **50 Spring Boot JVMs** (more than
ADR-0006's "~30+" estimate — reinforces the footprint concern), plus non-JVM backing that is **out of
scope** (it carries no JVM baseline). This table is the **count's SSOT**; the ADRs / `INDEX.md` / `STATUS.md`
restate it and must move with it (it grew 47 → 50 when the inventory domain + `mcp-media-fetch` landed).

| Tier | Count | Members |
|---|---|---|
| Agents (LLM + MCP reasoners) | 15 | briefing · calendar · chef · coach · coordinator · creator · docs · finance · inventory · notes · nutritionist · researcher · stylist · tasks · travel |
| Domain-MCP (own a schema) | 13 | mcp-briefing · mcp-caldav · mcp-coach · mcp-creator · mcp-docs · mcp-finance · mcp-ics-import · mcp-inventory · mcp-money-pro-import · mcp-nutrition · mcp-tasks · mcp-travel · mcp-wardrobe |
| Capability-MCP (schema-less) | 12 | mcp-chart-render · mcp-feeds · mcp-food-data · mcp-image-gen · mcp-market-data · mcp-media-fetch · mcp-media-processing · mcp-reddit · mcp-travel-search · mcp-weather · mcp-web · mcp-youtube |
| Platform (Java services) | 10 | calendar-web · conversation-service · gateway-telegram · llm-gateway · media-service · memory-service · notifier-service · orchestrator · profile-service · scheduler-service |
| **Total JVM** | **50** | |
| Non-JVM backing (out of scope) | — | postgres (+backup) · radicale · seaweedfs · searxng · whisper · grafana · liquibase (one-shot) · rclone-offsite · tailscale sidecars |

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
   (Postgres/Radicale/SeaweedFS/SearXNG/whisper/Grafana) is out of scope entirely.
4. **Native-image (path C, ADR-0006 Option C) targets the resident set first** — always-in-memory hosts
   have the biggest payoff.

## Proposed target topology (50 JVMs → ~13 processes)

### Resident (always in memory) — 5 processes
| Host | JVMs | Members |
|---|---|---|
| **Platform-hot** | 7 → 1 | gateway-telegram · orchestrator · profile-service · notifier-service · scheduler-service · conversation-service · media-service |
| **Agent-hot** | 6 → 1 | calendar-agent · finance-agent · tasks-agent · notes-agent · coordinator-agent · researcher-agent |
| **Domain-MCP-hot** | 6 → 1 | mcp-caldav · mcp-finance · mcp-tasks · mcp-web · mcp-media-processing · mcp-media-fetch (placement decided, wiring = slice 3l) |
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
| **Inventory** | inventory-agent · mcp-inventory (reads the hot `mcp-media-processing`) |
| **Coach** (parked) | coach-agent · mcp-coach — a cold host when the epic thaws (#289) |

`calendar-web` (+ tailscale sidecar) stays on its own opt-in `tunnel` profile — not consolidated.

**Modules that landed after this map was drawn (reconciled 2026-10-04, slice 3k).** The map was written
2026-08-28; three JVMs joined compose afterwards and had no host at all — the drift this reconcile closes:
- **`inventory-agent` + `mcp-inventory`** (the inventory domain closed 2026-09-27) → their own **Inventory**
  cold unit, the Docs shape (one agent + its own domain-MCP). "Где что лежит" is an occasional ask, so cold
  is the right tier; built in slice **3k**.
- **`mcp-media-fetch`** (#294 video understanding, 2026-09-02) → the resident **Domain-MCP-hot** host, not a
  cold one. Its only consumer is `researcher-agent`, which is **resident**, and a pasted video link is a
  chat-latency ask — a cold wake (~8–20 s on the JVM) would sit in front of every one. It also pairs on
  every video flow with `mcp-media-processing`, already in that host, so co-usage affinity agrees. Note for
  the rollout: that host's image must then carry **`yt-dlp` + `ffmpeg`** (it carries ffmpeg already for
  `mcp-media-processing`). Wiring = slice **3l**; this map records only the placement. **Confirmed
  owner-signed 2026-10-07:** its compose profile is now `hot` too — it had been `cold` while the *hot*
  `researcher-agent` hard-`depends_on`'d it, which made `--profile hot` an invalid project
  ([lifecycle.md](lifecycle.md) §Hot/cold, now guarded by `check-consistency.sh` check 13).

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
| **Cold hosts** (Content · Lifestyle · Brief+Travel · Docs · Finance-aux · Inventory) | **native optional — for latency, not RAM** | idle = stopped = 0 RAM, so no footprint gain; native's payoff here is instant wake (~50–100 ms vs ~8–20 s). Worth it where instant-start matters and the build is cheap; else **CDS/AOT** |
| **Native-hostile modules** (heavy reflection / dynamic classloading not covered by Spring Boot 4 AOT hints) | **stay JVM + CDS/AOT** | hint-chasing cost exceeds the benefit |
| **One-shot jobs** (`liquibase`) | **plain JVM** | runs at startup then exits — native pointless |
| **Non-JVM backing** (Postgres · Radicale · SeaweedFS · SearXNG · whisper · Grafana) | **not applicable** | not our code, not a JVM |

Two guardrails keep this from over-investing:
- **B first makes C tractable.** Consolidation collapses 50 JVMs → ~13 hosts, so the native decision is
  made over ~13 targets, not 50 — and only ~5 resident ones are native-compiled for footprint.
- **Measurement-first (slice 1).** If the real numbers show 64 GB is already ample after B alone, **C may
  be optional entirely** — or reserved for the few worst offenders. ADR-0006 does not commit to
  "everything native" up front.

## Open questions → deferred to measurement (slices 1/3, Mac)
- **Cold granularity** — 6 cold hosts vs finer. Native start (~50–100 ms) makes coarse grouping cheap, so
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
(not a container), read from **`ollama ps`** (the resident size, which includes unified GPU memory) with
`ps` RSS as the fallback. Cold hosts that are stopped simply don't appear — the snapshot reflects
whatever is actually resident, which is the number that matters.

**First run on the Mac, 2026-11-02 — it took two fixes to produce a usable number** (the harness was
authored on the VDI, so neither defect could surface there):
1. **It did not run at all.** The name→image map used `declare -A`; macOS ships **bash 3.2**, which has no
   associative arrays, and no newer bash is installed or declared in the `Brewfile`. Replaced with a text
   map + an `awk` lookup — no new dependency.
2. **The model number was wrong by ~17×, which inverted the premise check.** Under the MLX runner the
   weights sit in unified memory that `ps` does not attribute to the process: a resident **44 GB** model
   reported **~2.5 GB** of RSS, so the summary read "JVM 11 GB vs model 2.5 GB" when the truth is 11 vs 44.
   Now read from `ollama ps`; the `model` line names its source and `split.model_src` records it in `--json`
   so before/after diffs stay comparable.

Baseline captured the same day (all 19 resident JVMs up, model loaded): **JVM apps 10039 MB / 19 · JVM
singletons 1048 MB / 2 · Postgres 166 MB · model 45056 MB · backing 1019 MB / 5 · total 57328 MB** of the
box's 64 GB — which confirms the ADR's ~10 GB-of-overhead premise and is the "before" for slice 3's delta.

These four are verified by that run but stay `not yet asserted`: `measure-footprint.sh` is a shell harness
and the repo has no shell test lane, so nothing in CI can fail if they regress.

- Scenario: measuring a running stack → a per-process table (name · tier · RSS, sorted desc) **and** a
  split summary (JVM total + count · Postgres · model · backing · grand total) (not yet asserted — no shell test lane).
- Scenario: the local model runs as a host Ollama process → its resident size is captured separately from
  the containers and reported in the `model` line, naming the source it came from (not yet asserted — no shell test lane).
- Scenario: `--json` → the same numbers as one machine-readable object, so slice 3 can diff before/after
  consolidation without re-parsing the table (not yet asserted — no shell test lane).
- Scenario: the stack (or Docker) is down → the script reports only what is running and exits 0 (a
  measurement tool never fails a pipeline), naming what it could not reach (not yet asserted — no shell test lane).

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
  PG to every context (`ddl-auto=none`) plus a live **SeaweedFS** for media-service's boot-time
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
- **3j (done — fifth cold host-unit, the Finance-aux unit; first MCP-only cold unit):** 3a
  `exec`-classifier rolled out to the four aux MCPs — two schema-less capability-MCPs (`mcp-market-data` ·
  `mcp-chart-render`) + two import domain-MCPs (`mcp-money-pro-import` · `mcp-ics-import`); new
  `deploy/finance-aux-host` boots all four side-by-side (4 JVMs → 1). **This is the first cold unit with no
  agent** — an MCP-only cluster grouped by co-usage affinity (§Grouping 1: seldom-used finance/calendar
  helpers that wake together). So, like the resident Domain-MCP-hot host (3c), it needs neither the
  per-agent manifest-path fix (3d) nor the `agent.skills-classpath` re-supply — only the `application.yml`
  config-name skip and the web-type pin. Web-type re-supply: `mcp-market-data` + `mcp-chart-render` are
  webflux-only → reactive; `mcp-money-pro-import` + `mcp-ics-import` carry both `spring-web`+`webflux` (like
  `mcp-creator`) → pinned servlet. The two import MCPs are DB-bound, so the IT wires the shared
  Testcontainers PG with `ddl-auto=none` (no schema needed to boot the web + MCP servers).
  `FinanceAuxHostFootprintIntegrationTest` boots all four, each on its own port, each carrying only its own
  application bean. **Remaining cold unit:** Coach (parked #289) — same mechanism, per-unit list.
- **3k (done — sixth cold host-unit, the Inventory unit; the late-arrival reconcile):** the inventory domain
  closed 2026-09-27, *after* this map was drawn, so it was the one domain with **no host at all**. New
  `deploy/inventory-host` boots `inventory-agent` + `mcp-inventory` side-by-side (2 JVMs → 1) — the **Docs
  shape** (3f): one agent plus its own domain-MCP, which binds the *hot* `mcp-media-processing` (vision
  caption) without co-hosting it. Both modules were built after the 3a enabler, so they **already carried
  the `exec` classifier** — this slice is the launcher only, no packaging change. Launcher needs the
  `application.yml` config-name skip + web-type re-supply (`inventory-agent` reactive; `mcp-inventory`
  carries `spring-web`+`webflux` → pinned servlet) + the agent's `agent.skills-classpath`; single agent → no
  manifest-path collision (3d not needed). `mcp-inventory` is DB-bound → the IT wires the shared
  Testcontainers PG at `ddl-auto=none`. The same reconcile placed the third late arrival,
  **`mcp-media-fetch`**, into the resident Domain-MCP-hot host (§Proposed target topology — rationale there;
  wiring = **3l**), and corrected the process inventory 47 → **50 JVMs**. **Remaining cold unit:** Coach
  (parked #289).

- **3n (in flight — per-module config location; replaces the `spring.config.name` skip):** slices 3b…3k
  booted each co-hosted context with a unique, **non-existent** `spring.config.name` so the modules'
  `application.yml` resources (all at `classpath:/application.yml`) could not collide. That solved the
  collision by **discarding every module's own configuration** — which is fine for an IT that passes
  properties explicitly, but makes the deployable `main` impossible: the yml is where each module's
  `env → property` mapping lives (`MCP_CALDAV_PORT`, `MCP_CALDAV_DB_URL`, `SCHEDULER_URL`, the OCR/STT
  engine picks …) **and** where `spring.ai.mcp.server.instructions` lives — the tool-surface prose the MCP
  server hands its clients. Hand-wiring those ~40 properties into the launcher would copy a contract into
  Java and guarantee drift (the IT already drops `instructions` silently).
  **Mechanism (owner-chosen 2026-11-02), the slice-3d `AGENT.md` precedent applied to `application.yml`:**
  each co-hosted module's pom copies its own `application.yml` to a **per-module classpath path**
  (`config/<module>/application.yml`) alongside the default one — one source file, two classpath
  locations, so a standalone `-exec.jar` still resolves `classpath:/application.yml` unchanged — and the
  launcher boots each context with `spring.config.location=classpath:/config/<module>/`. Each context then
  reads its **real** configuration: env substitution works as it always did and `instructions` survive. The
  `spring.config.name` hack is removed, not built upon. Piloted here on `domain-mcp-host`; the same change
  for `agent-host` + `platform-host` and the deployable `main`/Dockerfile/compose follow as separate slices.

- **3o (in flight — the deployable host artifact):** the host gets a runnable jar (its pom repackages
  with an explicit `mainClass`, since the launcher is a plain class that boots several contexts, not a
  `@SpringBootApplication`) and a Dockerfile carrying the **union** of its members' native dependencies
  (today only `mcp-media-processing`'s tesseract + ffmpeg; slice 3l adds `yt-dlp`).
  **The first real deploy of it failed, and the failure is the slice's main lesson.** Three contexts came
  up on their own ports and the fourth died with *"Failed to determine a suitable driver class"* — while
  the IT was green. Cause: **co-hosting merges five classpaths into one**, so the schema-less capability
  MCPs (`mcp-web`, `mcp-media-processing`) inherit their schema-bound siblings' JPA starters and Boot
  auto-configures a `DataSource` they have no URL for. Standalone this cannot happen — they have no JPA
  on their classpath at all. The IT could not see it because it handed the Testcontainers datasource to
  **every** context, capability MCPs included.
  Fixes: (i) `Hosted` now records each member's `Persistence` (`SCHEMA_BOUND` / `SCHEMA_LESS`) and a
  schema-less member is booted with the JPA/DataSource auto-configurations excluded; (ii) the IT gives a
  datasource only to schema-bound members, so it now reproduces deploy — verified by reverting the fix,
  where it fails with that exact error; (iii) `boot` and `main` were collapsed onto one `launchArgs`
  helper, because the IT passing while the deploy died is precisely what two separate wiring paths buy.
  **Known limitation, not yet fixed:** the logging system initialises once per JVM, so all five contexts
  log under the first member's `spring.application.name` (`[mcp-caldav]`). Harmless to behaviour,
  actively misleading when reading a production log — it is why the deploy failure first looked like a
  caldav fault. Needs a per-context logging decision before the compose cutover.
  **Measured at deploy, 2026-11-02 (the number ADR-0006 item 3 asks for).** Host up with the five
  stopped, `scripts/measure-footprint.sh` on the running stack:

  | | JVM apps | processes |
  |---|---|---|
  | five separate JVMs (settled, 20 h up) | 2954 MB | 5 |
  | one consolidated host | **828 MB** | 1 |
  | whole stack's JVM-app tier | 10039 MB → **7920 MB** | 19 → 15 |

  **−2119 MB from this one host — a 3.6× cut on its tier**, and better than this doc's own projection
  (which assumed ~300 MB per JVM baseline; the real per-module cost once co-hosted is ~166 MB). Caveat:
  the host had ~5 minutes of uptime against the five services' 20 hours, so its number will drift up with
  use — `-Xmx2560m` caps where it can go. Snapshots: `logs/footprint-{baseline,3o-consolidated}-2026-11-02.json`.

  **What that implies for the goal.** If Agent-hot (2787 MB / 6 JVMs) and Platform-hot (3844 MB / 7 JVMs)
  collapse in the same proportion, the resident tier goes ~9.6 GB → ~2.7 GB and the whole container stack
  ~12.0 GB → ~5.0 GB. So **Path B alone does not reach the 2–3 GB target** the owner is after — it gets
  two thirds of the way. Path C (native) on the three resident hosts is what closes the rest, which is
  exactly the B-then-C order this ADR recommends, now with a measured basis rather than an estimate.

  **Compose cutover is deliberately NOT in this slice.** Twenty-odd agent `depends_on` edges and
  `MCP_*_URL` values name the five services; the host preserves those URLs by taking their names as
  **network aliases**, but switching over means re-pointing every edge, and a separate profile cannot
  work (compose rejects a project whose active service depends on one outside the active profiles, which
  is what `check-consistency.sh` check 13 guards). That is its own slice, gated on the number below.

- **3p (done — the compose cutover; the host is now the deployed topology):** `domain-mcp-host` replaces
  the five `mcp-*` services in `infra/docker-compose.yml`. The hot set drops **28 → 24** with no
  capability lost.
  **What made it a packaging change and not a contract change:** 14 services reach these MCPs by service
  name (`MCP_CALDAV_URL: http://mcp-caldav:8090` and friends), so the host takes all five names as
  compose **network aliases**. Verified on the running stack, not asserted: from inside
  `calendar-agent`, every one of the five old `name:port` pairs resolves to the host's IP and answers
  `HTTP 200` on `/actuator/health`.
  The 18 `depends_on` edges on the five collapse to **14** (several services depended on two of them).
  A separate compose profile was tried first and cannot work: compose rejects a project whose active
  service depends on one outside the active profiles — the failure mode check 13 guards — so this is a
  replacement, not a parallel option. Rollback is `git revert`: the five module Dockerfiles and their
  `-exec` jars are untouched, so the separate services still build and run.
  **Two traps worth recording.** (i) The healthcheck probes **all five** ports, because 3o's deploy came
  up with three contexts live and a single-port probe would have reported that healthy, letting every
  dependent start against a host missing two MCPs. (ii) The `$p` loop variable in that healthcheck needs
  `$$p` in compose, or compose interpolates it away and the probe silently checks `/dev/tcp/127.0.0.1/`
  with no port.
  **New lint, check 18 (`host-alias-closure`):** the alias list and the launcher's `RESIDENT_HOT` are one
  coupling in two files that nothing else reads together — add a member without its alias and that MCP is
  unreachable fleet-wide while every healthcheck stays green. Verified it fails on a removed alias.
  Measured after cutover: **JVM apps 7894 MB / 15 JVMs**, host at 790 MB
  (`logs/footprint-3p-cutover-2026-11-02.json`).

- **3q (done — per-module config on the Agent-hot host; the launcher stops duplicating the modules):**
  the 3n mechanism rolled out to `agent-host`. Each agent's pom lands a copy of its own
  `application.yml` at `config/<name>/` and the launcher boots each context with
  `spring.config.location` there.
  **This tier was the clean case, and the reason is worth recording.** The `spring.config.name` skip had
  forced `AgentHost` to re-supply, in Java, everything the skipped yml would have said — the reactive
  web stack, the per-agent `manifest/<name>/AGENT.md` path, and **each agent's skills glob**. All three
  were already declared in every agent's own yml, so the launcher held a second copy of six modules'
  configuration, `coordinator-agent`'s deliberate *empty* skills registry included (encoded as a `null`
  in Java beside a yml comment explaining the same thing). `structuralProps` and the `skillsGlob` record
  field are both **gone**; the yml is the only source.
  **No auto-configuration excludes needed here, unlike 3o.** All six agents are schema-less, so there is
  no JPA on this host's classpath to inherit — what hurt the Domain-MCP-hot host was *mixing*
  schema-bound and schema-less members, not consolidating as such. Adding a DB-bound member to this host
  would reintroduce the question.
  `AgentHostConfigLocationTest` guards the six-pom coupling (the per-module copy exists, is that agent's
  own, and declares its manifest path and web stack), and the IT now asserts each context's own
  `spring.application.name`, its own manifest path, and that the skills glob survived the move out of
  Java — empty for the coordinator, non-blank for the rest.
  **Trap found while proving the guard has teeth:** removing a pom's resource block did **not** fail the
  test until `mvn clean` — Maven leaves the already-copied resource in `target/classes`, so a local
  green after such an edit is meaningless. CI is a fresh checkout and sees it.

- **3r (done — Agent-hot deployable + cutover, and the defect it exposed in 3o/3p):** `agent-host` gets
  a runnable jar and a Dockerfile (plain JRE — no agent has a native dependency) and replaces the six
  agent services in compose. Hot set **24 → 19**. Nothing in compose `depends_on` an agent, so unlike 3p
  this moved **no** dependency edges; only the orchestrator's six `*_AGENT_URL` values matter, and the
  old service names are preserved as network aliases. Measured: the six agents **2799 MB → 549 MB** in
  one JVM (**5.1×**, the best ratio of the three tiers), whole JVM-app tier **10039 → 5689 MB**, 19 → 10
  JVMs, 27 → 18 containers (`logs/footprint-3r-agent-host-2026-11-02.json`).
  **⚠ This slice exposed a defect that made 3o/3p's central claim false.** `agent-host` would not start:
  `researcher-agent`'s MCP client timed out creating `mcpSyncClients`. The cause was not in agent-host at
  all — **every MCP server inside `domain-mcp-host` was answering `/sse` with 404 while
  `/actuator/health` stayed 200.** So the consolidated host had been running since 3p with its MCP
  surface dead, and "each context keeps its own MCP server, so every contract is unchanged" was wrong.
  Nothing caught it because every check — the healthcheck, the IT, my live verification — probed
  `/actuator/health` and never the MCP endpoint.
  **Root cause, confirmed by experiment rather than inference.** The first hypothesis (web stack) was
  only half of it: all five contexts had booted on Tomcat, because the merged classpath carries
  `spring-boot-starter-web` from the three servlet members and Boot then picks SERVLET for everyone. But
  a standalone `mcp-caldav` run from its own image answers `/sse` **200 on that same servlet stack**, so
  the stack alone could not explain it. The real cause is that **both MCP transport starters are on the
  merged classpath** (`spring-ai-starter-mcp-server-webmvc` from caldav/finance/tasks,
  `…-webflux` from web/media-processing); they collide, one wins, and every context whose stack does not
  match it mounts nothing.
  Fix: each member now declares its `webType`, is booted with `spring.main.web-application-type`, and has
  the **other** stack's three transport auto-configurations excluded — the same per-member-exclusion
  shape `Persistence` already uses. Verified live: `/sse` is 200 on all five through their aliases
  (webmvc members answer `200`, webflux `200 OK` — visibly different stacks), and `agent-host` then came
  up healthy on its own.
  **`check-consistency.sh` check 17 describes this exact failure** ("/sse answers 404 while health stays
  green") but checks each module's pom, not a host's merged classpath, so it could not see it. The guard
  now lives in the IT instead: it asserts `/sse` is not 404 for every context. Cost: the IT went 7 s →
  103 s, since each probe holds an SSE connection open.

- Scenario: a co-hosted module is built → its main artifact is a plain classes jar (no `BOOT-INF`)
  and an `-exec` executable jar is attached alongside (not yet asserted — build-time packaging property, no runtime test; verified by the reactor build).
- Scenario: the host boots the five resident Domain-MCP-hot contexts in one JVM → all five are live on
  distinct ports, each with only its own module's application bean (asserted by `DomainMcpHostFootprintIntegrationTest`).
- Scenario: the host boots the six resident Agent-hot contexts in one JVM → all six are live on distinct
  ports, each with only its own application bean **and its own AGENT.md persona** (proving both the
  `application.yml` skip and the per-agent manifest-path fix) (asserted by `AgentHostFootprintIntegrationTest`).
- Scenario: the host boots the seven resident Platform-hot contexts in one JVM → all seven are live on
  distinct ports, each context carrying only its own application bean, with mixed reactive/servlet web
  stacks side-by-side and media-service's boot-time bucket-ensure satisfied by a live SeaweedFS (asserted by
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
- Scenario: the fifth cold host-unit boots four aux MCPs (no agent) in one JVM → `mcp-market-data` +
  `mcp-chart-render` + `mcp-money-pro-import` + `mcp-ics-import` are all live on distinct ports, each
  context carrying only its own application bean, with mixed reactive/servlet web stacks side-by-side and
  the two DB-bound import MCPs booted schema-less (asserted by `FinanceAuxHostFootprintIntegrationTest`).
- Scenario: the sixth cold host-unit boots an agent and its domain-MCP in one JVM → `inventory-agent` +
  `mcp-inventory` are both live on distinct ports, each context carrying only its own application bean, so
  the domain that shipped after the map was drawn is consolidated like every other (asserted by
  `InventoryHostFootprintIntegrationTest`).

- Scenario: a co-hosted context boots under the per-module config location → it loads **its own**
  module's `application.yml`, so `spring.application.name` is that module's and
  `spring.ai.mcp.server.instructions` is non-empty (the tool-surface prose the `spring.config.name` skip
  silently dropped) (asserted by `DomainMcpHostFootprintIntegrationTest`).
- Scenario: the per-module yml copy the host reads declares that module's **production** port
  (8090 · 8092 · 8095 · 8098 · 8097), so consolidation needs no port re-wiring and agents' `/internal/*`
  URLs stay valid (asserted by `DomainMcpHostConfigLocationTest`). The co-residency IT overrides the port
  to ephemeral so it cannot collide with a locally running stack or a sibling host IT under CI's `-T2`
  (asserted by `DomainMcpHostFootprintIntegrationTest`).
- Scenario: a co-hosted module is packaged → its `application.yml` is present at **both**
  `classpath:/application.yml` (so the standalone `-exec.jar` is unaffected) and
  `classpath:/config/<module>/application.yml` (so the host can point at it), from the one source file
  (asserted by `DomainMcpHostConfigLocationTest`).
- Scenario: a co-hosted module's pom loses the per-module resource block → the packaging test fails in CI
  rather than the host silently falling back to a sibling's configuration at deploy (asserted by
  `DomainMcpHostConfigLocationTest`).

- Scenario: a schema-less member is co-hosted with schema-bound siblings → it carries **no**
  `DataSource` bean, although their JPA starters are on the shared classpath (asserted by
  `DomainMcpHostFootprintIntegrationTest`).
- Scenario: a schema-bound member is co-hosted → it still gets JPA and its own datasource from its own
  yml (asserted by `DomainMcpHostFootprintIntegrationTest`).
- Scenario: the host is packaged → its main artifact is an executable jar whose `Start-Class` is the
  launcher, so one container runs all five contexts (not yet asserted — asserted by hand at deploy;
  a packaging assertion needs the repackaged jar, which `mvn test` does not have).

- Scenario: a service addresses a co-hosted MCP by its old name → the name resolves to the host
  container and the MCP answers on its own port, so no `MCP_*_URL` changed (verified on the running stack
  2026-11-02 from inside `calendar-agent`: all five answered HTTP 200) (not yet asserted — needs a live
  stack; `check-consistency.sh` check 18 guards the alias list that makes it work).
- Scenario: the consolidated host starts with a context dead → its healthcheck fails, so no dependent
  starts against a partial host (not yet asserted — a compose-level behaviour with no test lane; the
  all-five-ports probe is the guard).
- Scenario: a member is added to the launcher without a matching compose network alias → the lint fails
  in CI rather than that MCP becoming unreachable fleet-wide at deploy (asserted by
  `check-consistency.sh` check 18, verified by removing an alias).

- Scenario: a co-hosted agent boots under the per-module config location → it reads its own yml, so
  its `spring.application.name`, its own `manifest/<name>/AGENT.md` persona and its own skills glob all
  come from the module and not from the launcher (asserted by `AgentHostFootprintIntegrationTest`).
- Scenario: `coordinator-agent` is co-hosted → its skills registry stays empty, because its own yml
  leaves the glob unset (the anti-cross-leak default the launcher used to duplicate as a `null`)
  (asserted by `AgentHostFootprintIntegrationTest`).
- Scenario: an agent's pom loses its per-module resource block → the packaging test fails on a clean
  build rather than that context booting a sibling's persona at deploy (asserted by
  `AgentHostConfigLocationTest`).

- Scenario: a co-hosted MCP module is reached on its own port → its **MCP endpoint** is mounted, not
  merely `/actuator/health`; a 404 on `/sse` with a green health is the co-hosting failure (asserted by
  `DomainMcpHostFootprintIntegrationTest`).
- Scenario: a host mixes webmvc-MCP and webflux-MCP members → each context boots on its own web stack
  with the other stack's transport auto-configurations excluded, so exactly one transport matches
  (asserted by `DomainMcpHostFootprintIntegrationTest`).
- Scenario: the orchestrator addresses a co-hosted agent by its old name → the name resolves to
  `agent-host` and the agent answers on its own port, so no `*_AGENT_URL` changed (verified live
  2026-11-02, all six answered 200) (not yet asserted — needs a live stack; check 18 guards the alias
  list that makes it work).

## Boundaries (from ADR-0006)
- Domain logic is never rewritten; domain-MCPs keep their schemas + contracts.
- Goldens / E2E must pass against the consolidated (and later native) artifacts, not only the dev topology
  — the guardrail that keeps correctness intact through the move.
- Hardware-gated: real RSS and native builds need the Mac; this design + the slice-1 harness are authored
  now, executed at deploy.
