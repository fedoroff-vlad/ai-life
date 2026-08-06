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
7. **Documents** (household vs personal docs) — the remaining domain of slice 6's original pairing.
   Split write/read like calendar/finance/tasks/nutrition. Read default = own (the shared cut is on
   explicit request).
   - [x] **7a — write:** `docs-agent`'s `DocArchiver` routes an archived document to the acting member's
     shared vs personal household via the shared `SharingResolver` wired with a new
     `sharing/DocsSharingPolicy` (warranty/contract → shared — a household asset; receipt/note/ID →
     private; degrades to personal with no family household). The `docType` extracted by `doc-archiver` is
     the signal (carried on `SharingContext.itemKind`, the nutrition sibling of `NutritionSharingPolicy`).
     mcp-docs stays tenant-agnostic; the SB-5 note seed follows the resolved household. Validated:
     `DocArchiverTest` (contract → shared, receipt → personal even with a shared household present).
   - [x] **7b — read:** `doc-finder` unions personal ∪ shared documents on an explicit "наши документы"
     cue (default = own), via a new `read/DocReads` helper (sibling of finance `SpendingReads` / tasks
     `TaskReads` / nutrition `MealReads`) — `households(...)` resolves the set via
     `ProfileSharingClient.households`, `searchUnion(...)` fans the trigram search across it; the
     semantic-recall source fans out over the same set. The family/own scope is a deterministic keyword
     match (`FAMILY_CUES` in `IntentController`, the module's existing cue style), like nutrition 6b — so
     no golden-routing test applies. `DocFinderTest` (own cut unchanged + family cue unions a personal +
     shared-household doc) green. **Documents fully retrofitted → all opt-in domains done; only the
     deferred item 8 remains.**
8. **Memory-driven default-sharing** — graduate the `DefaultSharingPolicy` default from a static
   per-domain rule to one that **learns the owner's past choices** (ADR-0001 item 7). Design +
   slice sequence in **[§Item 8 design](#item-8--memory-driven-default-sharing-design)** below.
   - [x] **DS-0 — docs (this section):** record the design + slice sequence in this ADR + flip STATUS
     to the in-flight track. *(docs)*
   - [x] **DS-1 — store:** memory-service `memory.sharing_decision` tally table + `POST /v1/sharing/decisions`
     (record) + `GET /v1/sharing/policy` (aggregate → learned default + confidence, `204` when unseen) +
     `contracts/sharing`. Deterministic majority (ties → `PRIVATE` at 0.5); no LLM. `SharingDecisionIntegrationTest`
     (5, Testcontainers). The store is domain-agnostic — it tallies opaque `signalKey`s built caller-side in DS-2.
   - [x] **DS-2 — seam + `LearnedSharingPolicy`:** added a default `Mono<SharingScope> decideAsync(ctx,
     learningHousehold)` to `DefaultSharingPolicy` (wraps the sync `decide` — the 5 static policies are
     unchanged; the extra `learningHousehold` param is the per-member tally scope only the resolver knows).
     `SharingResolver` awaits it, and on an **explicit** choice records the owner's decision (the ground-truth
     learn signal) keyed by their **personal** household — best-effort, fire-and-forget. `LearnedSharingPolicy`
     (in `libs/sharing`) decorates a domain's static policy: query the store via the new `SharingLearningClient`
     → trust the learned scope only when `total ≥ 3` **and** `confidence ≥ 0.67`, else delegate to the static
     rule. Both reads/writes soft-fail. No domain wired yet (DS-3). `LearnedSharingPolicyTest` (6) +
     `SharingLearningClientTest` (5) + `SharingResolverTest` learning cases (3); 27 `libs/sharing` tests green.
   - [x] **DS-3 — reference domain (calendar):** calendar-agent's `OutboundHttpConfig` wraps
     `CalendarSharingPolicy` in `LearnedSharingPolicy` + constructs `SharingResolver` with the learning-enabled
     constructor (a new `SharingLearningClient` bean over the shared `memoryServiceWebClient`), so unscoped
     events default to the learned scope once the tally is deep + decisive, else the static occasion rule, and
     explicit choices are recorded. Pure wiring — the routing/learning logic (unit-proven in `libs/sharing`,
     incl. `SharingResolverTest.learnedDefaultFlowsThroughTheResolverAndRoutes`) is unchanged. The 7
     `ActionControllerTest` cases stay green (no-history path = static default, memory pointed at a fast-fail
     address). Calendar stays the canonical example.
   - [x] **DS-4 — remaining opt-in domains** (finance, tasks, nutrition, docs — all wired), one per PR —
     each just wraps its existing static policy the same way calendar did (a `SharingLearningClient` bean
     over the shared `memoryServiceWebClient` + wrap the policy in `LearnedSharingPolicy` + the resolver's
     learning-enabled constructor; point any context test that invokes the resolver at a fast-fail memory URL):
     - [x] **finance** — `finance-agent` `OutboundHttpConfig` wraps `FinanceSharingPolicy`; `AccountManager`'s
       account routing now defaults to the learned scope once the tally is deep + decisive, else the static
       joint-account rule. `AccountManagerTest` (3) + full finance-agent suite (72) green.
     - [x] **tasks** — `tasks-agent` `OutboundHttpConfig` wraps `TasksSharingPolicy`; `TaskCapturer`'s
       capture routing now defaults to the learned scope once the tally is deep + decisive, else the static
       household-task rule. `TaskCapturerTest` (3, fast-fail memory URL) + full tasks-agent suite green.
     - [x] **nutrition** — `nutritionist-agent` `OutboundHttpConfig` wraps `NutritionSharingPolicy`;
       `BasketBreakdown`'s basket routing now defaults to the learned scope once the tally is deep +
       decisive, else the static grocery-basket rule. `BasketBreakdownTest` (fast-fail memory URL) + full
       nutritionist suite green.
     - [x] **docs** — `docs-agent` `OutboundHttpConfig` wraps `DocsSharingPolicy`; `DocArchiver`'s archive
       routing now defaults to the learned scope once the tally is deep + decisive, else the static doc-type
       rule. `DocArchiverTest` (fast-fail memory URL) + full docs-agent suite green. **All opt-in domains
       wired — DS-4 complete.**
   - **DS-N — confirm-on-ambiguity** (**not** hardware/infra-blocked — the conversation-state confirm loop it
     reuses is already built and in use by tasks-agent `inbox-clarify` / notes-agent `ambient-approve` /
     memory-service AC-4; it is *sequenced last* only because it is the largest, cross-cutting item 8 slice, not
     because a dependency is missing). Design + sub-slices in **[§DS-N design](#ds-n--confirm-on-ambiguity-design)**
     below. When neither the tally nor the static rule is confident, defer the write, ask the owner once
     ("«X» — личное или общее?") via a conversation pending-action, and finish + learn from the reply.
     - [x] **DS-N-0 — design (this section):** record the resolver-contract change (a third `needsConfirm`
       outcome), the abstain seam on `DefaultSharingPolicy`, the confirm→resume→learn shape, and the sub-slices.
       *(docs)*
     - [x] **DS-N-1a — engine (`libs/sharing`, no domain wired):** the abstain seam (`DefaultSharingPolicy.
       maybeDecide` may be empty → `decideAsync` completes empty; the default stays always-confident, so the 5
       static policies are unchanged) + a `SharingResolution` sealed outcome (`Resolved` / `NeedsConfirm`) +
       `SharingResolver.resolve(...)` (ask-aware) / `confirm(...)` (finish + record the reply).
       `resolveHousehold(...)` now delegates to `resolve` and collapses `NeedsConfirm` to the fallback, so every
       existing caller is unchanged (full reactor compiles; calendar `ActionControllerTest` + finance
       `AccountManagerTest` green). `LearnedSharingPolicy` propagates the wrapped policy's abstain on a
       tally-miss. 36 `libs/sharing` tests (9 new: NeedsConfirm/confirm/collapse + abstain propagation).
     - [ ] **DS-N-1b — reference domain (calendar):** wire calendar end-to-end — `ActionController` calls
       `resolve`, defers on `NeedsConfirm` (stash draft + routing in a `pendingAction`, `PUT` the
       conversation-state lock, ask "личное или общее?"), and a `/resume` calls `confirm` to finish + learn.
       Confirm→resume→learn test. `CalendarSharingPolicy` overrides `maybeDecide` to abstain on its ambiguous case.
     - [ ] **DS-N-2…N — retrofit finance / tasks / nutrition / docs**, one per PR: each makes its policy abstain
       on its genuinely-ambiguous case + defers/resumes in its write flow.
   - [ ] **(still deferred, separate)** reconcile memory/second-brain's older owner-tag model onto this
     primitive — orthogonal to the learned default; note when a consumer needs it.

## Item 8 — memory-driven default-sharing (design)

**Goal.** Today each domain's `DefaultSharingPolicy` plugs a *static* deterministic rule (calendar:
occasion → shared; finance: joint-account → shared; …). Item 8 makes the *default-when-unspecified* learn
the owner's actual past choices, so the system stops guessing from a hand-written rule and starts matching
what the owner has repeatedly done — **confirming only when it genuinely can't tell**.

**Invariant kept.** The *mechanism* stays deterministic — the household pick, the fallbacks, and the
privacy boundary in `SharingResolver` never change. "Learning" here is a **deterministic majority vote over
a structured tally**, not probabilistic/LLM inference: a privacy boundary must be predictable and auditable.
Only *which scope the unspecified default resolves to* becomes data-driven, and only behind the existing
`DefaultSharingPolicy` seam.

**Store (DS-1).** A structured tally on memory-service, not the fuzzy recall tier:

```
memory.sharing_decision(household_id, domain, signal_key, scope, count, first_seen, last_seen)
  signal_key = a stable, low-cardinality digest of SharingContext (sorted categories + itemKind +
               involvesHouseholdMember) — the same shape always maps to the same key.
```
- `POST /v1/sharing/decisions {householdId, domain, signalKey, scope}` — upsert-increment the tally.
- `GET /v1/sharing/policy?householdId&domain&signalKey` → `{scope, confidence}` where confidence =
  winning_count / total for that key (204/empty when the key is unseen). The caller compares against a
  threshold; the store itself makes no policy call.

**Seam (DS-2, as built).** `DefaultSharingPolicy` gains a **default** method `Mono<SharingScope>
decideAsync(SharingContext ctx, UUID learningHousehold)` whose default impl is `Mono.just(decide(ctx))`, so
the five static policies compile unchanged. The second parameter is the acting member's **personal
household** — the stable per-member key the tally is scoped by. It has to be a call-time argument (not baked
into a bean, not in `SharingContext`) because it is per-request and only the resolver knows it, from the
routing read; learning is inherently per-actor, whereas the sync `decide(ctx)` was deliberately actor-blind.
`SharingResolver.resolveHousehold` awaits `decideAsync`, and on an **explicit** choice records that
decision — the **owner's ground truth**, the signal worth learning — keyed by their personal household so
SHARED and PRIVATE choices for the same signal profile land in one tally instead of being split across the
households they route to. Recording is best-effort/fire-and-forget. A `LearnedSharingPolicy` decorator
(constructed with the domain's static policy + a `SharingLearningClient` + the domain name) overrides
`decideAsync`: it looks up the tally for `ctx.signalKey()` scoped to `learningHousehold` and returns the
learned scope only when the tally is both deep (`total ≥ 3`) and decisive (`confidence ≥ 0.67`), else
delegates to the wrapped static policy — so learning can only sharpen the default toward a demonstrated
habit, never flip it on weak evidence. No domain policy or the resolver's household-pick mechanism is
rewritten — exactly the seam the Decision section promised.

**Wiring (DS-3+).** A domain opts into learning by wrapping its static policy bean in `LearnedSharingPolicy`
when constructing its `SharingResolver` (a one-line change in the agent's `OutboundHttpConfig`) — mirroring
how each domain already wires the resolver. calendar is retrofitted first as the reference.

**Confirm-on-ambiguity (DS-N, deferred — the learn/infer/*confirm* loop).** DS-4 closed the learn+infer
half: on a non-explicit item the resolver returns the learned scope when the tally is deep + decisive, else
the static rule — but it always returns *something silently*. DS-N adds the third branch: when the system
genuinely can't tell, **ask once instead of guessing**, and record the reply (which immediately improves the
tally). It is **not** blocked — see [§DS-N design](#ds-n--confirm-on-ambiguity-design).

## DS-N — confirm-on-ambiguity (design)

**Not blocked, just larger.** The confirm loop DS-N needs is the same `core.conversation_state` route-lock +
pending-action already shipped and in production use (tasks-agent `inbox-clarify`, notes-agent
`ambient-approve`, memory-service AC-4's out-of-band "заметил: … — записать?"). DS-N is sequenced last
because it is a **cross-cutting** slice (it changes the resolver's contract and threads a defer→resume through
every domain's write flow), not because a dependency is missing.

**Invariant kept (again).** The *mechanism* stays deterministic. DS-N only decides *when to stop guessing and
ask*; the household pick and the privacy boundary are unchanged. The reply the owner gives is an **explicit**
choice — it flows through the exact record-on-explicit path DS-2 already built, so a confirmed answer both
finishes the current write and sharpens the tally for next time.

**1. What "ambiguous" means (the trigger).** The non-explicit path is ambiguous when **both** halves are
unsure:
- the **tally** is not confident — `LearnedSharingPolicy` would fall through (total < `MIN_SAMPLES` **or**
  confidence < `MIN_CONFIDENCE`), **and**
- the **static policy itself** is unsure for this context — e.g. a doc with an untyped/unknown `docType`, a
  task with no household-context signal, an account with no joint/personal cue. A policy that *is* sure (a
  clear "contract", an obvious joint account) never asks — it just routes, exactly as today.

The second half needs the seam to express "I'm not sure" rather than always returning a scope (see §2).

**2. Seam change — an abstain signal on `DefaultSharingPolicy`.** Today `decideAsync` returns
`Mono<SharingScope>` — it must always answer. DS-N lets a policy *abstain*. Minimal, backward-compatible
shape: `decideAsync` may complete **empty** (`Mono.empty()`) to mean "abstain — I can't confidently default
this". The current default impl (`Mono.just(decide(ctx))`) still always answers, so the 5 static policies stay
unchanged unless a domain **opts in** by overriding to abstain on its genuinely-ambiguous case.
`LearnedSharingPolicy` abstains only when the tally is unconfident *and* the wrapped static policy abstains
(so a confident static rule still wins on a cold tally — no regression from DS-4).

**3. Resolver contract — a third outcome.** `resolveHousehold` returns `Mono<UUID>` (a resolved household),
which has no way to say "ask first". DS-N introduces a sealed `SharingResolution`:
`Resolved(UUID household)` | `NeedsConfirm(SharingContext ctx, UUID personalHousehold, UUID fallbackHousehold)`.
A new `resolve(userId, explicitScope, ctx, fallback) → Mono<SharingResolution>` returns it; the existing
`resolveHousehold(...) → Mono<UUID>` is kept for callers that don't want to ask (it collapses `NeedsConfirm`
to the fallback/static pick), so **no existing caller is forced to change** — a domain opts into asking by
switching to `resolve(...)`.

**4. Confirm → resume → learn (per domain, reuses conversation-service).** On `NeedsConfirm` the write flow
does **not** write:
1. it stashes the in-progress item (draft) + the `SharingContext` + `personalHousehold` in a `pendingAction`,
   `PUT`s a `core.conversation_state` lock (`routeLock=<agent>`), and replies "«X» — это личное или общее?"
   (quick-reply / buttons, per [[feedback_ask_approval_via_popup]]);
2. the orchestrator sees the lock and routes the owner's next message straight to the agent's `/resume`
   (no re-classification — the primitive already does this);
3. `/resume` parses личное/общее → an explicit `SharingScope`, finishes the deferred write under the picked
   household, and — because the reply is an *explicit* choice — records it to the tally via the DS-2
   record-on-explicit path. It then `DELETE`s the lock. A forgotten confirm ages out by TTL (30 min) and the
   next message classifies normally.

This is structurally identical to the `inbox-clarify` confirm the tasks-agent already runs.

**5. Sub-slices (each own PR, ≤5 files where possible).**
- **DS-N-0 (this design).** *(docs)*
- **DS-N-1 — engine + calendar reference.** `SharingResolution` + the abstain seam in `libs/sharing`
  (default always-confident → static policies untouched) + calendar wired end-to-end (`ActionController`
  defers on `NeedsConfirm`, sets the lock, gains a `/resume`). Unit + one confirm→resume→learn test. Calendar
  stays the canonical example.
- **DS-N-2…N — retrofit finance / tasks / nutrition / docs**, one per PR: each overrides its policy to abstain
  on its genuinely-ambiguous case and defers/resumes in its write flow (`AccountManager` / `TaskCapturer` /
  `BasketBreakdown` / `DocArchiver`).

**6. Cost / benefit — build DS-N-1, then reassess.** DS-N is the most expensive item 8 slice for the marginal
benefit: DS-4's shipped behaviour (learn when confident, else a sane static default) is already reasonable.
DS-N's specific value is avoiding a **silent wrong** on a *privacy boundary* — a private doc quietly shared
with the spouse, or a joint expense hidden from them — by asking instead of guessing. Recommendation: build
**DS-N-1** (engine + calendar reference) and let the confirm prove out on one domain, then decide from real use
whether the full retrofit (DS-N-2…N) earns its keep before spending it. Do **not** batch all domains up front.

## Notes

Second ADR in the repo. `SharingContext` and `DefaultSharingPolicy` are new shared contracts — flagged
here (new layer) rather than invented silently. Per the ADR convention, `architecture.md` §Locked
decisions is updated only when this ADR reaches **Accepted**; until then it is the proposal and
architecture.md remains current truth.
