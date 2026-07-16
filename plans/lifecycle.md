# Lifecycle & Mac deployment — design (owner-signed 2026-07-10)

**Status: SIGNED, implementation not started.** Target machine: **Apple Mac Studio M4 Max (16 CPU /
40 GPU), 64 GB unified / 512 GB**, running ai-life **24/7**. Goal: keep a small **hot** set of services
always-on and start the rest **cold** on demand (auto, never manual), so a single 64 GB box hosts both
ai-life and an occasional heavy second tenant (the separate coding-agent project) without holding two
large models resident at once.

This file is the agreed design. Owner signed decisions 1–5 (below) on 2026-07-10. Implementation is
phased in §Slices; the **`deploy-mvp`** slice comes first (nothing here matters until the stack boots on
the Mac). New architectural layer = **`supervisor`** (flagged per CLAUDE.md before any code).

## Why (sizing, the load-bearing constraint)
Unified memory is shared CPU+GPU; macOS caps GPU working set at ~75% of RAM → **~48 GB for models on a
64 GB box**. The whole design exists to stay under that with margin:
- All 43 JVMs hot ≈ 15 GB; hot set only (~18 services) ≈ 6 GB — hence hot/cold.
- Model plan (never two 32B resident): normal = ai-life **32B**; coder active = ai-life **14B** + coder
  **32B-code** ≈ 33 GB of models, comfortably under the 48 GB GPU ceiling.
- Peak (coder on) ≈ OS 5 + hot JVM 6 + backing 4 + models 33 ≈ **~50 GB** → fits 64 GB with ~14 GB spare.
- **64 GB is sufficient for this exact design**; 128 GB is only needed to hold two 32B resident (no
  downshift) or run 70B-class models. Because 64 GB has modest margin, the supervisor + clean model swap
  are **load-bearing** — they must be robust.

## Two independent mechanisms (different owners, different cadence)

### A. Container lifecycle — `platform/supervisor` (NEW service)
The only component with host Docker access. Brings cold services up on demand and reaps idle ones.
- **Docker access via socket-proxy (Decision 1):** never a raw `/var/run/docker.sock` mount (root-
  equivalent). A `tecnativa/docker-socket-proxy` sidecar exposes only `start`/`stop`/`inspect` to the
  supervisor.
- **Reuses compose's own dependency graph:** `ensure(service)` = `docker compose up -d <service>` —
  compose starts the service *and its `depends_on` chain* (e.g. `stylist-agent` → `mcp-wardrobe` +
  `mcp-media-processing`), skipping already-running ones. The supervisor does **not** re-derive a
  dependency graph; it wraps `up -d` + a health wait.
- **API:** `POST /v1/ensure {service}` → start + wait health → `{ready, ms}` (idempotent);
  `POST /v1/release {service}` → mark idle; `GET /v1/lifecycle/status` → what's up + resident model.
- **Reaper:** background loop stops cold services idle > `SUPERVISOR_IDLE_TTL_MINUTES` (Decision 3,
  default **15**).
- **State lives in the supervisor's memory, rebuilt from `docker ps`** — no new DB table.

### B. Model routing — in `llm-gateway` (existing service)
Owns model selection already (`LLM_DEFAULT_MODEL`). Gains a **runtime override** so ai-life's chat model
follows a workload profile.
- `POST /v1/model-profile {profile: normal|coder-active}` → `normal` = `LLM_DEFAULT_MODEL`,
  `coder-active` = `LLM_DEFAULT_MODEL_DOWNSHIFT`.
- **Opt-in, default OFF (owner, 2026-07-16).** The whole two-tenant dance is an **add-on, not a
  base feature** — gated by `LLM_MODEL_PROFILE_ENABLED` (default `false`). Anyone running ai-life
  *without* the coder (or the coder without ai-life) must be unaffected: with the flag off the
  endpoint is a no-op/404 and the gateway just serves `LLM_DEFAULT_MODEL` forever. Neither project
  may hard-depend on the other.
- **No hardcoded tags — both profiles come from env** (`LLM_DEFAULT_MODEL` /
  `LLM_DEFAULT_MODEL_DOWNSHIFT`), per the standing "provider/model switches via env only" rule.
  Someone else's pair (or ours, later) will be different models entirely; the mechanism must not
  care which.
- **Clean swap (critical on 64 GB):** on switch, gateway explicitly unloads the outgoing model from
  Ollama (`keep_alive:0` / `ollama stop`) so two large models never sit resident during the
  transition. Proof required — see the LC-4 acceptance criterion in §Slices.
