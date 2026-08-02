# ADR-0002: Sharing (personal vs shared) as a reusable cross-domain capability

**Status:** Accepted (2026-08-01 — Option B; owner-approved: `libs/sharing` shared module + per-module
`sharing/` policy; deterministic mechanism, extensible default-policy seam)
**Date:** 2026-08-01
**Deciders:** repo owner (holder/admin)
**Builds on:** [ADR-0001](ADR-0001-identity-membership-scope.md) (multi-tenant workspace identity —
personal household per user + M:N membership + per-item tenant routing). ADR-0001 shipped the identity
substrate and proved the routing on **calendar** (slices 4–5). This ADR generalises that one-off into a
**shared capability** every domain reuses.

## Context

ADR-0001 delivered "own vs shared" for calendar by routing each item to exactly one household (personal
or family) and reading the union over the caller's membership set. The **infrastructure** it produced is
already domain-neutral:

- profile-service `GET /v1/users/{id}/household-routing` → `{personalHouseholdId, sharedHouseholdIds}`
  (the write-path split) and `GET /v1/users/{id}/households` (the read-path union) — **not** calendar-specific.

But the **consumer-side** logic landed inline in calendar:

- `SharingScope{PRIVATE,SHARED}` lives in `contracts/calendar` (domain-tagged, but conceptually neutral).
- the write-path resolution (explicit choice → else default-sharing policy → `householdRouting` → pick
  personal/family → fallbacks) is hand-written in `calendar-agent`'s `ActionController`.
- the read-path member→set resolution is hand-written in `calendar-web`.

The owner's decision (design chat, 2026-08-01): **this must be a cemented architecture, not copied per
domain.** Concretely:

1. The reusable mechanism lives in a **shared location and is used by all** domains.
2. Each domain's own tweak on top lives in a **same-named directory in every module** (one convention).
3. The second household member (spouse) exists **from the start**, so the capability delivers value
   immediately — this is not speculative.

The domains that need it beyond calendar: **finance** (joint budget/expenses vs personal spending),
**tasks** (household chores / shared shopping list vs personal todos), **nutrition** (shared meal plan /
shopping vs the inherently-personal food log), **documents** (household warranty/contract vs personal ID).
Copying calendar's inline logic into each is exactly the silent-drift failure the change-propagation
rules exist to prevent.

### Forces / constraints

- **No duplication.** One engine, N thin policies. A change to the routing/fallback rules must land in
  one place.
- **Domain-specific "what is shared" is real.** The *default* of an item (occasion → shared; personal
  meeting → private; joint-account txn → shared) is genuinely per-domain. The engine must accept a
  domain policy, not hard-code one.
- **Simplicity (owner value).** The per-domain cost of adopting sharing should be ~one small policy
  class + wiring, not a re-derivation. Aim: a domain joins in ≤5 files.
- **Not every domain shares.** coach / health / stylist-wardrobe are private by nature; creator /
  research / market-data are single-owner. The capability is *opt-in* — a domain that never calls it is
  unaffected.
- **Reuse the ADR-0001 primitives** (`household-routing`, `/households`) rather than inventing new
  identity surfaces.

## Decision

Adopt a **shared engine + per-module policy** design, named **`sharing`** throughout (aligns with the
existing `SharingScope`).

### 1. Shared core — a new leaf module `libs/sharing`, taken by **everyone**

The mechanism is a small, dependency-light module both **domain agents** and **read-only web services**
depend on (it pulls only `contracts` + reactor WebClient — no LLM, no skill registry), so there is **zero
duplication**, including on the read side.

- **`SharingScope` → `libs/contracts` `contracts/common`** (lifted out of `contracts/calendar`;
  domain-neutral, and it is a wire type — it rides in `CreateEventInput` JSON). All domains reference the
  one enum.
