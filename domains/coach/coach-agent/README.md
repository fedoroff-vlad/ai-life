# coach-agent

Self-understanding coach ([#289](https://github.com/fedoroff-vlad/ai-life/issues/289)), port **8122**.
Reads the experience ai-life has accumulated about a person — their second-brain notes, semantic
memories, past coach sessions — and, using **evidence-based methods only** (CBT/ACT/MI/SFBT/IFS),
surfaces recurring patterns as **observations + hypotheses**, never diagnoses. Registered in the
orchestrator as `coach`; owns no source data — its one durable store is the coaching record in
[mcp-coach](../mcp-coach/README.md) (`/internal/coach/*`, no MCP transport). Spec:
[plans/coach.md](../../../plans/coach.md).

**Not therapy, not a clinician (load-bearing):** no diagnosis, crisis → refer out (the safety gate runs
before any pattern analysis and short-circuits it), every pattern is framed as a revisable hypothesis.

## Status (CO-2)

Skeleton + the **safety gate** + the **Reflect** mode (reactive). Develop/intake/briefs/proactive are
later slices (CO-3…CO-7).

- **Privacy (Decision 0)** — the **subject is the authenticated sender**: every read and write is scoped
  to `msg.userId()`; there is no owner-override and no cross-member read. Non-private scope (household /
  group chat) is declined before anything runs. Note reads are filtered to notes **owned by the subject**
  — household-shared and other members' notes never enter a session.
  *(CO-2 note: `subject` = the sender's `user_id` — the only authenticated identity a message carries;
  `coach.subject` is a soft uuid, so a later user↔person link can migrate it without DDL.)*
- **Safety gate** — one FAST-channel `safety-check` turn (`{"crisis": true|false}`, errs toward `true`
  when uncertain). Crisis → a fixed care + referral reply; no gather, no synthesis, no store writes.
- **Reflect** — gather (subject's `journal|reflection|goal` notes + memory recall on the request + recent
  coach sessions) → one DEFAULT-channel synthesis on the shared `Coordinator` under
  `[AGENT.md, reflect SKILL.md]`, shaped by the subject's `coach_profile` vector (defaults when absent) →
  strict JSON: user reply + observations (method-tagged) + hypotheses (confidence 0-100) + session
  summary → persisted to mcp-coach (session → observations → hypotheses). Persistence is best-effort:
  a store outage loses the record, never the conversation; a prose (non-JSON) model reply still reaches
  the user, unpersisted.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/coach/intent` | orchestrator entry: private-scope guard → safety gate → Reflect. |
| GET | `/agents/coach/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`safety-check`** (`domains/coach/skills/safety-check/SKILL.md`) — strict-JSON crisis classifier;
  runs first, short-circuits coaching.
- **`reflect`** (`domains/coach/skills/reflect/SKILL.md`) — the Reflect synthesis: patterns as grounded
  observations + open questions, method-tagged, ≤1 question in the reply, strict JSON.

## Env

| Var | Default | Purpose |
|---|---|---|
| `COACH_AGENT_PORT` | `8122` | HTTP port. |
| `COACH_AGENT_MEMORY_RECALL_K` | `5` | memory-recall fan-in (shared agent-runtime). |
| `COACH_NOTES_SCAN_LIMIT` | `100` | how many recent household notes to pull before the subject filter. |
| `COACH_GATHERED_NOTES_MAX` | `15` | cap on subject notes handed to the synthesis. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | safety check (FAST) + Reflect synthesis (DEFAULT). |
| `MEMORY_SERVICE_URL` | `http://memory-service:8087` | subject notes (`GET /v1/notes`) + semantic recall. |
| `MCP_COACH_URL` | `http://mcp-coach:8121` | the durable coaching record (`/internal/coach/*`). |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` | internal | shared agent-runtime clients. |

## Key classes

- `CoachAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/CoachAgentProperties` — `coach-agent.*` base URLs (implements `SharedClientProperties`) + gather caps.
- `config/OutboundHttpConfig` — the `mcpCoachWebClient` bean (its own base URL; profile/notifier/memory come from `agent-runtime`).
- `safety/SafetyGate` — the crisis gate: FAST `safety-check` turn, lenient parse, degrades to non-crisis on gateway error (the synthesis would fail to a friendly error anyway).
- `flow/Reflector` — the Reflect flow: profile → gather (notes + recall + sessions) → `Coordinator` synthesis → parse → persist (best-effort) → reply. Drops observations with non-whitelisted methods; clamps confidence to 0-100.
- `http/CoachStoreClient` — `/internal/coach/*`: profile + recent-sessions reads (soft-fail empty), session/observation/hypothesis writes (errors logged by the flow, reply survives).
- `http/SubjectNotesClient` — `GET /v1/notes` filtered to the subject's own `journal|reflection|goal` notes (the privacy boundary); soft-fails to empty.
- `web/IntentController` — private-scope + sender guards → safety gate → Reflect; `web/ManifestController`.

## Tests

- `E2ECoachReflectFlowTest` — the CO-2 closer: ONE real Spring context + MockWebServers for llm-gateway /
  memory-service / mcp-coach; proves safety-short-circuit, the Decision-0 note filter on the wire, the
  strict-JSON persist chain (unknown method dropped, confidence clamped), and prose degradation.
- `GoldenSafetyGateTest` / `GoldenReflectorTest` — real-model (qwen2.5:7b) structure-not-text golden
  tests, opt-in via `GOLDEN_LLM` (`scripts/golden.sh -pl domains/coach/coach-agent`).