- **Signal source + ordering — evict BEFORE load, both ways (owner, 2026-07-16).** The coder flips
  the profile on its own start/stop (one HTTP call). ai-life knows nothing about the coder — it only
  changes its own chat model on the flag, so the endpoint stays generic (any workload may call it).
  **The order is load-bearing and is the opposite of the intuitive one:**
  1. **Start:** coder signals `coder-active` **before loading anything** → ai-life evicts the big
     model, *confirms* eviction, settles on the downshift tag → answers OK → **only then** the coder
     loads its coder model.
  2. **Stop / idle-timeout:** coder unloads its own model and *confirms* it is gone → signals
     `normal` → ai-life restores the big model.

  Loading the coder model first (then signalling) puts both resident at once ≈39 GB of models +
  ~15 GB of JVMs/backing/OS ≈ **54 GB** — over the ~48 GB ceiling, i.e. precisely the crash the
  downshift exists to prevent, on every session start. **Seamlessness is explicitly not required**
  (owner), so the handshake simply waits — a few seconds per session start, and the budget holds.

## Flow (cold agent, e.g. "что приготовить из того что есть" → chef)
1. orchestrator classifies → `chef` (cold in the registry: `{hot:false, container:chef-agent}`).
2. orchestrator calls `supervisor.ensure(chef-agent)`.
3. supervisor `compose up -d chef-agent` (compose pulls its MCP deps); waits health.
4. health green → orchestrator routes to `/agents/chef/intent` → agent replies.
5. after `SUPERVISOR_IDLE_TTL_MINUTES` idle the reaper stops `chef-agent` + its unique cold deps.

**Cold-start latency (Decision 2 — instant, no interim message):** JVM cold start (~8–20 s) is cut to
~2–3 s via **CDS/AOT** (Spring Boot 4), so no "поднимаю…" placeholder is needed. CDS/AOT therefore lands
**before/with** the orchestrator lazy-activation slice, not as a later optimisation.

## Hot/cold set — via compose profiles (LC-1 SHIPPED — this is the as-built list, 27 hot / 24 cold / 2 tunnel)
- **`profiles: ["hot"]`** (always-on, 27): backing (postgres, liquibase, postgres-backup, radicale,
  minio, searxng, **whisper**) + platform (gateway-telegram, llm-gateway, orchestrator, profile,
  notifier, scheduler, conversation, memory, media) + calendar (mcp-caldav + calendar-agent), finance
  (mcp-finance + finance-agent), tasks (mcp-tasks + tasks-agent), search (mcp-web + researcher-agent) +
  **mcp-media-processing** (OCR/STT for receipts & voice — passive inbound, must be ready) + **notes-agent**
  (second brain — always needed) + **coordinator-agent** (cross-domain assistant front).
- **`profiles: ["cold"]`** (on demand, 24): chef, stylist, nutritionist, docs, briefing, coach,
  **creator (Decision 4)** agents + their domain MCPs (wardrobe, nutrition, creator, briefing, docs,
  coach) + capability MCPs market-data, image-gen, weather, youtube/reddit/feeds, chart-render,
  ics-import, money-pro-import, food-data + grafana.
- **`profiles: ["tunnel"]`** (opt-in, 2): calendar-web + tailscale-calendar (shared-calendar web UI).
- Boot the always-on system: `docker compose --profile hot up -d`. **A bare `up` starts nothing** (every
  service is profiled) — pass `--profile hot` (and add `--profile cold` for a full smoke), or a service
  name. Start scripts (`scripts/start-{mac,win}.*`) now pass `--profile hot`.
- **Owner-signed refinement of the finance aux MCPs (2026-07-15).** `finance-agent` (hot) previously hard
  `depends_on: service_healthy`'d **four** aux MCPs the design wants cold. Since Compose does **not**
  auto-start a cold-profiled dependency of a hot service (verified against the docs — a hot set must be
  dependency-closed), those deps were the one closure break. Resolution: `mcp-media-processing` → hot
  (receipts/voice are passive inbound, nothing to "start on command"); `mcp-money-pro-import`,
  `mcp-market-data`, `mcp-chart-render` → cold and **removed from finance-agent's `depends_on`**. Their
  real flows reach them over HTTP `/internal/*` and soft-fail while the MCP is down; the unused MCP-SSE
  binding is silenced with `FINANCE_AGENT_MCP_CLIENT_ENABLED=false` so boot pays no dial timeout. Until
  LC-3, those three features (Money Pro import, investment quotes, report charts) need the MCP started by
  name (or degrade gracefully); LC-3 gives them true on-demand start.

