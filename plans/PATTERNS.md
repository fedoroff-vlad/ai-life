# PATTERNS — scaffolding recipes (read BEFORE copying a sibling module)

This file exists so you do not have to read 6 files in a sibling module just to learn the
layout when scaffolding something new. Each recipe is a checklist + a pointer to the
canonical example. If the canonical example has drifted from the recipe, fix the
recipe — it is the source of truth for "how we do this here".

## Recipe: add a new MCP server (`mcp/<name>`)
Canonical example: [mcp-ics-import](../mcp/mcp-ics-import) (Stage-1 closer of the
pattern; mcp-caldav is older and slightly less aligned).

1. New module dir `mcp/<name>/` with:
   - `pom.xml` (parent = `ai-life-parent`, `<artifactId>` = `<name>`, depends on
     `contracts` + `platform-common` + `spring-ai-starter-mcp-server-webflux` + JPA +
     `ical4j`/whatever).
   - `Dockerfile` (mirror mcp-caldav's two-stage Temurin 21 build).
   - `README.md` (purpose, port, MCP tools, env vars, key classes — one line each).
2. New module reference in root `pom.xml` `<modules>` list.
3. Java package `dev.fedorov.ailife.mcp.<name>`:
   - `<Name>McpApplication.java` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
   - `config/<Name>Properties.java` — `@ConfigurationProperties(prefix = "<name>")`.
   - `config/HttpConfig.java` — `WebClient` beans (use `.clone()` per outbound dependency
     to avoid shared-builder leakage — see [PR10 note](STATUS.md)).
   - `domain/` — JPA entities + repositories.
   - `tools/<Name>McpTools.java` + `tools/ToolsConfig.java` — Spring AI `@Tool`-annotated
     methods + `MethodToolCallbackProvider` bean. **Tool descriptions in English** (token
     economy).
4. `src/main/resources/application.yml` — `spring.ai.mcp.server.{name, version, type:
   ASYNC, instructions}`, `server.port`, properties, actuator exposure.
5. Add new contract DTOs/inputs in `libs/contracts/.../<domain>/` (records,
   `@JsonInclude(NON_NULL)`).
6. Liquibase: new feature file (see migration recipe below); also mirror the new tables
   in this module's `src/test/resources/test-schema.sql` so Testcontainers boots.
7. Integration test: `<Name>McpIntegrationTest` with `@SpringBootTest` + `@Testcontainers`,
   pgvector PG container, `test-schema.sql` mounted to `/docker-entrypoint-initdb.d/`,
   and any other backend container (Radicale uses
   `Wait.forHttp("/.web/")` — its image has no bash so the default port-listening check
   no-ops).
8. **Wire into the deploy surface — do not skip these or `docker compose up` silently
   omits your service:**
   - [infra/docker-compose.yml](../infra/docker-compose.yml) — new service block
     mirroring `mcp-finance` (build context `..`, healthcheck on `/actuator/health`,
     `depends_on: { postgres: service_healthy, liquibase: service_completed_successfully }`,
     internal-only — no `ports:` unless there is a reason).
   - [infra/.env.example](../infra/.env.example) — new `# === mcp-<name> ===` block
     with port + DB url/user/password + any tool-side config (cron, owner-agent, etc.).
   - [infra/README.md](../infra/README.md) — add a row to the port table.
   - The full compose includes existing infra; do NOT add a separate
     `docker-compose.dev.yml` entry for app services (`dev.yml` is infra-only by
     convention — see infra/README §"Two compose files").

## Recipe: add a new agent (`agents/<name>`)
Canonical example: [calendar-agent](../agents/calendar-agent), [finance-agent](../agents/finance-agent).

1. New module dir `agents/<name>/` with `pom.xml`, `AGENT.md` (frontmatter + EN body),
   `README.md`, `Dockerfile` (mirror calendar-agent's two-stage Temurin 21 build).
2. `pom.xml`:
   - depends on `contracts` + `llm-client` + `agent-runtime` + `spring-boot-starter-webflux`.
     **Do NOT re-implement the AGENT.md / SKILL.md loaders or `Profile`/`Notifier`/`Memory`
     clients** — they live in `libs/agent-runtime` since PR25a/25b. New agents `@Import`
     them; see step 3.
   - `<resources>` block copies `AGENT.md` from the module root + the relevant skills
     directory onto the classpath:
     `<include>AGENT.md</include>` and a second `<resource>` for
     `<directory>../../skills/<domain></directory>` → `<targetPath>skills/<domain></targetPath>`.
3. Java package `dev.fedorov.ailife.agents.<name>`:
   - `<Name>AgentApplication.java` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig.class)`
     (the runtime sits outside the auto-scan root). Optionally
     `@EnableConfigurationProperties(<Name>AgentProperties.class)` for per-agent config.
   - `config/<Name>AgentProperties.java` — `<name>-agent.*` block for per-agent base URLs
     (only the URL bindings; the HTTP clients themselves come from `agent-runtime`).
   - `config/OutboundHttpConfig.java` — `@Qualifier`-named `WebClient` beans
     (`profileServiceWebClient`, `notifierWebClient`, `memoryServiceWebClient`) each
     `.clone()`d off the shared builder to avoid base-URL leakage. The runtime's
     `ProfileClient`/`NotifierClient`/`MemoryClient` pick them up by qualifier.
   - `web/IntentController.java` — `POST /agents/<name>/intent`, calls llm-gateway with
     `AgentManifest.body()` as system prompt.
   - `web/TriggerController.java` — `POST /agents/<name>/triggers/{kind}`, dispatches via
     the injected `SkillRegistry` from `agent-runtime`.
   - `web/ManifestController.java` — `GET /agents/<name>/manifest` (returns the injected
     `AgentManifest`).
4. `application.yml` — `agent.manifest-classpath: AGENT.md`, `agent.skills-classpath:
   "classpath*:skills/<domain>/*/SKILL.md"` (the empty default is a deliberate
   anti-cross-leak guard — set it per agent), `agent.memory-recall-k`, and any
   `<name>-agent.*` base URLs.
5. Register the agent in orchestrator config (`AgentRegistryProperties` →
   `{name, baseUrl}` + env var `<NAME>_AGENT_URL`).
6. Skills live in repo root under `skills/<domain>/<name>/SKILL.md` (NOT inside the
   agent module) — `pom.xml` `<resources>` copies them in. First skill in a new domain
   creates the directory; subsequent skills just drop a folder beside.
7. **Wire into the deploy surface — same checklist as a new MCP module:**
   - [infra/docker-compose.yml](../infra/docker-compose.yml) — new service block
     mirroring `finance-agent` (build context `..`, healthcheck on `/actuator/health`,
     `depends_on` includes llm-gateway, profile-service, notifier-service, memory-service,
     and every MCP server the agent talks to — all with `condition: service_healthy`).
   - [infra/.env.example](../infra/.env.example) — new `# === <name>-agent ===` block
     with port, URL, and any agent-specific tuning (`*_MEMORY_RECALL_K`,
     `*_MCP_CLIENT_ENABLED`, etc.).
   - [infra/README.md](../infra/README.md) — add a row to the port table.

## Recipe: add a Liquibase migration
Canonical examples: [011-ics-subscriptions.yml](../infra/liquibase/features/011-ics-subscriptions.yml)
(simple), [010-calendar.yml](../infra/liquibase/features/010-calendar.yml) (with raw-SQL
GIN index).

### Numbering convention (do not deviate without updating this table)

| Range | Domain | Schemas / topic |
|---|---|---|
| `001-009` | core / cross-cutting | `core` (households, users, sessions), `scheduling`, `people`, `memory`, `audit`, `bus`, `media` |
| `010-019` | calendar | `calendar.*`, ICS subscriptions |
| `020-029` | finance | `finance.*` (transactions, budgets, recurring) |
| `030-039` | tasks | `tasks.*` |
| `040-049` | _next domain_ (reserved) | — |

Rules:
- **Pick the lowest free slot inside your range** — don't leave gaps unless you
  know what you're holding them for. New cross-cutting schema → next free in
  `001-009`. New domain table → next free in that domain's range.
- **One concept per file.** `020-finance.yml` introduces the schema +
  base tables; `021-fin-budget.yml` adds a feature on top; a schema-id column
  added to an existing table goes into its own file (`022-fin-budget-schedule-id.yml`).
  Never amend an already-merged migration — write a new file.
- **Filename = `NNN-<domain>-<topic>.yml`.** Topic is what the file actually
  does, in kebab-case. Topic is required for follow-ups (anything after the
  domain's base file).
- **`changeSet.id` matches the filename without `.yml`** (e.g. `021-fin-budget`).
  `author: ai-life` for every entry.
- **Master changelog order matters.** Add the `<include>` in
  [db.changelog-master.xml](../infra/liquibase/db.changelog-master.xml) **after**
  any migration whose tables yours references via FK. Numerically-sorted order
  is the convention; deviate only if a cross-domain dependency forces it
  (rare — call it out in a YAML comment).

### Steps

1. New file `infra/liquibase/features/NNN-<domain>-<topic>.yml` per the
   convention above.
2. `changeSet.id` matches the filename without `.yml`. `author: ai-life`.
3. Include the file in `infra/liquibase/db.changelog-master.xml` `<include>` list
   (numerically sorted unless a cross-range dependency forces otherwise).
4. For every integration test that touches the new tables, **mirror the minimal
   DDL** in that module's `src/test/resources/test-schema.sql`. Drift = failing
   test; that is the intended early-warning mechanism. Update the
   `-- Mirrors infra/liquibase/features/{…}.yml` header comment so the next
   maintainer can re-sync.

## Recipe: add a new contract DTO
Canonical examples: any record in [libs/contracts](../libs/contracts) — they are
deliberately tiny and `@JsonInclude(NON_NULL)`.

1. Java record under `dev.fedorov.ailife.contracts.<domain>.<Name>.java`.
2. `@JsonInclude(JsonInclude.Include.NON_NULL)` on the record.
3. No business logic in contracts — they are pure data carriers. If a record needs
   computed methods (e.g. `toDto()`), put those on the entity, not the record.
4. Update the consuming module's README if the contract is part of its public surface.