- **`libs/sharing`:**
  - `DefaultSharingPolicy` — the domain extension point: `SharingScope decide(SharingContext ctx)`.
    `SharingContext` carries the neutral signals a policy reads (categories/tags, whether a household
    member is involved, item kind). Implemented once per domain, in that domain's `sharing/` package.
  - `SharingResolver` — the **write-path** engine: `resolveHousehold(userId, explicitScope, ctx,
    fallbackHousehold) → Mono<UUID>`. Encapsulates the whole rule set currently inline in calendar:
    explicit choice wins → else `policy.decide(ctx)` → household-routing → pick personal vs first-shared →
    `userId`-absent / profile-404 → `fallbackHousehold` → shared-with-no-family degrades to personal.
  - `ProfileSharingClient` — the thin identity read: `householdRouting(userId)` (write split) +
    `households(userId)` (read union, personal ∪ shared). This is the one client both agents and web
    services call, so the read-side member→set resolution is shared too (no per-service 2-liner).
- **Policy is extensible by design (the seam).** `DefaultSharingPolicy` is the single point where the
  *default* decision is made. Today each domain plugs a **deterministic** rule (occasions → shared,
  joint-account → shared, else private). Later, the *same interface* can be backed by a
  **memory/second-brain-driven** implementation (learn the owner's past choices, confirm only on
  ambiguity — ADR-0001 action item 7) **without touching `SharingResolver` or any domain**. The
  *mechanism* (which household a row lands in / which set a read spans) stays deterministic — it is a
  privacy boundary, not a place for probabilistic inference; only the *default-when-unspecified* is where
  judgement (and later, learning) lives.
- **Cementing:** `architecture.md` §Locked decisions records "sharing (`libs/sharing`) is *the*
  cross-domain personal/shared privacy primitive; the mechanism is deterministic, the default-policy is a
  per-domain `DefaultSharingPolicy` that may later be memory-driven" (on acceptance); `PATTERNS.md` gets
  an "**add sharing to a domain**" recipe pointing at calendar as the canonical example.

### 2. Per-module convention (same-named directory)

Every domain agent that opts in gets a package **`sharing/`** containing **only its domain tweak**:

```
domains/<domain>/<domain>-agent/.../sharing/
    <Domain>SharingPolicy.java   implements DefaultSharingPolicy   // the "what is shared here" rule
    (wiring: the agent injects SharingResolver with this policy)
```

The engine, the enum, the identity calls — all shared. The folder name is identical in every module, so
"where's the sharing tweak for domain X" has one answer.

### 3. Read side for non-agent web services

`calendar-web` (a platform read service) is not a domain agent and must not pull the heavy
`agent-runtime`. Because the mechanism lives in the light `libs/sharing` leaf, web services depend on
**that** instead and call `ProfileSharingClient.households(userId)` for the member→set resolution — the
same code agents use. No per-service duplication; the module boundary keeps web services free of the
agent runtime while still sharing the identity read.

## Options Considered

### Option A: Copy calendar's inline logic into each domain
**Rejected.** Every domain re-implements the resolver + fallbacks; a rule change (e.g. the
shared-with-no-family degrade) must be found and fixed in N places — the exact silent-drift the
change-propagation map exists to stop. Fails the owner's "no duplication" requirement.

### Option B: Shared engine (`libs/sharing`) + per-module `sharing/` policy (**recommended**)
| Dimension | Assessment |
|-----------|------------|
| Duplication | **None** — one resolver, N thin policies |
| Per-domain cost | **Low** — a policy class + wiring, ≤5 files |
| Domain fidelity | **High** — each domain keeps its own "what is shared" rule |
| Fit to owner's ask | **Full** — shared mechanism used by all + same-named per-module dir |

**Cons:** an up-front refactor (lift the enum, extract the engine, retrofit calendar as the reference)
before the first new domain sees value.

### Option C: Fully centralize, including the default-sharing policy
One central policy that inspects item type for all domains. **Rejected** — "what is shared" is genuinely
domain-specific and evolving (finance's joint-account rule has nothing to do with calendar's occasion
rule); centralising it couples every domain to one file and one release. Option B keeps the *mechanism*
shared and the *policy* local — the correct seam.

## Consequences

**Easier:**
- Adding sharing to finance/tasks/nutrition/docs becomes a recipe (policy + wiring), not a design task.
- The routing/fallback rules have exactly one home; a fix propagates to all domains at once.
- calendar stops being a special case — it's the reference implementation of the shared engine.