## Decisions (owner-signed 2026-07-10)
1. **Docker access via socket-proxy**, not a raw socket mount.
2. **Instant cold-start** — fold CDS/AOT into the MVP; no interim "поднимаю…" message.
3. **Idle TTL in env** — `SUPERVISOR_IDLE_TTL_MINUTES`, default 15.
4. **Creator is cold.**
5. **`deploy-mvp` first** — get the stack live on the Mac, then build the lifecycle layer there.

## Feasibility — verified against the codebase (2026-07-10)
Audited before committing to the plan. Verdict: **feasible, with one prerequisite fix.**
- ⚠️ **Supervisor = `docker compose up -d <svc>` — re-verify at LC-2/LC-3.** Cold agents declare
  `depends_on: {…: service_healthy}` on their MCPs (checked `chef-agent` → `mcp-nutrition`+`mcp-web`), so
  `up -d <agent>` is meant to bring the whole subtree; already-healthy hot deps are not restarted. **But**
  the LC-1 docs check surfaced that Compose does **not** auto-start a dependency that sits in a *disabled*
  profile — it must be in the same enabled profile, started separately, or unprofiled. Whether explicitly
  **naming** the agent (`up -d chef-agent`) enables its cold MCP deps' profiles is the open question the
  supervisor's `ensure` must confirm live (no Docker daemon on the dev box to test). Fallback if it does
  not: `ensure` starts the agent **and** its cold MCP deps by explicit name (`up -d chef-agent mcp-nutrition …`),
  or passes `--profile cold`. This does not affect LC-1 (the hot set is dependency-closed by design).
- ✅ **Health endpoints** (`/actuator/health`) exist on every service for the reaper/ensure to poll.
- ✅ **Cold MCP stores lose no data** — data lives in hot Postgres; the MCP is stateless over the DB, so
  stopping/starting it is safe.
- ✅ **llm-gateway is fully env-parameterised** → mock→Ollama is pure config; the runtime model override
  (LC-4) is our own code.
- 🔴 **BLOCKER — orchestrator discovery is startup-static + liveness-coupled.** `AgentDiscovery` scrapes
  manifests **once at boot**; `LlmIntentClassifier` freezes `knownAgents`+prompt from that snapshot, and
  only agents whose manifest fetched get a `RemoteAgent`. A cold agent **down at boot is un-classifiable
  and un-dispatchable** (intent *and* wake, `/v1/agents/wake` shares the map). Lazy-activation cannot sit
  on top of this → **LC-2.5 below is a hard prerequisite for LC-3.**
- ⚠️ **Wake path** must also be hooked (ensure-before-wake), not just the intent path.
- ⚠️ **No per-JVM heap caps** in compose (43 uncapped JVMs) → a deploy-mvp TODO before all-hot.

## Slices (each = one small vertical slice / PR)
- **deploy-mvp — boot on the Mac.** Mac/Ollama env profile (`LLM_PROVIDER=openai-compatible` → host
  Ollama, real model names, Telegram + internal tokens), per-JVM heap caps, `infra/README` refresh, first
  `docker compose up` + a cross-domain smoke (cal/fin/tasks). Config only, no app code. **Prereq for all
  LC-* below.**
- **LC-1 — compose hot/cold profiles. ✅ SHIPPED 2026-07-15.** Every service tagged
  `profiles: ["hot"|"cold"]` (27/24, + 2 tunnel); `--profile hot up` boots the always-on set; start
  scripts + `infra/README` + compose header updated. finance-agent's cold-aux-MCP deps removed +
  `FINANCE_AGENT_MCP_CLIENT_ENABLED=false` in `.env.mac.example` (see §Hot/cold set refinement). Config
  only, no app code.
- **LC-2 — `platform/supervisor` + socket-proxy.** `ensure`/`release`/`status` + reaper. New module +
  README.
- **LC-2.5 — cold-tolerant agent discovery (PREREQUISITE for LC-3).** Make the orchestrator know + reach
  every configured agent while it is stopped: (1) a `RemoteAgent` per configured entry regardless of the
  manifest fetch; (2) a **durable manifest roster** — persist each manifest on a successful scrape (e.g.
  `core.agent_manifest`) + load-all-at-boot + periodic refresh — so the classifier's few-shot always lists
  cold agents; (3) the classifier reads the durable roster, not a boot-frozen snapshot. See the audit
  above + [architecture.md](architecture.md) §Orchestrator routing doctrine.
