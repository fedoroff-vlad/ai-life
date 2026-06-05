# profile-service

Users, households, preferences. Resolves `telegram_user_id → user_id` for the gateway,
and serves as the source of truth for identity across the system.

## Endpoints

| method | path                                    | purpose                          |
|--------|-----------------------------------------|----------------------------------|
| POST   | `/v1/households`                        | create household                 |
| GET    | `/v1/households/{id}`                   | fetch by id                      |
| POST   | `/v1/users`                             | create user under a household    |
| GET    | `/v1/users/{id}`                        | fetch by id                      |
| GET    | `/v1/users/by-telegram/{telegram_user_id}` | reverse lookup for gateway     |
| GET    | `/actuator/health`                      | liveness                         |

Validation errors → 400; missing household → 422; duplicate telegram_user_id → 409.

## Configuration (env vars)

```
PROFILE_SERVICE_PORT=8082
PROFILE_DB_URL=jdbc:postgresql://localhost:5432/ailife
PROFILE_DB_USER=ailife
PROFILE_DB_PASSWORD=ailife
```

## Run locally

Make sure the dev infra is up (`infra/docker-compose.dev.yml`) so Postgres + the `core`
schema (created by Liquibase) are available.

```sh
mvn -B -pl platform/profile-service -am spring-boot:run
```

## Tests

`mvn -B -pl platform/profile-service test` — uses Testcontainers (pulls `pgvector/pgvector:pg16`),
applies a tiny test schema, and runs full Spring Boot context with REST calls.

## Key classes
- `ProfileServiceApplication`.
- `domain/Household`, `domain/User`, `domain/Person` + `*Repository` — JPA over `core.{households,users,people}`.
- `web/HouseholdController` — `/v1/households` CRUD.
- `web/UserController` — `/v1/users`, `/by-telegram/{id}`, `/by-household/{id}`.
- `web/PeopleController` — `/v1/people` POST/GET/by-household/PATCH (partial).
- `web/dto/Create*Request`, `web/dto/UpdatePersonRequest` — request bodies. Response payloads use shared `*Dto` records from [libs/contracts](../../libs/contracts).

## Schema
[001-core.yml](../../infra/liquibase/features/001-core.yml) (households, users) +
[003-people.yml](../../infra/liquibase/features/003-people.yml) (people + `pg_trgm` GIN indexes on `interests` and `display_name`).

## Endpoint additions since the table above
- `GET /v1/users/by-household/{id}` — added in PR10 (notifier fan-out).
- `GET /v1/people/{id}`, `GET /v1/people/by-household/{id}`, `PATCH /v1/people/{id}` — added in PR9a (calendar-agent person resolution).