**Harder / to revisit:**
- One refactor PR moves `SharingScope` and rewrites calendar onto the engine (blast radius: calendar
  contract + calendar-agent + tests) before any new value — sequenced as the foundation slice.
- The `SharingContext` shape is a new shared contract; it will need a field or two as new domains reveal
  signals (start minimal: `categories`, `involvesHouseholdMember`, `itemKind`).
- One new leaf module (`libs/sharing`) enters the build; it is small and dependency-light, and it is the
  price of removing duplication across both agents and web services.
- memory/second-brain still uses the older owner-tag model (ADR-0001 noted the reconciliation as
  tech-debt); this ADR does **not** retrofit it — it stays a separate later item. When the
  default-sharing *policy* graduates to memory-driven (ADR-0001 item 7), it plugs into the
  `DefaultSharingPolicy` seam — no engine or domain change.

## Action Items (slice sequence — each its own PR, ≤5 files)

1. [x] **Accept this ADR**; update `architecture.md` §Locked decisions (sharing = the cross-domain
   personal/shared primitive) + register in `INDEX.md` + add the `PATTERNS.md` "add sharing to a domain"
   recipe. *(docs — this PR)*
2. [x] **Foundation — the `libs/sharing` module:** create the leaf module (`DefaultSharingPolicy`,
   `SharingContext`, `SharingResolver`, `ProfileSharingClient`); lift `SharingScope` → `contracts/common`.
   No domain wired yet beyond keeping the build green. *(the shared engine)*
3. **Retrofit calendar (reference impl):** *(split write/read — >5 files)*
   - [x] **3a — write:** calendar-agent uses `SharingResolver` + `sharing/CalendarSharingPolicy`
     (occasions → shared) on its write path; the inline routing/fallback logic deleted. Behaviour
     unchanged (7 `ActionControllerTest` cases green). Calendar is now the canonical example the PATTERNS
     recipe points at.
   - [x] **3b — read:** calendar-web uses `libs/sharing`'s `ProfileSharingClient.households` on its read
     path (via `config/SharingConfig`; the local `ProfileHouseholdsClient` retired). Behaviour unchanged
     (7 calendar-web tests green). **Calendar fully retrofitted — the capability's reference impl.**
