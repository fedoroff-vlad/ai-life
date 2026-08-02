# libs/sharing

**Status (2026-08-02):** engine shipped (ADR-0002 slice 2); **calendar fully wired as the reference impl** —
write path (slice 3a, `calendar-agent`) + read path (slice 3b, `calendar-web`). **finance fully retrofitted
(read 4a + write 4b):** the read side unions spending across the member's personal ∪ shared households on the
shared-scope ("наши траты") cut — `FinancialAdvisor` (4a-i) + `MonthlyReporter`/`YearReporter` (4a-ii), all
through the domain's `read/SpendingReads` helper over `ProfileSharingClient.households`; the write side
(slice 4b) routes a chat-created account to a personal vs shared household via `SharingResolver` +
`sharing/FinanceSharingPolicy` (`AccountManager` flow). **tasks write path wired (slice 5a):** `tasks-agent`
routes a chat-captured task to a personal vs shared household via `SharingResolver` + `sharing/TasksSharingPolicy`
(`TaskCapturer` flow). tasks read (5b) + nutrition/docs next.

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
personal vs shared household through it. **tasks** consumes the write path (slice 5a): `tasks-agent`'s
`OutboundHttpConfig` declares the `ProfileSharingClient` + `SharingResolver` beans (the resolver wired with
`sharing/TasksSharingPolicy`), and the `TaskCapturer` flow routes a chat-captured task to a personal vs shared
household through it (read path 5b next).

## Depends on
`libs/contracts` (`SharingScope` in `contracts/common`, `HouseholdRoutingDto` in `contracts/profile`) +
`spring-webflux` (WebClient). Nothing else — no persistence, no LLM, no Spring Boot auto-config.

## Key classes
- `SharingResolver` — the deterministic **write-path** engine. `resolveHousehold(userId, explicitScope,
  ctx, fallbackHousehold) → Mono<UUID>`: explicit choice wins → else `policy.decide(ctx)` →
  household-routing → pick personal / first-shared → fallbacks (no `userId` or profile-404 →
  `fallbackHousehold`; SHARED with no family → personal). The single home of the rule set formerly inline
  in calendar-agent.
- `DefaultSharingPolicy` — the **per-domain seam** (`@FunctionalInterface`): `SharingScope decide(ctx)`.
  Implemented once per domain in that domain's `sharing/` package. `privateByDefault()` is the safe stub.
- `SharingContext` — the neutral signals a policy reads: `categories` (never null), `involvesHouseholdMember`,
  `itemKind`. `ofCategories(...)` + `hasCategory(tag)` helpers. Evolving shared contract — add a field only
  when a new domain genuinely needs one.
- `ProfileSharingClient` — the one thin identity read, shared by every consumer:
  `householdRouting(userId)` (write split, empty on 4xx) + `households(userId)` (read union, empty list on
  any error). Collapses `agent-runtime`'s `ProfileClient.householdRouting` and calendar-web's
  `ProfileHouseholdsClient` into one. The consumer owns the base-URL-bound `WebClient`.

## Adding sharing to a domain
See `plans/PATTERNS.md` → "add sharing to a domain" (calendar is the canonical example once slice 3 lands).
In short: a `<Domain>SharingPolicy implements DefaultSharingPolicy` in the agent's `sharing/` package, wire
`SharingResolver` with it on create, and read the union via `ProfileSharingClient.households`.
