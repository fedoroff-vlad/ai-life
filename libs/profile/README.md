# libs/profile

**Status (ADR-0005 slice 2 — foundation):** the shared mechanism shipped; **no domain wired yet** (slice 3
retrofits briefing as the reference, then creator/nutrition/travel/stylist). Spec →
[ADR-0005](../../plans/adr/ADR-0005-personalization-profile-capability.md).

The reusable **per-member personalization-profile capability**. Five domains (briefing / creator /
nutrition / travel / stylist) each let a member state their own preferences in chat and store them as a
per-member `(household_id, owner_id)` row, and each re-implemented the same **write scope**, **read
resolution**, and **`/internal/<domain>-profile` client** by hand. This module is those three, lifted once —
one resolver + one client shape + N thin per-domain adapters — the same "shared mechanism, domain-owned
data" move `libs/sharing` made for privacy (ADR-0002). The profile **store stays per-domain** (heterogeneous
jsonb, read on the domain's hot path); only the *mechanism* is shared.

## Purpose
Given a member configuring or reading a per-member profile, own the two repeated rules so no domain copies
them: the **write scope** (`self → the speaker`, `household → the shared default`) and the **read
resolution** (`self → own household-default → family/shared household-default → empty`). The family-default
step (#490 FO-3) means a new member who set nothing inherits the family's shared default — now free for all
five domains, not just briefing.

## Who uses me
The five personalization **domain agents** (write via the `PersonalizationProfiler` template in
`agent-runtime` + read via `ProfileScopeResolver`) and any **read service** that resolves a per-member
profile — the module is a light leaf (`contracts` + `libs/sharing` + WebClient, no LLM / no agent-runtime)
so a read service can depend on it without pulling the agent runtime, exactly as `libs/sharing` is split.
`agent-runtime` depends on this leaf for `ProfileScope` (its `PersonalizationProfiler` template resolves the
write owner through it). *No consumer is wired yet — slice 3 makes briefing the reference.*

## Depends on
`libs/contracts` (`HouseholdRoutingDto` in `contracts/profile`) + `libs/sharing` (`ProfileSharingClient` —
the one shared identity read, reused rather than re-wrapped) + `spring-webflux` (WebClient). No persistence,
no LLM, no Spring Boot auto-config.

## Key classes
- `ProfileScope` — the **write-scope** helper: `ownerId(scope, userId)` = `household ? null : userId`, and
  `isHousehold(scope)`. The single home of the "self → the sender; household → the default" rule the five
  profilers copy-pasted. The scope token (`"self"` / `"household"`) is what a `*-profiler` SKILL emits.
- `ProfileScopeResolver` — the **read-resolution** engine (generalized #490 FO-3): `resolve(userId,
  householdId, fetch)` applies `self → own household-default → family (shared) household-default → empty`,
  where `fetch` is the domain's typed `(householdId, ownerId) -> Mono<T>` read. The family step reuses
  `libs/sharing`'s `ProfileSharingClient.householdRouting` (one identity client, two capabilities). Stateless
  — one shared bean serves every domain; errors soft-fail to empty so a caller's downstream default applies.
- `PersonalizationProfileClient<D, I>` — the generic typed `/internal/<path>` client shape shared by all
  five: `get(householdId, ownerId)` (404 → empty, 10s timeout) + `set(input)` upsert. The consumer owns the
  base-URL-bound `WebClient`.

The **profiler flow template** (`extract → parse → scope → write`) lives in
`libs/agent-runtime` (`agentruntime/profile/PersonalizationProfiler` + `ProfileSpec`), not here — it needs
`libs/llm-client` + the `SkillRegistry`, the same reason `SkillRouter` / `PickConfirmActRunner` live there.

## Adding a personalization profile to a domain
See `plans/PATTERNS.md` → "add a personalization profile to a domain" (briefing is the canonical example
once slice 3 lands). In short: a per-domain `profile/` package with the typed `PersonalizationProfileClient`
binding + a `ProfileSpec` (SKILL name, `SetInput` builder, reply wording); read via `ProfileScopeResolver`.