4. **Finance** — scoping decided (owner, 2026-08-01): **account-level, not per-transaction** — mcp-finance's
   `addTransaction` cross-household guard already forces a transaction's household to equal its account's, so
   the account is the sharing boundary (joint account → shared, personal card → private). **Report cut:**
   default = the member's **own** (personal-household) spending; **shared/family on explicit request** ("наши
   траты"). Split write/read like calendar:
   - [x] **4a-i — read (advisor):** `finance-agent`'s `FinancialAdvisor` gained a shared-scope union read —
     `advise(msg, shared)` reads across the member's personal ∪ shared households via
     `ProfileSharingClient.households` (fan-out + `mergeByCategory`) when the classifier tags `scope:"shared"`;
     default stays the personal household. `libs/sharing` bound (bean in `OutboundHttpConfig`). mcp-finance
     untouched (tenant-agnostic).
   - [x] **4a-ii — read (reporters):** the shared-vs-personal cut + cross-household merge lifted into a shared
     `read/SpendingReads` helper (adopted by the advisor too — no duplication); `MonthlyReporter` /
     `YearReporter` gained `report(msg, shared)` and the `report` classifier flow reads `scope:"shared"`.
     Balances have no dedicated agent read flow (only the `get_balance` MCP tool, tenant-scoped) → nothing to
     retrofit there.
   - [x] **4b — write:** the finance sharing write path. Built the account-create seam (owner-chosen: a
     chat-driven `AccountManager` flow, the sibling of `CategoryManager`) — mcp-finance `POST /internal/account`
     passthrough + `AccountClient.upsert` + `account` classifier action + `account-manager` SKILL — and routed
     it through the shared `SharingResolver` wired with a new `sharing/FinanceSharingPolicy` (joint account
     → shared household, personal → the member's own; the account is the sharing boundary). mcp-finance stays
     tenant-agnostic. Validated: unit `AccountManagerTest`/`IntentRouterTest` + mcp-finance controller test
     green; `GoldenRoutingTest` structure + behaviour green on the real model (the `account` route confirmed).
     **Finance is now fully retrofitted (read 4a + write 4b).**
5. **Tasks** — split write/read like calendar/finance. Read default = own (mirror finance): the shared
   cut is on explicit request (`scope:"shared"`), not by default.
   - [x] **5a — write:** `tasks-agent`'s `task-capture` flow (`TaskCapturer`, the sibling of finance's
     `AccountManager`) plans a task from a plain-language capture and routes it to a personal vs shared
     household via the shared `SharingResolver` wired with a new `sharing/TasksSharingPolicy`
     (household/shared-list task — chore, shared shopping, involves another member → shared; personal
     todo → private). Persisted via mcp-tasks' new `POST /internal/task` passthrough (`AddTaskClient`).
     This is the deterministic capture an LLM-driven `add_task` tool call cannot take (the classifier
     never sees the household id). mcp-tasks stays tenant-agnostic. Validated: `TaskCapturerTest`
     (personal → personal hh, shared → shared hh, empty plan asks) + mcp-tasks controller test green.
   - [x] **5b — read:** union read. `next-action-suggester` reads through a new `read/TaskReads` helper
     (sibling of finance's `SpendingReads`): default = own (envelope household), and on an explicit
     `scope:"shared"` cut it unions across the member's personal ∪ shared households via
     `ProfileSharingClient.households`. The router threads the `scope` flag from the classifier node onto
     `RouterResult.shared` → `IntentController` → `suggest(msg, shared)`. weekly-review stays per-household
     (a proactive scheduler cron — no user/scope axis). Validated: `TaskReadsTest` (own vs shared set +
     union/cap) + `NextActionSuggesterTest` shared-cut + `IntentRouterTest` scope-parse. **Tasks fully
     retrofitted (write 5a + read 5b).**
6. **Nutrition** (shared meal-plan/shopping surface only; food log stays personal) — split write/read:
   - [x] **6a — write:** the direct basket breakdown (`BasketBreakdown`) routes the saved `basket` to the
     acting member's shared vs personal household via `SharingResolver` + new `sharing/NutritionSharingPolicy`
     (a grocery basket is a household-provisioning act → shared by default, degrading to personal with no
     family household). Only the direct path routes; the IA-b bus fan-out keeps finance's already-resolved
     household; the food log stays personal. mcp-nutrition stays tenant-agnostic. Validated:
     `BasketBreakdownTest` (grocery basket → shared household; no-family → degrade to personal) + full
     nutritionist suite green.
   - [x] **6b — read:** `MealPlanner` reads through a new `read/MealReads` helper (sibling of finance's
     `SpendingReads` / tasks' `TaskReads`): a family-scoped ration ("наш рацион", "на всю семью" — a
     `FAMILY_CUES` keyword in `IntentController`, threaded as a `shared` flag) unions diet profiles + recent
     meals across the member's personal ∪ shared households (`ProfileSharingClient.households`); default =
     own. The `meal-planner` SKILL gained `payload.scope` + `context.householdProfiles` (array). Nutrition's
     routing is a deterministic keyword heuristic (not an LLM classifier), so no golden-routing test applies
     (unlike tasks 5b); the model-dependent extraction stays covered by `GoldenMealLogTest`. Validated:
     `MealPlannerTest` family-union case + full nutritionist suite green. **Nutrition fully retrofitted.**
7. [ ] **Documents** (household vs personal docs) — the remaining domain of slice 6's original pairing.
8. [ ] **(deferred)** reconcile memory/second-brain's owner-tag model onto the sharing primitive.

## Notes

Second ADR in the repo. `SharingContext` and `DefaultSharingPolicy` are new shared contracts — flagged
here (new layer) rather than invented silently. Per the ADR convention, `architecture.md` §Locked
decisions is updated only when this ADR reaches **Accepted**; until then it is the proposal and
architecture.md remains current truth.
