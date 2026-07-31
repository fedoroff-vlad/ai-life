# ADR-0001: Identity, membership, and per-item scope (multi-tenant workspace model)

**Status:** Accepted (2026-07-31 — Option B; implementation plan owner-approved)
**Date:** 2026-07-31
**Deciders:** repo owner (holder/admin)
**Drives:** #295 (per-person ICS feed content filtering) — the concrete use-case that surfaced this gap.

## Context

Today the identity model is a straight 1:1 chain (locked in
[architecture.md](../architecture.md) §Locked decisions):

```
telegram_user_id → user_id → household_id → scope (private | household | group)
```

Two entity kinds coexist deliberately:
- **`core.users`** — operators (bot users). Carry `telegram_user_id`, belong to one `household_id`.
- **`core.people`** — *contacts* (family/friends notes are written **about**: birthdays, gifts,
  greetings). No `user_id` link; "distinct from core.users" ([003-people.yml](../../infra/liquibase/features/003-people.yml)).

`scope = user:<id> | household:<id> | agent:<name>` is already the privacy primitive — every
**memory** record carries it (architecture.md §Locked decisions, line "Memory: hybrid; every record
has scope …"). But it has **not** been extended to other domains: `calendar.events_cache` carries only
`person_id` (a *contact* tag — "who it's about"), **no owner/scope** ("who may see it").

This blocks a coherent answer to #295 ("each member subscribes to just their relevant events — own vs
shared") and to the owner's broader intent, articulated in the design chat:

1. Each user has a **private** space (notes, events, birthdays) that stays private.
2. A **shared/family** space exists; joining it is **gated by the holder** (a notification on new
   registration → the holder decides who is added and how they relate).
3. Joining a shared space is **not a 100% merge** — private items stay private; only shared items
   surface to the group.
4. A **separate tenant** (a friend who registers) lives in their own space with **zero** cross-access.
5. A contact can **become** an operator over time ("the daughter grows up and starts using the bot").
6. Admin access, if any, is under a **separate** identity — not blended into a personal space.

The current 1:1 `user → household` cannot express (1)+(3)+(4) together: a user needs a private space
**and** optional membership in a shared space, with the two not merging.

### Forces / constraints

- **Small scale.** A family deployment (2–5 members), not a SaaS. Correctness and simplicity beat
  horizontal scale. Multi-tenancy is `household_id`, not per-user schemas (architecture.md).
- **Refines a locked decision.** The 1:1 `→ household_id` chain is locked; this ADR proposes making it
  1:N. That is exactly why it is written down and signed off rather than coded silently.
- **Reuse the existing `scope` primitive** (memory already uses it) instead of inventing a second
  privacy mechanism.
- **Binary privacy is enough** (owner-confirmed): `user:<id>` (private) vs `household:<id>` (shared).
  No sub-groups ("me + wife but not kids") for now — a separate need would get a separate household.

## Decision

Adopt the well-trodden **multi-tenant workspace / membership** model (Slack / Notion / Google
Workspace / Linear): a user owns a private space and can be an approved **member** of one or more
shared spaces; every item is scoped to either a user or a space.

Concretely:

1. **User = identity.** `telegram_user_id → user_id`. A user's private scope (`user:<id>`) is theirs
   forever, independent of any membership.
2. **Every user gets a personal `household` on registration** — an isolated single-member tenant.
3. **Membership becomes many-to-many.** New table
   `core.household_members(household_id, user_id, role, relationship, joined_at)`. A user is a member
   of their personal household + optionally of shared (family) households they were approved into.
   This is the mechanism behind "merge is not 100%": joining a shared household **does not move**
   personal items — they stay in the personal household, invisible to other members.
4. **Item scope = the item's `household_id` (tenant routing).** An item lives in **exactly one**
   household: private → the author's **personal** household; shared → the **family** household. Reading
   = the union over the caller's memberships. No per-item `user:` tag is needed — the household *is* the
   visibility boundary, which is also what isolates a friend (his own household) with zero special
   logic. **Reuse finding:** `memory.memories` already implements the "own vs shared" *read filter* as
   `WHERE household_id=? AND (user_id IS NULL OR user_id=?)` (an **owner-tag** model inside one
   household — `platform/memory-service/.../domain/MemoryRepository.java`). New work (calendar, tasks)
   adopts **tenant routing** because only it delivers "personal-space-first + friend-fully-isolated +
   partial-merge". Memory is **left as-is** and reconciled later (tech-debt), not retrofitted here.
5. **Invite-only onboarding (owner's A — deep-link token).** The **owner is the admin**; there is no
   separate admin surface. A Telegram bot cannot DM a user first, so onboarding is a **deep-link invite
   token**: owner mints an invite (pre-tagging relationship + share-access + target family household) →
   `t.me/<bot>?start=<token>` → forwards it out-of-band → invitee opens it, `/start` carries the token →
   a new user + **personal** household is created and bound to the pending invite → the holder is
   **notified** on join, which inserts the `household_members(family, invitee, relationship)` row. A
   user who registers **without** an invite (or is declined) simply keeps their own personal household —
   **full isolation, no special logic**. M:N already leaves room for a friend to run *their own* shared
   space later; nothing extra is built for that now.
6. **`person → user` optional link.** Add nullable `core.people.user_id`. A contact may *become* an
   operator; prior notes/events about them keep their existing scope, and the now-user gains a private
   space. Not required for #295 — added when the "contact becomes a user" path is first exercised.
7. **Default-sharing policy (owner's D) — smart default + override + learn**, not "ask every time".
   Grounded in family-calendar best practice (Google/Apple: *avoid all-or-nothing; default new personal
   items private; keep a dedicated shared surface; per-item override*):

   | Item type | Default household | Rationale |
   |---|---|---|
   | Birthdays / anniversaries / occasions | **family (shared)** | inherently household-relevant |
   | Personal tasks, personal meetings/events | **personal (private)** | avoid oversharing |
   | Item explicitly involving a household member | propose **family**, confirm only if ambiguous | content inference |
   | Everything else | **personal (private)** | safe default |

   - content inference proposes `family` and confirms **only** on ambiguous cases (don't nag);
   - learn the owner's choices over time (reuses the ambient-capture tiering: explicit→auto,
     important→approve, trivial→ignore — [ambient-capture.md](../ambient-capture.md));
   - a manual `private/shared` flag always overrides.

## Options Considered

### Option A: Scope-only, keep 1 user → 1 household
Privacy purely via `user:<id>` scope inside a single shared household; a friend = a different
household.

| Dimension | Assessment |
|-----------|------------|
| Complexity | **Low** — no members table, no schema churn beyond an event scope column |
| Cost | Low |
| Flexibility | **Low** — a user can be in only one shared space; onboarding = *move* a user between households (orphans their prior personal items unless everything is `user:<id>`-scoped) |
| Fit to intent | Partial — gives "not 100% merge" for one family, but the "personal household exists first, then is *added* to the shared one" flow (owner B) forces M:N anyway the moment a user needs both |

**Pros:** minimal change; enough for a 2-person family today.
**Cons:** breaks down exactly at the owner's stated flow (personal space first → approved into shared → both coexist); "move on approve" is lossy and awkward.

### Option B: Multi-tenant workspace / M:N membership (**recommended**)
Personal household per user + M:N membership into shared households + per-item `user|household` scope +
admin-gated join.

| Dimension | Assessment |
|-----------|------------|
| Complexity | **Medium** — one join table, an onboarding flow, an event scope column |
| Cost | Low (small N; indexed joins) |
| Flexibility | **High** — a user can be in personal + N shared spaces; friend isolation is free (just don't add them) |
| Fit to intent | **Full** — covers wife, daughter-grows-up, Mama-with-private-space, friend, admin-under-separate-account |
| Team familiarity | High — the standard workspace pattern; well-documented practices |

**Pros:** directly expresses "not 100% merge"; onboarding is *add a membership* (non-lossy); reuses the
locked `scope` primitive; forward-compatible with the `person→user` link.
**Cons:** refines a locked 1:1 decision (needs this ADR); touches core identity + several domains
(sequence carefully in slices).

### Option C: Merge `person` and `user` into one "individual" entity with roles
A single `core.individuals` table with `is_operator` / `is_contact` flags.

**Pros:** one identity to reason about; the "contact becomes user" case is a flag flip.
**Cons:** **rejected** — contacts and operators have very different lifecycles and cardinality (many
passive contacts vs a few operators); merging breaks the locked deliberate separation and is a large
refactor for little gain. The optional `people.user_id` link (Option B, item 6) captures the one real
overlap without the merge.

## Trade-off Analysis

The decisive force is the owner's **"merge is not 100%"** + **"personal space exists before joining a
shared one."** Option A can fake privacy inside one household via `user:<id>` scope, but cannot model a
user who owns a private space **and** is a member of a shared space without becoming M:N in practice —
so A collapses into B the first time the real onboarding flow runs. Option C over-unifies against a
locked, deliberate separation. Option B is the standard, well-understood answer and reuses the existing
`scope` primitive rather than inventing a parallel one. The cost (a join table + an onboarding flow +
an event scope column) is proportionate and lands in independent slices.

## Consequences

**Easier:**
- Per-member ICS feeds (#295) become "events in the caller's household set (personal ∪ shared)" — a
  clean read filter, no contact/user confusion.
- New members, friends-as-separate-tenants, and "contact becomes user" all fall out of one model.
- Privacy is one primitive (household membership) across calendar and later tasks; memory's owner-tag
  model is reconciled to it later.

**Harder / to revisit:**
- The locked `telegram_user_id → user_id → household_id` (1:1) becomes 1:N — **architecture.md
  §Locked decisions is updated in the accepting PR** (this one).
- Every household-scoped read must go through membership (`household_members`) instead of a single
  `users.household_id`. Existing queries that assume 1:1 need an audit.
- Default-sharing inference is a policy surface that will need tuning (start conservative: default
  private for meetings/tasks, shared for occasions; confirm on ambiguity).
- `group_chat` scope and the "admin under a separate account" idea are **out of scope here** — noted
  for a later ADR.

## Action Items (slice sequence — each its own PR, ≤5 files)

1. [x] **Accept this ADR**; update [architecture.md](../architecture.md) §Locked decisions (1:1 →
   1:N membership) + [core.md](../core.md) + register in [INDEX.md](../INDEX.md). *(this PR)*
2. [x] **Membership schema + backfill:** `core.household_members` migration
   ([013-household-members.yml](../../infra/liquibase/features/013-household-members.yml)); every
   existing user is backfilled as a member of their current household, `users.household_id` kept as the
   read-through default during transition. profile-service reads memberships via
   `GET /v1/users/{id}/households` (and inserts a self-membership on user creation). *(slice 2)*
3. [x] **Onboarding flow:** new-registration → personal household (slice 3); owner mints a deep-link
   invite `/invite <name> as <relationship>` → `POST /v1/invites` (slice 4a store + 4b-ii gateway
   command, PR#381); invitee opens `/start <token>` → redeem inserts the `household_members` row +
   the holder is pinged on join (slice 4b-i, PR#379). Handled at the gateway level, not via a domain
   skill.
4. [x] **Per-item scope on calendar (tenant routing):** `CreateEventInput` gained a
   `SharingScope{PRIVATE,SHARED}` choice; calendar-agent's `create_event` resolves it (explicit, else
   the default-sharing policy — occasion categories → shared, everything else → private) against the new
   profile-service `GET /v1/users/{id}/household-routing` split (slice 4a, PR#383) to a concrete
   personal/family `household_id` (slice 4b, PR#384). mcp-caldav stays tenant-agnostic; a `userId`-absent
   request (or profile 404) falls back to the envelope household; a shared choice with no family household
   degrades to personal. Reads honoring the caller's household set = slice 5.
5. [ ] **#295 feed filter:** `GET /internal/events` filters by the requesting member's household set
   (personal ∪ shared); calendar-web passes the resolved feed's member; ICS feed now serves own +
   shared only.
6. [ ] **(deferred) `people.user_id` link** — when the "contact becomes a user" path is first needed.
7. [ ] **(deferred) default-sharing inference** — the learn/confirm policy (own tracking issue).

## Notes

First ADR in the repo. Convention: `plans/adr/ADR-NNNN-<slug>.md`, ADR format above, registered in
`plans/INDEX.md`. Locked decisions in `architecture.md` are updated only when an ADR reaches
**Accepted** — until then the ADR is the proposal, architecture.md remains the current truth.
