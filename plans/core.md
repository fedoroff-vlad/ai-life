# Core domain — identity, profiles, people

Schema `core`: users, household, sessions, conversations, people. Owned conceptually by **profile-service**; `core` tables are shared/read by all.

## profile-service (platform/)
- Identity: `telegram_user_id` → `user_id` → `household_id` → scope (private / household / group_chat).
- People CRUD (contacts: family, friends — for birthdays, gifts, greetings).
- Preferences, dietary restrictions, clothing sizes, etc. (grows over time, prefer JSONB over new tables).

### Endpoints
- `GET /v1/users/by-telegram/{id}`, create household, create user (find-or-create on first `/start`).
- `POST /v1/people`, `GET /v1/people/{id}`, `PATCH /v1/people/{id}`, `GET /v1/people/by-household/{id}`.

## core.people (Liquibase, added in calendar stage / PR8)
```
id                 uuid PK (gen_random_uuid)
household_id       uuid NOT NULL FK → core.households
user_id            uuid FK → core.users (NULL)   # ADR-0001 item 6: contact→operator link
display_name       varchar(128) NOT NULL
relationship       varchar(64)        # mother / friend / colleague / ...
locale             varchar(16)        # for greeting language
interests          jsonb DEFAULT '[]'
notes              text               # gift history etc. until memory-service (Stage 4)
lead_days_override jsonb              # { "gift": 30, "greeting": 1 } or NULL
created_at         timestamptz DEFAULT now()
```
Indexes: `(household_id)`; GIN `interests` jsonb_path_ops; trgm `display_name` gin_trgm_ops (fuzzy "Маша" match).

Person ↔ calendar event link: via `calendar.events_cache.person_id` (FK NULL) for now. No separate person_events table in calendar stage.

Person → user link (ADR-0001 item 6): nullable `user_id`. A contact may **become** an operator ("the daughter grows up and starts using the bot"). Set via `PUT /v1/people/{id}/user` (422 if the user is unknown). Prior notes/events about the person keep their existing scope; the now-user gains a private space independently. Most people never become users, so the column stays null.

## Identity & membership (ADR-0001, Accepted 2026-07-31)
`user → household` is **1:N**, not 1:1: each user owns a **personal household** and can be an approved
**member** of shared (family) households via `core.household_members(household_id, user_id, role,
relationship, joined_at)`. An item lives in exactly one household = its visibility boundary (**tenant
routing**): private → personal household, shared → family household; a member reads the **union** over
their memberships. A friend registers into their own personal household → full isolation. Onboarding is
invite-only (deep-link token, owner-gated). `users.household_id` is retained as the user's personal
household (read-through default) during the migration. Full model + slice sequence:
[adr/ADR-0001](adr/ADR-0001-identity-membership-scope.md).

## Onboarding UX — a household assistant, not a solo tool ([#490](https://github.com/fedoroff-vlad/ai-life/issues/490))
The identity/membership plumbing ([ADR-0001](adr/ADR-0001-identity-membership-scope.md)) + sharing
([ADR-0002](adr/ADR-0002-sharing-shared-capability.md)) are shipped; #490 is the **UX layer on top** so a
second member (the wife) is productive in minutes with minimal friction. Slices FO-*:

- **FO-1 — join orientation.** The `/start <token>` redeem reply is a short **orientation** for the new
  member (not a bare "you've joined"): what already works with no setup, how to set personal preferences
  in chat, and the privacy line. Gateway-level (`IdentityResolver.joinedReply`).
  - `Scenario:` WHEN the wife opens the invite deep-link and joins → THEN the reply names her relationship,
    lists what works immediately (capture / recall / briefing), shows a chat example for personal prefs,
    and states personal items stay private — in her locale.
- **FO-2 — preferences-in-chat, keyword-free.** A plain conversational preference ("я встаю в 7, брифинг в
  7:15", "я живу в Казани") is captured **per-member** without a domain-specific cue, routed to the right
  per-domain profiler (briefing/travel/nutrition/stylist), self-scoped to the sender.
  - `Scenario:` WHEN a member states a preference in chat with no config keyword → THEN it is stored for
    that member (`owner_id` = sender) and honoured, not applied household-wide.
- **FO-3 — sensible defaults on join.** A new member with nothing set gets working defaults immediately
  (e.g. a briefing profile seeded with all-sections defaults), so nothing is a dead-end; personal stays
  personal, shared stays shared (ADR-0002).
  - `Scenario:` WHEN a member has set nothing and asks for a briefing → THEN a default digest is produced
    (no "configure me first" dead-end).

## Notes
- Growth happens as **rows / JSONB**, never runtime DDL.
- Multi-tenancy (vlad + wife + later others) is `household_id` + membership, not per-user schemas.
