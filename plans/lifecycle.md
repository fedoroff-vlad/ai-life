# Lifecycle & Mac deployment — design (owner-signed 2026-07-10)

**Status: SIGNED; partially implemented, then PARKED on hardware.** Shipped: `deploy-mvp` config
(`.env.mac.example`, per-JVM heap caps), **LC-1** hot/cold compose profiles (2026-07-15), **LC-4**
`/v1/model-profile` evict-before-load swap (2026-07-21). **Parked** (Mac not yet purchased, no Docker
daemon on the dev box): **LC-2** (`platform/supervisor` + socket-proxy) and everything after it, plus
`deploy-mvp`'s first real `--profile hot up`. Target machine: **Apple Mac Studio M4 Max (16 CPU /
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
- **Model plan, amended 2026-10-29 (owner): ONE universal model serves both ai-life and the coding
  agent** → [model-strategy.md](model-strategy.md) §One model for both tenants. Resident models ≈ 26 GB
  at the normal peak (default MoE 19.5 + fast 6 + embedding 0.7), with vision/whisper as brief spikes.
- Peak ≈ OS 5 + hot JVM 6 + backing 4 + models 26 ≈ **~41 GB** → fits 64 GB with ~23 GB spare, and that
  spare is **KV-cache headroom**, which is what carries concurrent sessions.
- **64 GB is sufficient for this exact design** and is the **fixed target** (bought, arriving — no larger
  config planned). The **supervisor stays load-bearing** (cold services must come up reliably); the
  **model swap no longer is** — with one resident model there is nothing to swap on the normal path.
- Superseded for the record: the original sizing assumed two tenants (ai-life 14B + coder 32B-code
  ≈ 33 GB of models, ~50 GB peak) and the margin it left is why the clean swap used to be load-bearing.

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

