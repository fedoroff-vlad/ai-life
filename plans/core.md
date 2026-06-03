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

## Notes
- Growth happens as **rows / JSONB**, never runtime DDL.
- Multi-tenancy (vlad + wife + later others) is `household_id` + scope, not per-user schemas.
