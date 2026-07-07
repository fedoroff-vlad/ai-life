# mcp-coach

MCP server: source-of-truth coaching record over the `coach.*` schema. Subject-scoped
CRUD for the coach-agent's durable memory — the one place coaching is **not** stateless.
The store only; reflection/reasoning (safety gate, Reflect/Develop, methods) lives in
`coach-agent`. See [plans/coach.md](../../plans/coach.md) / [#289](https://github.com/fedoroff-vlad/ai-life/issues/289).

**Consumers:** [coach-agent](../coach-agent/README.md) (CO-2) — reads `profile` + `sessions` and writes
`sessions`/`observations`/`hypotheses` through the `/internal/coach/*` passthroughs (no MCP transport).

**Scope invariant (load-bearing):** every record is scoped by `(householdId, subject)` —
the per-person coaching "vector"; records never cross subjects. `subject` is a soft person
reference (a plain uuid, no FK — resolved via profile-service, like `memory.note.person_id`).
Reads/lists filter on both; id-based mutations (`update_*`) trust the caller since the id came
from a subject-scoped read (mirrors mcp-tasks — this MCP is intentionally low-level). The
strictly-private "subject = authenticated sender, no cross-member read" rule is enforced in
`coach-agent`.

## Tools (MCP)

- `upsert_coach_profile(householdId, subject, methodWeights?, tone?, focusAreas?, boundaries?, active?)`
  — create/update the per-subject **vector** (unique on household+subject). `methodWeights`
  (JSON object over cbt/act/mi/sfbt/ifs), `focusAreas`/`boundaries` (JSON arrays) applied when
  non-null; `active` defaults true.
- `get_coach_profile(householdId, subject)` — the vector or `null` (agent falls back to defaults).
- `add_coach_value(householdId, subject, label, note?, source?, weight?)` — record a value.
  `source` ∈ `stated|inferred` (default `stated`).
- `list_coach_values(householdId, subject, activeOnly?)` — newest first; `activeOnly` excludes
  deactivated.
- `start_coach_session(householdId, subject, mode, summary?)` — open a session envelope.
  `mode` ∈ `reflect|develop|intake`.
- `list_recent_coach_sessions(householdId, subject, limit?)` — newest first (continuity).
- `add_coach_observation(householdId, subject, sessionId?, text, method, evidenceRefs?)` —
  a grounded observation. `method` ∈ `cbt|act|mi|sfbt|ifs`; `sessionId` must belong to the same
  subject; `evidenceRefs` is a JSON array of note/brief ids.
- `list_coach_observations(householdId, subject, sessionId?, limit?)` — newest first; scope to
  one session with `sessionId`.
- `add_coach_hypothesis(householdId, subject, text, confidence?, supportingObservationIds?, contradictingObservationIds?)`
  — propose a pattern (lands `status=open`). Explicitly a hypothesis.
- `update_coach_hypothesis(id, status?, confidence?, supportingObservationIds?, contradictingObservationIds?)`
  — revise (non-null only). `status` ∈ `open|supported|revised|dropped`. Throws on unknown id.
- `list_coach_hypotheses(householdId, subject, status?, limit?)` — most-recently-updated first;
  `status` filters (e.g. `open`).
- `add_coach_action(householdId, subject, text, valueId?, hypothesisId?, dueAt?)` — propose a
  next step (lands `status=proposed`). `valueId`/`hypothesisId` must belong to the same subject.
- `update_coach_action(id, status?, dueAt?)` — advance follow-up state. `status` ∈
  `proposed|active|done|dropped`. Throws on unknown id.
- `list_coach_actions(householdId, subject, status?, limit?)` — newest first; `status` filters
  (e.g. `active`).
- `add_coach_intake_answer(householdId, subject, topic?, question, answer?, askedBy?)` — store a
  deliberate intake Q+A. `askedBy` ∈ `onboarding|session` (default `session`).
- `list_coach_intake(householdId, subject, limit?)` — newest first.

List `limit` is capped at 200 (default 50).

## Internal REST passthroughs

Non-MCP, no LLM tax — for the coach-agent (CO-2+). Base `POST/GET /internal/coach/*`; every
endpoint delegates to the matching tool (same invariants). Validation failures → 400.

- `POST /internal/coach/profile` (`UpsertCoachProfileInput`) · `GET /internal/coach/profile?householdId=&subject=`
- `POST /internal/coach/values` (`AddCoachValueInput`) · `GET /internal/coach/values?householdId=&subject=&activeOnly=`
- `POST /internal/coach/sessions` (`StartCoachSessionInput`) · `GET /internal/coach/sessions?householdId=&subject=&limit=`
- `POST /internal/coach/observations` (`AddCoachObservationInput`) · `GET /internal/coach/observations?householdId=&subject=&sessionId=&limit=`
- `POST /internal/coach/hypotheses` (`AddCoachHypothesisInput`) · `PATCH /internal/coach/hypotheses` (`UpdateCoachHypothesisInput`) · `GET /internal/coach/hypotheses?householdId=&subject=&status=&limit=`
- `POST /internal/coach/actions` (`AddCoachActionInput`) · `PATCH /internal/coach/actions` (`UpdateCoachActionInput`) · `GET /internal/coach/actions?householdId=&subject=&status=&limit=`
- `POST /internal/coach/intake` (`AddCoachIntakeAnswerInput`) · `GET /internal/coach/intake?householdId=&subject=&limit=`

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_COACH_PORT` | `8121` | HTTP port |
| `MCP_COACH_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_COACH_DB_USER` / `MCP_COACH_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `McpCoachApplication`.
- `domain/` — seven JPA entities + repositories over `coach.*`, all `(household_id, subject)`
  scoped: `CoachProfile` (unique per subject; `method_weights`/`focus_areas`/`boundaries` jsonb),
  `CoachValue`, `CoachSession`, `CoachObservation` (soft `session_id` FK; `evidence_refs` jsonb),
  `CoachHypothesis` (`supporting/contradicting_observation_ids` jsonb), `CoachAction`
  (`value_id`/`hypothesis_id` FKs), `CoachIntake`.
- `tools/CoachMcpTools` — 16 `@Tool` methods (CRUD + reads). Enforces subject scope, the
  status/method/mode/source whitelists, and the cross-subject guards on `session_id`/`value_id`/
  `hypothesis_id`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.
- `web/InternalCoachController` — `/internal/coach/*` REST passthroughs (agent-facing);
  `IllegalArgumentException` → 400.

## Schema

- [100-coach.yml](../../infra/liquibase/features/100-coach.yml) — the `coach.*` schema: seven
  tables (`coach_profile`, `coach_value`, `coach_session`, `coach_observation`,
  `coach_hypothesis`, `coach_action`, `coach_intake`), all `(household_id, subject)` scoped.
  `subject` carries no FK (soft person reference, like `memory.note.person_id`);
  `coach_observation.session_id` → `coach_session`, `coach_action.value_id`/`hypothesis_id` →
  their tables. jsonb for the free-form vector/evidence/observation-id fields.
