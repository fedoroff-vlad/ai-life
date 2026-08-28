# ADR-0006: Runtime topology — collapse process sprawl + native footprint to free RAM for the model

**Status:** Proposed (owner-initiated 2026-08-28; measurement-first + hardware-gated on the Mac). Drives
[#584](https://github.com/fedoroff-vlad/ai-life/issues/584). Not yet Accepted — the recommended direction
stands but the slice sequence is measurement-gated (item 1) and the real numbers need the deploy hardware,
so `architecture.md` §Locked decisions is **not** updated until this reaches Accepted.
**Date:** 2026-08-28
**Deciders:** repo owner (holder/admin)
**Builds on:** [lifecycle.md](../lifecycle.md) (Mac deploy + hot/cold + CDS/AOT cold-start LC-3a) and
[migration-25-boot4.md](../migration-25-boot4.md) (Boot 4 / JDK 25; the "GraalVM native-image (if ever)"
aside). This ADR takes the **footprint** angle those two only touch obliquely and makes it a first-class,
recorded goal — distinct from LC-3a's **cold-start-latency** goal.

## Context

The system grew to **~30+ runtime JVM processes** (domain agents + domain/capability-MCP servers +
platform services) across **~57 code modules**. On the target — a **single-user, single-box** deploy (Mac
Studio M4 Max 64/512, running ai-life 24/7) — every idle Spring Boot process costs **~250–350 MB RSS**
(JIT compiler + code cache, class metadata in metaspace, the reflection/classpath-scan graph Spring keeps
live, and the JVM's baseline heap). ~30 × ~300 MB ≈ **9–11 GB spent on framework overhead, not on the
agent's model.**

The owner's concern (2026-08-28): that overhead **competes with the local LLM for RAM**. The goal is a
**bigger / better local model**; the "обвязка" — while it is exactly what makes the system *accurate and
correct* (typed contracts, deterministic privacy, tested flows) — eats the space the model wants.

### The reframe this ADR rests on

**Modules in the repo (code organization) are not the same thing as processes/JVMs at runtime (deployment
topology).** They have been coupled by default — one container per module — but nothing forces it:

- **Correctness lives in the code + tests**, not in the process count: the typed `libs/contracts`, the
  golden/E2E harness, the ADR discipline, the deterministic privacy boundaries (`libs/sharing`,
  confirm-act, undo). None of that depends on *how many JVMs* run.
- **RAM is a deployment concern.** The per-domain microservice topology was a reasonable default, but the
  isolation it buys — independent crash domains, independent horizontal scaling, independent rolling
  restart — is **worth almost nothing on a single-user, single-box, non-scaling target**. We pay ~10 GB of
  per-JVM overhead for isolation we do not use here.

So the sprawl the owner dislikes is a **runtime topology** choice we can change **without touching the
module structure, the contracts, or the tests**.

### What the existing plans already cover — and the gap

- `lifecycle.md` **LC-1/LC-2**: hot/cold compose profiles + supervisor (a cold service is stopped until
  needed). **LC-3a**: CDS/AOT fast cold-start for the agent modules — but its stated motivation is
  **cold-start UX (~2–3 s, no "поднимаю…" placeholder)**, not footprint, and CDS/AOT yields only ~1.3–2×.
- `migration-25-boot4.md:68`: full **GraalVM native-image** is recorded only as *"(if ever) needs v25+"* —
  **not a committed decision**.
- **Nowhere recorded:** the **RAM-for-the-model** goal, and **process consolidation** (the single largest
  footprint lever, requiring zero code-logic change). This ADR fills that gap and sequences the work.

### Forces / constraints

- **Keep correctness.** Typed contracts, goldens/E2E, ADR discipline, and the deterministic privacy engines
  (`sharing`/confirm-act) are non-negotiable. Any topology change must preserve the **module boundaries in
  code** and the **test harness** — the goldens/E2E must run green against the consolidated/native
  artifacts, not only against the dev topology.
- **Optimize for the actual target** — single-user, single-box, 24/7 — not a hypothetical multi-tenant
  cloud. Isolation we do not need is not a requirement.
- **Do not rewrite domain logic.** Domain-MCPs own schemas + rules; they stay as modules and keep their
  HTTP/MCP contracts. Consolidation groups their *processes*, not their code.
- **Measurement-first.** The premise ("services eat the model's RAM") must be **measured**, not assumed —
  at 64 GB a lean service tier may already leave ample room; the measurement may redirect effort to the few
  worst offenders instead of a broad refactor.
- **Hardware-gated.** Real RSS numbers and native builds need the Mac (the dev box is a Citrix VDI: no GPU,
  no Docker daemon). The **design + a measurement harness can be authored now**; execution lands with
  hardware.
- **n8n is out of scope / rejected** (separate discussion): moving to n8n does not reduce the model's RAM
  (the MCP services are still needed, n8n adds its own footprint) and would cost the typed, tested core.

## Options considered

### Option A: Status quo — N JVMs + LC-3a CDS/AOT for cold-start only
**Rejected as the answer to footprint.** CDS/AOT (~1.3–2×) is a *latency* lever framed for cold-start UX;
it does not consolidate processes and does not target the model's RAM. Keep it — but it is not this goal.

### Option B: Process consolidation — group modules into a few JVM "hosts" (**recommended primary**)
Run several modules in **one JVM host** (e.g. one **domain-MCP host**, one **agent host**, one **platform
host**), the modules **unchanged in code**, communicating between hosts over the same localhost HTTP/MCP
contracts (or in-process where a boundary is genuinely internal). Pays the ~300 MB JVM baseline **once per
host instead of once per module.**

| Dimension | Assessment |
|---|---|
| RAM win | **Largest cheap win** — ~30 baselines → ~3–6; reclaims most of the ~10 GB overhead |
| Code change | **~None to logic** — a deploy-time aggregator / multi-module context; modules, contracts, tests untouched |
| Cost | Loses per-domain crash isolation (acceptable single-box — supervisor restarts the host); shared classpath / bean-name care when co-hosting |
| Reversibility | High — a host is a packaging choice; split back out if a real isolation need appears |

### Option C: GraalVM native-image for the resident hot set (**recommended second lever, staged**)
Compile the still-resident hot host(s) **ahead of time** into native binaries: **~5–10× lower per-process
RSS** (~300 MB → ~30–60 MB) and **~50–100 ms** start (which makes cold-start trivial, a strong synergy with
LC-1/2). Cost: slow, memory-hungry native builds; closed-world constraints (reflection/resource **hints** —
Spring Boot 4 AOT + Spring AI/webflux/JPA native support cover most); a **CI native-build + native-smoke
lane**; and a small loss of JIT peak throughput that **does not matter for these I/O-bound services** (HTTP
+ DB + waiting on the LLM). Staged *after* B, applied to what stays resident.

### Option D: Rewrite to another stack / n8n
**Rejected.** Throws away the typed, tested core and does not even target the model's RAM. See the n8n
discussion (2026-08-28).

### Recommended: **B then C, measurement-gated**
Consolidate processes first (cheap, largest win, zero logic change); then native-image the still-resident
hot host(s); keep CDS/AOT (LC-3a) for whatever remains a JVM, now understood as the *latency* lever. The
**repo's module structure stays the SSOT for code organization and is decoupled from the runtime process
count**, which becomes a deploy-time grouping choice.

## Consequences

**Easier:**
- RAM reclaimed for a larger/better local model — the owner's actual goal.
- Cold-start becomes trivial (native ~50–100 ms) — LC-1/2 hot/cold compose with consolidation (a host is a
  hot/cold unit).
- Simpler deploy — fewer processes/containers to compose, healthcheck, and supervise.

**Harder / to revisit:**
- Consolidation loses per-domain crash isolation — on a single box this is acceptable; mitigate with
  supervisor restart of the host and by keeping genuinely-independent singletons (Postgres, `llm-gateway`)
  separate.
- Native adds build time + a CI native-build/smoke lane + reflection/resource hints for edge libraries.
- **Behaviour parity must be proven** on the consolidated + native artifacts (run the goldens/E2E against
  them), not only the dev topology — this is the guardrail that keeps "correctness" intact through the move.
- Co-hosting modules in one JVM needs care with classpath / bean-name / property-prefix collisions and
  per-module `application.yml` merging.
- Relationship to `lifecycle.md`: **LC-3a (CDS/AOT) stays but is re-scoped as the *latency* lever**; this
  ADR owns the *footprint* lever. On Accepted, retire the "if ever" native aside in `migration-25-boot4.md`.

## Action Items (measurement-first; each its own PR; hardware-gated where noted)

1. [ ] **Measurement harness** — a script capturing per-process RSS + the total, and the **JVM vs model vs
   Postgres** split on the running stack, establishing the real baseline (it may show 64 GB is already
   ample, or pinpoint the worst offenders). *Authorable now; run at deploy.*
2. [~] **Topology map** — classify every **runtime** module as must-be-resident (hot) / on-demand (cold) /
   consolidatable-together, and propose the host groupings (domain-MCP host / agent host / platform host /
   isolated singletons: `llm-gateway`, **`memory-service`**, Postgres). *Design doc, no code.* **Drafted:
   [topology-map.md](../topology-map.md)** (47 JVMs → ~12 hosts; `memory-service` isolated up front). Real
   host boundaries confirmed by the slice-1/3 measurement on the Mac.
3. [ ] **Consolidation spike** — one host JVM running **all domain-MCPs** behind the same localhost HTTP
   contracts (a multi-module Spring context or a thin aggregator); measure the RAM delta **and** assert the
   goldens/E2E parity. Reversible. *Hardware-gated for the real number; buildable/testable earlier.*
4. [ ] **Rollout** — extend consolidation across the agent + platform tiers, guided by item 1's numbers.
5. [ ] **Native-image lane** — CDS/AOT (LC-3a) → GraalVM native-image for the resident hot host(s); a CI
   native-build + native-smoke lane; reflection/resource hints as needed. *Hardware-gated.*
6. [ ] **Re-measure + cement** — record the final topology in `architecture.md` + `lifecycle.md`, retire the
   `migration-25-boot4.md` "if ever" native aside, and move this ADR to Accepted.

## Notes

Sixth ADR in the repo. The **runtime-topology-vs-module-structure decoupling** is the new architectural
concept introduced here — flagged (new concept) rather than invented silently, per the session rules.
`architecture.md` §Locked decisions is updated only when this reaches **Accepted** (after the measurement
pass validates the direction with real numbers). Tracks as epic
[#584](https://github.com/fedoroff-vlad/ai-life/issues/584); hardware-gated behind the Mac purchase, same
gate as the rest of `lifecycle.md`.
