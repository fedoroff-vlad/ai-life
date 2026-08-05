# libs/sharing

**Status (2026-08-05):** engine shipped (ADR-0002 slice 2); **all opt-in domains retrofitted** — calendar
(reference: write 3a `calendar-agent` + read 3b `calendar-web`), finance (4a/4b), tasks (5a/5b), nutrition
(6a/6b), docs (7a/7b) — each splitting a write (route a create to personal vs shared household via
`SharingResolver` + a `sharing/<Domain>SharingPolicy`) and a read (union own ∪ shared on an explicit family
cut via `ProfileSharingClient.households`, default own). Per-domain detail: [ADR-0002](../../plans/adr/ADR-0002-sharing-shared-capability.md)
§Action Items + [HISTORY](../../plans/HISTORY.md). **Item 8 — memory-driven default: DS-0…DS-4 COMPLETE
(2026-08-05).** The per-domain `DefaultSharingPolicy` default is now *learned* from the owner's past explicit
choices (`LearnedSharingPolicy` + `SharingLearningClient` over DS-1's `memory.sharing_decision` tally), behind
the same seam; still deterministic (majority vote, never LLM). **Every opt-in agent** (calendar DS-3, finance/
tasks/nutrition/docs DS-4) now wraps its static policy in `LearnedSharingPolicy` + builds `SharingResolver`
with the learning-enabled constructor (a `SharingLearningClient` bean over its shared `memoryServiceWebClient`)
— one-line-per-domain wiring in each `OutboundHttpConfig`. Only **DS-N** (confirm-on-ambiguity) remains,
designed & deferred → [ADR §DS-N](../../plans/adr/ADR-0002-sharing-shared-capability.md#ds-n--confirm-on-ambiguity-design).

The reusable **personal-vs-shared privacy capability**. One engine + N thin per-domain policies, so every
domain gets "own vs shared" without copy-paste (the silent-drift failure ADR-0002 exists to stop).
Generalises the tenant-routing that ADR-0001 first built inline in calendar.

## Purpose
Given an item a user is creating, resolve **which household it is written to** (its visibility boundary),
and give read paths the **household set** to read "own + shared" across. The *mechanism* is deterministic —
a privacy boundary, never LLM-decided. Only the *default-when-unspecified* is judgement, and it lives
behind a per-domain seam that can later be memory-driven (ADR-0001 item 7) without touching the engine.

## Who uses me
Both **domain agents** (write path) **and** read-only **web services** (read path) — the module is a light
leaf (`contracts` + WebClient, no LLM / no agent-runtime) precisely so web services can depend on it
without pulling the agent runtime. **calendar** is the reference: `calendar-agent` (write path — slice 3a:
`sharing/CalendarSharingPolicy` + `SharingResolver`/`ProfileSharingClient` beans in its `OutboundHttpConfig`);
`calendar-web` (read path — slice 3b: `ProfileSharingClient.households` via `config/SharingConfig`, retiring
its former local `ProfileHouseholdsClient`). **finance** consumes both paths: the read side (slice 4a) —
`finance-agent` declares the `ProfileSharingClient` bean in its `OutboundHttpConfig`, and its `read/SpendingReads`
helper reads the union for the shared-scope analysis (`FinancialAdvisor`) and reports
(`MonthlyReporter`/`YearReporter`); the write side (slice 4b) — the same `OutboundHttpConfig` declares the
`SharingResolver` bean wired with `sharing/FinanceSharingPolicy`, and `AccountManager` routes a new account to a
personal vs shared household through it. **tasks** consumes both paths: the write path (slice 5a) —
`tasks-agent`'s `OutboundHttpConfig` declares the `ProfileSharingClient` + `SharingResolver` beans (the
resolver wired with `sharing/TasksSharingPolicy`), and the `TaskCapturer` flow routes a chat-captured task to
a personal vs shared household through it; the read path (slice 5b) — its `read/TaskReads` helper reads the
union for the shared cut in `next-action-suggester` over `ProfileSharingClient.households`. **nutrition**
consumes both paths: write (slice 6a) — `nutritionist-agent`'s `OutboundHttpConfig` wires `SharingResolver`
with `sharing/NutritionSharingPolicy`, and `BasketBreakdown` routes a saved grocery basket through it; read
(slice 6b) — its `read/MealReads` helper unions across households for a family-scoped ration. **docs** consumes
both paths: write (slice 7a) — `docs-agent`'s `OutboundHttpConfig` wires `SharingResolver` with
`sharing/DocsSharingPolicy`, and `DocArchiver` routes an archived document through it; read (slice 7b) — its
`read/DocReads` helper unions the search across households on a family cue. **Item 8:** every one of these
agents additionally wraps its static policy in `LearnedSharingPolicy` and builds `SharingResolver` with the
learning-enabled constructor (a `SharingLearningClient` bean over its `memoryServiceWebClient`), so the
unspecified default is the owner's learned choice once the tally is deep + decisive.

## Depends on
`libs/contracts` (`SharingScope` in `contracts/common`, `HouseholdRoutingDto` in `contracts/profile`) +
`spring-webflux` (WebClient). Nothing else — no persistence, no LLM, no Spring Boot auto-config.

## Key classes
- `SharingResolver` — the deterministic **write-path** engine. `resolveHousehold(userId, explicitScope,
  ctx, fallbackHousehold) → Mono<UUID>`: explicit choice wins → else `policy.decideAsync(ctx, personalHh)` →
  household-routing → pick personal / first-shared → fallbacks (no `userId` or profile-404 →
  `fallbackHousehold`; SHARED with no family → personal). The single home of the rule set formerly inline
  in calendar-agent. Item 8: a second constructor takes a `SharingLearningClient` + domain — then it records
  the owner's **explicit** choice (keyed by their personal household) as the learn signal, best-effort; the
  two-arg constructor leaves learning off (unchanged behaviour).
- `DefaultSharingPolicy` — the **per-domain seam** (`@FunctionalInterface`): `SharingScope decide(ctx)`.
  Implemented once per domain in that domain's `sharing/` package. `privateByDefault()` is the safe stub.
  Item 8: also a `default Mono<SharingScope> decideAsync(ctx, learningHousehold)` (wraps `decide`) — the async
  form the resolver awaits so a learned policy can do an I/O read; static policies inherit the default unchanged.
- `SharingContext` — the neutral signals a policy reads: `categories` (never null), `involvesHouseholdMember`,
  `itemKind`. `ofCategories(...)` + `hasCategory(tag)` helpers. `signalKey()` is the stable, low-cardinality
  digest (sorted categories + `itemKind` + `involvesHouseholdMember`) the learned-decision tally is grouped by.
  Evolving shared contract — add a field only when a new domain genuinely needs one.
- `LearnedSharingPolicy` — **memory-driven default (item 8)**: a decorator over a domain's static policy that,
  for an unscoped item, prefers what the owner has done before (a deterministic majority vote in DS-1's tally)
  over the static rule — but only when the tally is deep (`total ≥ 3`) and decisive (`confidence ≥ 0.67`); else
  it delegates to the static rule. Sync `decide` still returns the static rule.
- `SharingLearningClient` — the thin read/write over memory-service's `/v1/sharing/*` tally (DS-1): `record`
  (the resolver logs the owner's explicit choice) + `policy` (the decorator reads the learned default). Both
  best-effort — learning never delays or fails a create. The consumer owns the memory-service-bound `WebClient`.
- `ProfileSharingClient` — the one thin identity read, shared by every consumer:
  `householdRouting(userId)` (write split, empty on 4xx) + `households(userId)` (read union, empty list on
  any error). Collapses `agent-runtime`'s `ProfileClient.householdRouting` and calendar-web's
  `ProfileHouseholdsClient` into one. The consumer owns the base-URL-bound `WebClient`.

## Adding sharing to a domain
See `plans/PATTERNS.md` → "add sharing to a domain" (calendar is the canonical example once slice 3 lands).
In short: a `<Domain>SharingPolicy implements DefaultSharingPolicy` in the agent's `sharing/` package, wire
`SharingResolver` with it on create, and read the union via `ProfileSharingClient.households`.