> **DECIDED 2026-10-29 (owner) — the downshift is demoted to a safety valve, not a requirement.**
> One universal model now serves both tenants ([model-strategy.md](model-strategy.md) §One model for
> both tenants), so the case LC-4 existed for — two different models resident at once — does not arise
> on the normal path. What this means concretely:
> - **LC-4 stays in the code.** It is shipped, its evict-before-load ordering is pinned by
>   `ModelProfileServiceTest`, and it is already **off unless `LLM_MODEL_PROFILE_ENABLED=true`**. We do
>   not delete working, tested code on the strength of a plan change.
> - **It is off the default path.** The deploy does not enable it, and no slice depends on it. Do not
>   "finish" it.
> - **Its cross-repo caller goes dormant** — `lifecycle.py` in the `coding-agent` repo. That half is
>   invisible from here (CLAUDE.md's change-propagation map says so), so the counterpart chore must be
>   recorded in *that* repo's STATUS: stop signalling the profile.
> - **Reinstate it only if** the owner reinstates a separate coder model, which in turn only happens if
>   the general default proves too weak at code on the real monorepo. That is a quality judgement to be
>   made at deploy, not a memory calculation.
> The previous note (2026-07-28) already called this "maybe optional, measure first"; the owner's
> decision settles it one step further — not "optional downshift" but "no second model".
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

## Off-host access — the model reachable from the owner's work machine (requirement, 2026-10-29)

**Requirement (owner):** the single universal model must be usable from the owner's **work machine**,
not only from the Mac itself. This arrived with the one-model decision and is the reason that decision
matters: the one model has to serve the coding agent *remotely*.

**What is already settled, so this is not a new trust model.**
[ADR-0007](adr/ADR-0007-authorization-posture.md) fixes the posture: the private single-box network is
the trust boundary, made real by loopback-bound ports (#628) with **Tailscale as the only off-host
path**, defended in depth by a shared secret on every `/internal/*` (#630). ADR-0007 already carries
one off-host consumer (the ICS calendar feed over a Tailscale Funnel), so a second one fits the posture
rather than replacing it. **The mTLS/Spring-Security trigger does *not* fire:** that trigger is
"services split across hosts on a shared untrusted network", and here every service stays on the Mac —
only a *client* dials in, over a private encrypted overlay. Option B stands.

**Shape (to confirm at deploy, not built yet).** The work machine talks to **`llm-gateway`** over
Tailscale, not to Ollama directly: the gateway is already the single entry point for every LLM call,
already speaks an OpenAI-compatible surface to its callers, and is the one place where the model tag,
channel routing and the shared-secret guard live. Exposing Ollama instead would bypass all of that.

**Open questions the owner must resolve before this is designed** (listed here so no session invents an
answer):
1. **Can Tailscale be installed on that machine at all?** It is a managed corporate VDI — no admin
   rights and client-software policy are both plausible blockers. If not, this requirement needs a
   different mechanism and this section is void.
2. **Data direction.** Using the home model for work coding sends work code to the Mac. That is an
   employer-policy question, not an ai-life security question, and it is the owner's call.
3. **New residual risk to record in ADR-0007 when this lands:** a machine the owner does not control
   would hold a credential to the household stack. The shared secret authenticates "a first-party
   caller", not *which* caller — ADR-0007 already accepts that limit for on-box callers; accepting it
   for a corporate VDI is a bigger step and deserves its own line, plus a rotation story.

**Non-goals:** no public internet exposure, no second service, no per-service identity, and no
household *data* endpoints reachable off-host — this is about the LLM channel only.

## Flow (cold agent, e.g. "что приготовить из того что есть" → chef)
1. orchestrator classifies → `chef` (cold in the registry: `{hot:false, container:chef-agent}`).
2. orchestrator calls `supervisor.ensure(chef-agent)`.
3. supervisor `compose up -d chef-agent` (compose pulls its MCP deps); waits health.
4. health green → orchestrator routes to `/agents/chef/intent` → agent replies.
5. after `SUPERVISOR_IDLE_TTL_MINUTES` idle the reaper stops `chef-agent` + its unique cold deps.

**Cold-start latency (Decision 2 — instant, no interim message):** JVM cold start (~8–20 s) is cut to
~2–3 s via **CDS/AOT** (Spring Boot 4), so no "поднимаю…" placeholder is needed. CDS/AOT therefore lands
**before/with** the orchestrator lazy-activation slice, not as a later optimisation.

## Hot/cold set — via compose profiles (LC-1 SHIPPED — this is the as-built list, 28 hot / 29 cold / 2 tunnel)
- **`profiles: ["hot"]`** (always-on, 28): backing (postgres, liquibase, postgres-backup, radicale,
  seaweedfs, searxng, **whisper**) + platform (gateway-telegram, llm-gateway, orchestrator, profile,
  notifier, scheduler, conversation, memory, media) + calendar (mcp-caldav + calendar-agent), finance
  (mcp-finance + finance-agent), tasks (mcp-tasks + tasks-agent), search (mcp-web + researcher-agent) +
  **mcp-media-processing** (OCR/STT for receipts & voice — passive inbound, must be ready) +
  **mcp-media-fetch** (video acquisition — same passive-inbound argument, see the 2026-10-07 refinement
  below) + **notes-agent** (second brain — always needed) + **coordinator-agent** (cross-domain assistant
  front).
- **`profiles: ["cold"]`** (on demand, 29): chef, stylist, nutritionist, docs, briefing, coach, travel,
  inventory, **creator (Decision 4)** agents + their domain MCPs (wardrobe, nutrition, creator, briefing,
  docs, coach, travel, inventory) + capability MCPs market-data, image-gen, weather,
  youtube/reddit/feeds, chart-render, ics-import, money-pro-import, food-data, travel-search + grafana.
- **`profiles: ["tunnel"]`** (opt-in, 2): calendar-web + tailscale-calendar (shared-calendar web UI).
- **`profiles: ["offsite"]`** (opt-in, 1): rclone-offsite (off-site DB-dump replication to Yandex
  Disk and/or a Tailscale host, `BACKUP_OFFSITE_REMOTES` flag — see infra/README.md §Database backups).
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
- **Owner-signed refinement for `mcp-media-fetch` (2026-10-07) — and a lint so this class cannot recur.**
  The same closure break came back: [#294](https://github.com/fedoroff-vlad/ai-life/issues/294) (video
  understanding, 2026-09-02) gave the **hot** `researcher-agent` a hard `depends_on` on the **cold**
  `mcp-media-fetch`, which left `docker compose --profile hot config` an *invalid project* —
  `service "researcher-agent" depends on undefined service "mcp-media-fetch"`. One bad edge rejects the
  whole project, so **the entire hot bring-up** (and `scripts/start-{mac,win}.*` with it) was broken, not
  just the video feature; it sat unnoticed for a month because nothing in the build reads compose.
  Resolution (owner-chosen): `mcp-media-fetch` → **hot**. A pasted video link is **passive inbound** —
  exactly the argument that made `mcp-media-processing` hot — so there is nothing to "start on command",
  and leaving it cold would degrade a shipped feature until LC-3. Cost: +1 JVM (~300 MB) in the hot set
  today, and ~0 once ADR-0006 consolidation lands (it joins the Domain-MCP-hot JVM — see
  [topology-map.md](topology-map.md) §Proposed target topology). **Guard:**
  `scripts/check-consistency.sh` **check 13** (`hot-profile-closure`) now fails CI on any hot→non-hot
  `depends_on` edge, so the invariant in this section is machine-checked instead of remembered.

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
    Dockerfile). Lands before/with LC-3. **Scope note:** LC-3a is the *cold-start-latency* lever
    (~2–3 s → ~ms); the *RAM-footprint* lever (process consolidation + GraalVM native-image to free memory
    for the model) is a separate, larger track — [ADR-0006](adr/ADR-0006-runtime-topology-footprint.md) /
    [#584](https://github.com/fedoroff-vlad/ai-life/issues/584). CDS/AOT composes with it (it stays the
    latency half); native-image there is the footprint half.
- **LC-4 — model-manager in llm-gateway. ✅ SHIPPED 2026-07-21.** Runtime default-model override +
  `/v1/model-profile` + clean unload, **opt-in via `LLM_MODEL_PROFILE_ENABLED` (default off)**, both tags
  from env. See §B for the design + the evict-before-load handshake.
  - **As built.** `model/ModelProfileService` owns the swap; the override lives on
    `LlmGatewayProperties` (model resolution was already there, so every provider follows a downshift
    without knowing the mechanism exists — no provider was touched). `web/ModelProfileController` is
    `@ConditionalOnProperty`, so with the flag off the path is a genuine 404 rather than a disabled
    handler. Unloading is Ollama-native (no OpenAI-dialect equivalent), so the service derives Ollama's
    root from `LLM_BASE_URL` minus `/v1` — the one place in the gateway that speaks Ollama's own API.
    Contract detail in [`platform/llm-gateway/README.md`](../platform/llm-gateway/README.md) §Model profile.
  - **All three acceptance criteria are met and tested** (`ModelProfileServiceTest`, 7 tests): the
    switch polls `/api/ps` until the outgoing model is really gone before the incoming load; the
    ordering is asserted (`unload → ps → ps → load`); an eviction timeout raises and the switch is
    abandoned — no load issued, the profile does not flip, and the gateway keeps serving the model it
    had. Verified the lane can fail: dropping the confirmation poll turns exactly those two tests red.
  - **Driven live against a real Ollama** (dev box, 2026-07-21, `qwen3:8b` ⇄ `qwen2.5:7b` standing in
    for 32B ⇄ 14B) — the half the MockWebServer tests cannot cover: the flag binds from env
    (`LLM_MODEL_PROFILE_ENABLED`), both verbs are a genuine **404** with it off, and a switch really
    evicted the outgoing model and loaded the incoming one (`ollama ps` before/after) in **~10 s**
    end to end, after which chat calls came back tagged with the downshifted model. Restore, the
    re-signal no-op and the 400 on an unknown profile all behaved. So the ~10 s is the wait a coder
    session pays at start — well inside "seamlessness is not required".
  - **Two things the spec left open, decided here.** The override is adopted *between* the confirmed
    eviction and the warm-up, not after it — in the gap a chat request would otherwise resolve to the
    model just unloaded and make Ollama load it straight back. And the warm-up **soft-fails**: by then
    the box is already under budget and correct, so a failed pre-load costs one slow reply, which is a
    better answer than failing the whole switch.
  - **Re-signalling the active profile is a no-op** (a coder that restarted and re-announced itself
    must not cost a model reload), and a second concurrent switch is rejected with 409 rather than
    racing the first one's eviction.
  - **Owner decisions (2026-07-16):** the mechanism is an **add-on, not a base feature** — default off,
    so ai-life runs standalone unchanged and the coder is usable without ai-life; **no hardcoded model
    tags** (both profiles read env — someone else's pair will be different models); **seamlessness is
    not required**, so the handshake may simply wait.
  - **Cross-repo dependency — the coder-side hook is NOT ai-life's to build. ✅ It landed first
    (coding-agent C-6a, 2026-07-21).** This slice ships the *endpoint*; the caller (signal on session
    start/stop + idle timeout, itself flag-gated) lives in coding-agent's `lifecycle.py`. The 2026-07-16
    audit had found the hook orphaned — every doc on both sides said "the coder *only* signals up/down"
    and no slice in either roadmap owned it; coding-agent then took it, ahead of its own C-6, because
    what actually loads a 30B on the shared Mac today is its *analyzer* pass, not a shell. **Both halves
    now exist, so the pair may be run concurrently — with both flags on.** What the caller assumes of
    this endpoint, and what the implementation honours: a **2xx is the confirmation** that the outgoing
    model has left the engine (not an ack), and any other status — including the 404 when this flag is
    off — reads as a refusal that *fails* the coder run rather than letting it load over the ceiling.
  - **Acceptance criterion — the eviction must be *proven*, not assumed (audit 2026-07-16; met by the
    shipped slice above).** With only
    ~14 GB of headroom, "unload before load" is a **correctness requirement, not an optimisation**: if the
    outgoing `qwen3:32b` is still resident when `qwen3-coder:30b` begins loading, peak momentarily needs
    ≈38 GB of models and busts the ~48 GB GPU ceiling (swap/OOM — precisely the failure the downshift
    exists to prevent). LC-4 therefore does not ship until **(1)** `/v1/model-profile` *waits for* the
    outgoing model to actually leave Ollama (`keep_alive:0` / `ollama stop`, then poll `/api/ps` until it
    is gone) before issuing the incoming load — never fire-and-forget; **(2)** a test asserts the
    **ordering** (eviction confirmed → load starts), so the guarantee lives in the suite rather than in an
    env flag; **(3)** an eviction timeout **fails loudly** instead of proceeding into an over-budget load.
    All three are implemented and asserted (see the shipped bullet); this paragraph stays as the reason
    they are non-negotiable, not as an open item.
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
