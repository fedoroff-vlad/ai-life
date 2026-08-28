# ADR-0005: Personalization profile as a reusable cross-domain capability

**Status:** Accepted (2026-08-28 — Option B, owner-approved: `libs/profile` shared resolver + `agent-runtime`
profiler template + per-module `profile/` adapter; store stays per-domain). Implementation underway. Drives
[#476](https://github.com/fedoroff-vlad/ai-life/issues/476) (architecture-hardening epic
[#479](https://github.com/fedoroff-vlad/ai-life/issues/479)).
**Date:** 2026-08-28
**Deciders:** repo owner (holder/admin)
**Builds on:** [ADR-0001](ADR-0001-identity-membership-scope.md) (multi-tenant identity — personal
household per user + M:N membership + household-routing reads) and [ADR-0002](ADR-0002-sharing-shared-capability.md)
(sharing as a `libs/sharing` shared engine + per-module policy). This ADR applies the **same "lift the
repeated pattern into a shared capability, keep the domain data local" move** to the second place that
lagged that discipline: the per-member **personalization profile**.

## Context

Five domains each let a member state their own preferences in chat and store them as a per-member
profile. Every one re-implements the **same `(household_id, owner_id)` personalization pattern**:

| Domain | Store (mcp) | DTO | Agent client | Profiler flow | SKILL |
|---|---|---|---|---|---|
| briefing | `BriefingProfile` | `BriefingProfileDto` | `BriefingProfileClient` | `BriefingProfiler` | `briefing-profiler` |
| creator | `CreatorProfile` | `CreatorProfileDto` | `CreatorProfileClient` | `CreatorProfiler` | `creator-profiler` |
| nutrition | `DietProfile` | `DietProfileDto` | `DietProfileClient` | `DietProfiler` | `diet-profiler` |
| travel | `TravelProfile` | `TravelProfileDto` | `TravelProfileClient` | `TravelProfiler` | `travel-profiler` |
| stylist | `StyleProfile` | `StyleProfileDto` | `StyleProfileClient` / `WardrobeReadClient` | `AnalyseMe` | `style-profiler` |

The **repeated mechanism** (not the domain data) is:

1. **Write scope.** Every profiler runs `boolean household = "household".equalsIgnoreCase(scope)` then
   writes `ownerId = household ? null : msg.userId()` — the exact same line in all five
   (`BriefingProfiler:89-92`, `CreatorProfiler:74-77`, `DietProfiler:77-80`, `TravelProfiler:99`, stylist
   via `AnalyseMe`). "self → the sender; household → the default" is a copy-pasted comment.
2. **Read resolution.** Every read flow resolves `get(household, userId)` → `switchIfEmpty
   get(household, null)` → a plain default. Reimplemented per domain.
3. **The `/internal/<domain>-profile` client** — `get(householdId, ownerId)` (404 → empty, 10s timeout)
   + `set(input)` — is structurally identical across all five agents.
4. **The profiler flow** — one deterministic LLM extract via the domain's `*-profiler` SKILL (temp 0) →
   lenient JSON parse → scope → build the domain's `SetXxxProfileInput` → write — is the same skeleton
   with domain-specific fields.

What is **genuinely domain-specific** (and must stay local): the profile **fields** (briefing:
location/interests/sections/schedule; diet: goals/restrictions; style: body-shape/colours; travel:
home base/rest-type; creator: track/cadence), the **extraction prompt** (the SKILL), and any per-domain
post-step (briefing geocodes the stated city).

**The trigger (why now, concretely).** [#490](https://github.com/fedoroff-vlad/ai-life/issues/490) FO-3
just added **family-default inheritance** — a new household member who set nothing inherits the family's
shared household-default profile — but it lives **only in `BriefingComposer.resolveProfile`**. The other
four personalization domains still dead-end a new member (their read resolution stops at
`get(household, null)`). Copying FO-3 into four more places is exactly the silent-drift the
change-propagation rules exist to prevent. Lifting the resolver **propagates FO-3's benefit to all five
domains at once** — the clearest possible ROI for this refactor.

### Forces / constraints

- **No duplication.** One resolver + one client shape + one profiler template; N thin domain adapters. A
  change to the scope/inheritance rules (like FO-3) lands in one place.
- **Domain owns its data.** Profile fields are heterogeneous, jsonb-heavy, and read on **domain hot
  paths** (the briefing digest reads its profile; the nutrition analysis reads the diet profile). They
  belong to the domain's own schema (`architecture.md` §Conventions: "DB schemas split by domain"). The
  store must **not** be centralized.
- **Reuse the identity reads.** The family-default resolution needs the caller's personal/shared
  household split — already served by profile-service `household-routing` / `/households` and already
  wrapped by `libs/sharing`'s `ProfileSharingClient` (ADR-0002). Don't invent a second identity surface.
- **Simplicity (owner value).** A domain's cost to adopt personalization should be ~one thin adapter +
  its SKILL + wiring (≤5 files), not a re-derivation.
- **Coach is out of scope.** `CoachProfile` is `(household_id, subject)`-scoped — per **person/subject**,
  not per **operator** — a deliberately different model (ADR-0001 item 6 territory). It is **not** one of
  the five and is not touched here.

## Decision (recommended)

Adopt the **ADR-0002 shape**: a shared mechanism + per-module adapter, store stays per-domain. Named
**`profile`** throughout (a personalization profile; distinct from `profile-service`, which stays the
identity service).

### 1. Shared mechanism — split by dependency weight

- **`libs/profile` (new light leaf — `contracts` + reactor WebClient + `libs/sharing`'s identity read,
  no LLM):**
  - `ProfileScope` — the write-scope helper: `ownerId(scope, userId)` = `household ? null : userId`. One
    definition of "self vs household-default".
  - `ProfileScopeResolver<T>` — the **read** engine, the generalized FO-3 logic:
    `resolve(userId, householdId, fetch)` = `fetch(household, userId)` → `switchIfEmpty
    fetch(household, null)` → `switchIfEmpty familyDefault(userId, household, fetch)` → `Mono.empty()`,
    where `familyDefault` reuses `ProfileSharingClient.households`/`householdRouting` to find the shared
    household-default. `fetch` is a `(householdId, ownerId) -> Mono<T>` the domain supplies (its typed
    client). **This is the one place the self → own-default → family-default → empty rule lives.**
  - `PersonalizationProfileClient<T>` — the generic typed `/internal/<path>` client shape:
    `get(householdId, ownerId)` (404 → empty, timeout) + `set(input)`.
- **`PersonalizationProfiler` template → `libs/agent-runtime`** (it needs `libs/llm-client` +
  `SkillRegistry`, already there — it is the sibling of `SkillRouter` / `PickConfirmActRunner` /
  `BriefResponder`): one deterministic extract via a named SKILL → lenient JSON parse → `ProfileScope` →
  a domain `(draft, ownerId, msg) -> Mono<SetInput>` builder (where briefing does its geocode) → write.

The light data-plane bits go in the leaf so **read services** (not just agents) can reuse the resolver,
exactly as `libs/sharing` split its LLM-free engine from the confirm plumbing.

### 2. Per-module convention (same-named directory)

Every personalization domain gets a package **`profile/`** (mirroring `sharing/`) holding **only its
tweak**: the typed client binding, the `SetInput` builder, and any post-step (geocode). The resolver,
the scope helper, the profiler template, the identity read — all shared.

### 3. Cementing

On acceptance: `architecture.md` §Locked decisions records "the per-member personalization profile is a
shared capability (`libs/profile` resolver + `agent-runtime` profiler template); the store stays
per-domain; `family → own → family-default → empty` is the one resolution rule". `PATTERNS.md` gets an
"**add a personalization profile to a domain**" recipe pointing at briefing as the canonical example.

## Options Considered

### Option A: Leave it — copy FO-3 into the other four domains
**Rejected.** Propagates the family-default fix by hand into four more read flows and leaves the
scope/client/profiler duplication in place; the next resolution-rule change repeats across five modules.
The exact silent-drift the change-propagation map exists to stop.

### Option B: Shared mechanism (`libs/profile` + `agent-runtime` template) + per-module `profile/` — store per-domain (**recommended**)
| Dimension | Assessment |
|---|---|
| Duplication | **None** — one resolver + client + template, N thin adapters |
| Per-domain cost | **Low** — an adapter + SKILL + wiring, ≤5 files |
| Domain fidelity | **High** — fields, SKILL, post-steps stay local; store stays in the domain schema |
| FO-3 propagation | **Free** — family-default inheritance reaches all five at once |
| Precedent | Mirrors ADR-0002 exactly (proven) |

**Cons:** an up-front refactor (build the leaf + template, retrofit briefing as the reference) before the
other domains see value; one new light leaf module enters the build.

### Option C: Centralize storage into profile-service (one `core.personalization_profile(household, owner, domain, data jsonb)` table + generic endpoints)
**Rejected.** Truly DRY on storage, but it **fights the architecture**: it pulls heterogeneous
domain-owned data into the `core` schema (against "DB schemas split by domain"), turns every domain's
hot-path profile read into a cross-service HTTP hop, and couples all five domains to one service's
release. The domain data (diet goals, wardrobe colours) is genuinely domain-owned — only the *mechanism*
is shared. Option B keeps the mechanism shared and the data local — the correct seam, same conclusion
ADR-0002 reached for sharing.

## Consequences

**Easier:**
- FO-3 family-default inheritance immediately covers briefing + creator + nutrition + travel + stylist.
- Adding personalization to a new domain becomes a recipe (adapter + SKILL + wiring), not a design task.
- The scope/resolution rules have one home; a fix (like FO-3, or a future "confirm which member")
  propagates to all at once.

**Harder / to revisit:**
- An up-front refactor: build `libs/profile` + the `agent-runtime` template, retrofit briefing (its
  `BriefingProfiler` + `BriefingProfileClient` + `resolveProfile` collapse onto the shared code) before
  any new value — sequenced as the foundation + reference slices.
- One new light leaf module (`libs/profile`) enters the build (and the CI `-pl` pre-install list —
  change-map coupling, `check-consistency.sh` check 6).
- The generic template must allow a domain post-step (briefing's geocode) — handled by the domain-supplied
  `SetInput` builder, not a special case in the template.
- Relationship to `libs/sharing`: both reuse the same identity reads (`household-routing` / `/households`).
  `libs/profile` **depends on** `libs/sharing`'s `ProfileSharingClient` for that read rather than
  re-wrapping it — one identity client, two capabilities on top.

## Action Items (slice sequence — each its own PR, ≤5 files)

1. [x] **Accept this ADR**; update `architecture.md` §Locked decisions + register in `INDEX.md` + add the
   `PATTERNS.md` "add a personalization profile to a domain" recipe. *(docs — the accept PR)*
2. [x] **Foundation — `libs/profile` leaf** (`ProfileScope`, `ProfileScopeResolver`,
   `PersonalizationProfileClient`) + the `PersonalizationProfiler` template in `agent-runtime`. Unit-tested
   in isolation (resolver self/household/family/empty cases mirror the FO-3 tests); no domain wired beyond a
   green build.
3. [x] **Reference retrofit — briefing** (write + read): `BriefingProfiler` → the shared template (as a
   `ProfileSpec`), `BriefingProfileClient` → the generic client (subclass), `BriefingComposer.resolveProfile`
   → `ProfileScopeResolver` (its FO-3 family-default is now the shared rule). Behaviour unchanged (existing
   briefing tests + goldens green). Briefing is the canonical example the PATTERNS recipe points at. The
   `PersonalizationProfiler` template is wired as a shared `AgentRuntimeConfig` bean, so the remaining
   domains just inject it.
4. [x] **creator** — thin `profile/` adapter + wiring onto the shared mechanism; family-default now applies
   (`CreatorProfiler` → `ProfileSpec`, `CreatorProfileClient` → generic subclass,
   `ContentStrategist.resolveProfile` → `ProfileScopeResolver`).
5. [ ] **nutrition (diet)** — same; family-default now applies to the diet profile read.
6. [ ] **travel** — same; family-default now applies (home base inheritance).
7. [ ] **stylist** — same (its split set/get clients collapse onto the generic client). **All five
   retrofitted → capability complete.**

## Notes

Fifth ADR in the repo. `libs/profile` + the `agent-runtime` profiler template are new shared layers —
flagged here (new layer) rather than invented silently. Per the ADR convention, `architecture.md`
§Locked decisions is updated only when this ADR reaches **Accepted**; until then it is the proposal and
architecture.md remains current truth. `#478` (reconcile the empty `shared/skills/` doctrine) couples
with this work and is a natural companion slice.
