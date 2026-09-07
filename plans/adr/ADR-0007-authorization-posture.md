# ADR-0007: Authorization posture — trust boundary, inter-service authN, human authN/authZ

**Status:** Proposed (owner-initiated via the 2026-09-06 security/reliability review). Drives
[#632](https://github.com/fedoroff-vlad/ai-life/issues/632). Recommends a direction; the owner (holder/admin)
is the decider. `architecture.md` §Security / §Locked decisions is **not** updated until this reaches
**Accepted** (same convention as [ADR-0006](ADR-0006-runtime-topology-footprint.md)).
**Date:** 2026-09-06
**Deciders:** repo owner (holder/admin)
**Builds on:** [ADR-0001](ADR-0001-identity-membership-scope.md) (identity + household tenant-routing = the
data-layer authZ), [ADR-0004](ADR-0004-confirm-act-flow.md) (outbound confirm gate), `architecture.md`
§Security (untrusted-input / injection doctrine), and the already-shipped review slices
[#627](https://github.com/fedoroff-vlad/ai-life/issues/627) (owner-allowlist onboarding),
[#628](https://github.com/fedoroff-vlad/ai-life/issues/628) (loopback port binding),
[#629](https://github.com/fedoroff-vlad/ai-life/issues/629) (secret + dependency scanning).

## Context

The 2026-09-06 review found ai-life has **no explicit, written authorization model**. It works today, but
the model is implicit, undocumented, and rests on three things that were never stated as *the* security
design:

1. **Network isolation** — services talk over the internal compose network; nothing but the perimeter
   ports were exposed (and #628 has now bound even those to loopback, off-host access only via the
   Tailscale Funnel sidecar).
2. **A single trusted Telegram bot token** — the one externally-facing credential; the bot is the only
   human entry point, now gated by an owner-allowlist (#627) so a stranger can't even onboard.
3. **Household tenant-routing (ADR-0001/0002)** — the data-layer authZ: an item lives in exactly one
   household = its visibility boundary; a member reads the union over their memberships. This is
   deterministic and already enforced in the domain read/write paths.

### What actually exists for inter-service auth

- **Partial, and already the right shape where it matters most:** `gateway-telegram`'s `/internal/send`
  is guarded by `Authorization: Bearer ${GATEWAY_INTERNAL_API_TOKEN}` (notifier sends the matching
  `INTERNAL_API_TOKEN`). The bot token — the highest-value secret — never leaves the gateway, and the one
  internal endpoint that can make it send messages is shared-secret-guarded.
- **Everything else `/internal/*` is unauthenticated** (agent→MCP, orchestrator→agent, scheduler→
  orchestrator): it relies purely on network isolation. `spring-boot-starter-security` is on no classpath.

### The real threat model for *this* target

The target is **single-user, single-box, 24/7** (Mac Studio, personal household + invited family). That
shapes which threats are real:

- **Not** a malicious tenant on a shared cluster (there is no cluster, no other tenant).
- **Real:** the **perimeter leaking** (a host on an untrusted LAN) — addressed by #628 loopback + Tailscale-only
  off-host.
- **Real:** the **bot token leaking** → full owner impersonation. Bounded by #627 (allowlist) and by the
  token living only in the gateway; mitigated further by rotation.
- **Plausible:** **lateral movement** — a compromised dependency, sidecar, or a future third-party MCP on
  the internal network calling an internal tool directly, bypassing the orchestrator and the confirm gate.
  Today only network isolation stops this.
- **Always-on:** **prompt injection** via ingested content — already handled by the injection doctrine
  (`UntrustedContent.GUARD`) + the outbound confirm gate (ADR-0004); orthogonal to this ADR but part of the
  same "untrusted actor can propose, never act" spine.

## Questions this ADR settles (from #632)

1. **Trust boundary** — is the compose/tailnet network the boundary, or do we want auth *inside* it too?
2. **Inter-service authN** — none (network only) / shared-secret header / mTLS?
3. **Human authN** — is bot-token + allowlist enough, or do sensitive actions need step-up?
4. **AuthZ** — is household tenant-routing sufficient, and is it enforced at *every* read/write seam?
5. **Blast radius if the bot token leaks** — acceptable? mitigations?
6. **Multi-user future** — does "network = trust" still hold if family shares one deployment?

## Options considered (inter-service authN — question 2, the crux)

### Option A: Status quo — network isolation only
**Rejected as the recorded answer.** It is *almost* the right call for this target, but leaving it implicit
and single-layer means one perimeter slip (a mis-bound port, a compromised sidecar) exposes every internal
tool with no second line. The cost of fixing that is trivial, so "do nothing" is not worth defending.

### Option B: Shared-secret header on all `/internal/*` (**recommended**)
Generalize the pattern **that already guards `/internal/send`** into a `platform-common` filter applied to
every `/internal/**` endpoint: require `Authorization: Bearer ${INTERNAL_SHARED_SECRET}` (or `X-Internal-Token`),
reject unauthenticated with 401; every `libs/agent-runtime/http/*Client` sends it. Health/actuator endpoints
stay open.

| Dimension | Assessment |
|---|---|
| Security gain | Defense-in-depth: network isolation is no longer the *only* thing between an attacker-on-the-network and `ToolDispatcher`. Closes the lateral-movement gap cheaply. |
| Cost | Low — one filter + one env secret + the outbound clients already have the pattern to copy. No PKI, no cert rotation. |
| Fit for target | Proportionate. A private, single-box, loopback/tailnet network + a shared secret is a sound boundary for one user. |
| Limit | A shared secret is symmetric — any holder can impersonate any caller. Acceptable when all callers are first-party on one box; **not** an identity system. |

### Option C: mTLS between services (**deferred**)
Per-service certificates, a local CA, mutual TLS. Gives real per-service identity and encryption in transit.
**Disproportionate for the target:** cert issuance/rotation/renewal is real operational weight, and on a
single box with a private network the marginal benefit over Option B is small (traffic is loopback/tailnet,
not a hostile wire). Revisit only if the target changes — see the trigger below.

### Option D: Full Spring Security (**deferred**)
Adopt `spring-boot-starter-security` across services with a real authN/authZ framework. Heavyweight for what
is needed now; Option B's single filter delivers the actual requirement (guard `/internal/*`) without pulling
a framework and its config surface into every service. Revisit with a genuine multi-tenant need.

### Recommended: **B — "network is the boundary, made real and defended in depth"**
Accept the network as the **primary** trust boundary (it fits a single-box/single-user target), but stop
relying on it as the *only* layer:
- **Inter-service:** ship the shared-secret filter on all `/internal/*` (**#630**) — the interim *is* the
  standing answer for this target, not a stepping stone to mTLS.
- **Perimeter:** already done — #628 (loopback) + Tailscale Funnel for the one off-host feed.
- **Human authN:** bot-token + #627 allowlist is sufficient; **sensitive/outbound actions already require
  step-up via the confirm gate** (ADR-0004). No additional human step-up now.
- **AuthZ:** keep household tenant-routing (ADR-0001/0002) as the authZ mechanism, and **audit** that every
  `/internal/*` read/write seam actually applies the household filter (action item — an audit, not a
  redesign).
- **Record the accepted residual risks** and the **explicit trigger** that would reopen C/D.

### The trigger that reopens mTLS / Spring Security
This posture is **scoped to single-user/single-box**. Revisit Option C/D if any of these becomes true:
public/multi-tenant hosting (untrusted co-tenants), services split across hosts on a shared untrusted
network, or a compliance requirement for encryption-in-transit / per-service identity. Until then, B stands.

## Consequences

**Easier / better:**
- The authorization model is **written down** — future sessions and the owner can reason about it instead of
  rediscovering an implicit design.
- Lateral-movement risk closed cheaply; the bot token's one dangerous endpoint stays guarded and the rest of
  `/internal/*` joins it under one mechanism.
- No PKI/framework weight added to a single-box deploy.

**Harder / to revisit:**
- A shared secret is symmetric — it authenticates "a first-party caller," not *which* caller. Documented as
  an accepted limit for this target.
- The secret becomes a managed value (in `.env`, rotated on suspicion) — one more thing to keep out of git
  (the #629 gitleaks gate backstops this).
- The household-authZ audit (action item 2) may surface an `/internal/*` path that trusts its caller for the
  household instead of enforcing it — that would be a real fix, tracked separately.

## Action Items

1. [x] **#630 — shared-secret filter on all `/internal/*`.** Shipped: a `platform-common` autoconfig
   guards `/internal/*` (servlet + reactive filters) and a `WebClientCustomizer` stamps the bearer on
   outbound `/internal/*` calls fleet-wide (all clients build from the auto-configured `WebClient.Builder`);
   `INTERNAL_SHARED_SECRET` (`internal.shared-secret`) empty = disabled. The old gateway `GATEWAY_INTERNAL_API_TOKEN`
   / notifier `INTERNAL_API_TOKEN` are folded into it. Compose injects the secret into every service via a
   YAML anchor.
2. [ ] **Household-authZ seam audit.** Verify every `/internal/*` read/write applies the household/tenant
   filter (ADR-0001) rather than trusting the caller-supplied id. File findings as their own issue(s).
3. [ ] **Bot-token blast-radius note.** Document the rotation procedure and confirm the token lives only in
   the gateway (it does today) — a short runbook entry.
4. [ ] **On Accepted:** record this posture in `architecture.md` §Security + §Locked decisions, and add the
   C/D reopen-trigger there so a future multi-tenant pivot re-examines it.

## Notes

Seventh ADR in the repo. The new recorded concept is the **explicit trust-boundary decision** — "the
private single-box network is the primary boundary, made real (loopback + tailnet-only) and defended in
depth (shared-secret on `/internal/*`), with mTLS/Spring-Security deferred behind a stated trigger." Flagged
as a decision rather than silently assumed, per the session rules. Part of the 2026-09-06 security/reliability
review backlog (#627–633); #627/#628/#629 shipped, this ADR scopes #630, and #631/#633 are the reliability
half (independent of authZ).
