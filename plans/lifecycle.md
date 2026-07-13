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
- `POST /v1/model-profile {profile: normal|coder-active}` → `normal` = 32B, `coder-active` = 14B
  (the downshift model, a new `LLM_DEFAULT_MODEL_DOWNSHIFT` env).
- **Clean swap (critical on 64 GB):** on switch, gateway explicitly unloads the outgoing model from
  Ollama (`keep_alive:0` / `ollama stop`) so two 32B never sit resident during the transition.
- **Signal source:** the coder project flips the profile on its own start/stop (one HTTP call). ai-life
  knows nothing about the coder — it only changes its own chat model on the flag.

## Flow (cold agent, e.g. "что приготовить из того что есть" → chef)
1. orchestrator classifies → `chef` (cold in the registry: `{hot:false, container:chef-agent}`).
2. orchestrator calls `supervisor.ensure(chef-agent)`.
3. supervisor `compose up -d chef-agent` (compose pulls its MCP deps); waits health.
4. health green → orchestrator routes to `/agents/chef/intent` → agent replies.
5. after `SUPERVISOR_IDLE_TTL_MINUTES` idle the reaper stops `chef-agent` + its unique cold deps.

**Cold-start latency (Decision 2 — instant, no interim message):** JVM cold start (~8–20 s) is cut to
~2–3 s via **CDS/AOT** (Spring Boot 4), so no "поднимаю…" placeholder is needed. CDS/AOT therefore lands
**before/with** the orchestrator lazy-activation slice, not as a later optimisation.

## Hot/cold set — via compose profiles
- **`profiles: [hot]`** (always-on): platform (gateway-telegram, llm-gateway, orchestrator, profile,
  notifier, scheduler, conversation, memory, media) + **calendar, finance, tasks, search (mcp-web +
  researcher + searxng)** + Postgres, radicale, minio.
- **`profiles: [cold]`** (on demand): everything else — chef, stylist, nutritionist, docs, briefing,
  coach, **creator (Decision 4)**, market-data, image-gen, weather, youtube/reddit/feeds, chart-render,
  ics-import, money-pro-import, whisper, grafana.
- Boot the always-on system: `docker compose --profile hot up -d`. Cold services are started by name on
  demand (compose starts a named service even if its profile isn't active).

## Decisions (owner-signed 2026-07-10)
1. **Docker access via socket-proxy**, not a raw socket mount.
2. **Instant cold-start** — fold CDS/AOT into the MVP; no interim "поднимаю…" message.
3. **Idle TTL in env** — `SUPERVISOR_IDLE_TTL_MINUTES`, default 15.
4. **Creator is cold.**
5. **`deploy-mvp` first** — get the stack live on the Mac, then build the lifecycle layer there.

## Feasibility — verified against the codebase (2026-07-10)
Audited before committing to the plan. Verdict: **feasible, with one prerequisite fix.**
- ✅ **Supervisor = `docker compose up -d <svc>` works.** Cold agents declare `depends_on: {…: service_healthy}`
  on their MCPs (checked `chef-agent` → `mcp-nutrition`+`mcp-web`), so `up -d <agent>` brings the whole
  subtree and waits for health; already-healthy hot deps are not restarted.
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
- **LC-1 — compose hot/cold profiles.** Tag every service `hot`/`cold`; `--profile hot up` boots the
  always-on set. No code.
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
  - **LC-3a — CDS/AOT fast cold-start** for the agent modules (Spring Boot 4 AOT + CDS archive in the
    Dockerfile). Lands before/with LC-3.
- **LC-4 — model-manager in llm-gateway.** Runtime default-model override + `/v1/model-profile` + clean
  unload; coder start/stop hooks flip the profile.
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