- **LC-3 — orchestrator lazy-activation.** Registry entries gain `hot`/`container`; `ensure` before
  routing to a cold agent **and before a wake dispatch**. Depends on LC-2.5 (roster) + CDS/AOT (LC-3a) for
  the instant-start UX.
  - **Finance aux MCPs → true on-demand (folded into LC-3).** LC-1 made money-pro-import / market-data /
    chart-render cold but left their features degrading while down. LC-3 must `ensure(mcp)` before the
    flow that needs it (report → chart-render, invest → market-data, CSV attachment → money-pro-import) —
    the agent-side hook, not just the orchestrator route. Once ensure-before-flow exists, re-evaluate
    dropping media-processing/whisper to cold too (ensure them on an inbound photo/voice attachment at
    the gateway/orchestrator) to reclaim their hot footprint.
  - **LC-3a — CDS/AOT fast cold-start** for the agent modules (Spring Boot 4 AOT + CDS archive in the
    Dockerfile). Lands before/with LC-3.
- **LC-4 — model-manager in llm-gateway.** Runtime default-model override + `/v1/model-profile` + clean
  unload, **opt-in via `LLM_MODEL_PROFILE_ENABLED` (default off)**, both tags from env. See §B for the
  design + the evict-before-load handshake.
  - **Owner decisions (2026-07-16):** the mechanism is an **add-on, not a base feature** — default off,
    so ai-life runs standalone unchanged and the coder is usable without ai-life; **no hardcoded model
    tags** (both profiles read env — someone else's pair will be different models); **seamlessness is
    not required**, so the handshake may simply wait.
  - **Cross-repo dependency — the coder-side hook is NOT ai-life's to build.** This slice ships the
    *endpoint*; the caller (signal on session start/stop + idle timeout, itself flag-gated) is owned by
    **coding-agent C-6**, where a session lifecycle first exists. Audit 2026-07-16 found the hook was
    orphaned — every doc on both sides said "the coder *only* signals up/down" and no slice in either
    roadmap owned it. LC-4 is **not** done-in-effect until that counterpart lands; until then the
    endpoint is inert (flag off) and the pair must not be run concurrently on the 64 GB box.
  - **Acceptance criterion — the eviction must be *proven*, not assumed (audit 2026-07-16).** With only
    ~14 GB of headroom, "unload before load" is a **correctness requirement, not an optimisation**: if the
    outgoing `qwen3:32b` is still resident when `qwen3-coder:30b` begins loading, peak momentarily needs
    ≈38 GB of models and busts the ~48 GB GPU ceiling (swap/OOM — precisely the failure the downshift
    exists to prevent). LC-4 therefore does not ship until **(1)** `/v1/model-profile` *waits for* the
    outgoing model to actually leave Ollama (`keep_alive:0` / `ollama stop`, then poll `/api/ps` until it
    is gone) before issuing the incoming load — never fire-and-forget; **(2)** a test asserts the
    **ordering** (eviction confirmed → load starts), so the guarantee lives in the suite rather than in an
    env flag; **(3)** an eviction timeout **fails loudly** instead of proceeding into an over-budget load.
    None of this exists yet (no Java references `model-profile` / downshift / unload) — this is the binding
    contract for whoever builds LC-4.
  - **Qwen3 cutover (bundled with LC-4).** Deploy models moved to Qwen3 (`qwen3:32b` / `:14b` / `:8b`,
    coder `qwen3-coder:30b`) in `.env.mac.example` + pull scripts. Qwen3 "thinks" by default, which breaks
    strict-JSON skill parsing and is ~2-3x slower on CPU. **RESOLVED (2026-07-13):** the gateway gained
    `LLM_SUPPRESS_THINKING` (sends `reasoning_effort:none`; the `/no_think` prompt tag does *not* work via
    Ollama `/v1`), set on in both `.env.mac.example` and the golden lane. The golden lane was re-validated
    on `qwen3:8b` with the flag on — routing + strict-JSON + synthesis surfaces pass unchanged (see
    `platform/llm-gateway/README.md` §Golden tests). Caveat: the flag is global (also off on DEFAULT
    synthesis); per-channel thinking control is a future enhancement if a channel wants it back.
- **LC-5 — idle-shutdown polish + observability.** Reaper tuning, `/v1/lifecycle/status` surface.

## Wiring touchpoints (for the build, not now)
Root `pom.xml` (`platform/supervisor`); `infra/docker-compose.yml` (profiles on every service +
`supervisor` + `docker-socket-proxy` blocks + per-JVM heap caps); `.env.example` / new `.env.mac.example`
(LLM Ollama profile, `SUPERVISOR_*`, `LLM_DEFAULT_MODEL_DOWNSHIFT`); orchestrator agent registry
(`hot`/`container` per entry + a `SupervisorClient`); `llm-gateway` (`/v1/model-profile`); `plans/INDEX.md`
row (added with this spec). Per PATTERNS "add a new service".
