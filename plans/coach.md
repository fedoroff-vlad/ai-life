# Coach domain — spec

**Status: CO-2 SHIPPED — CO-3 next ([#289](https://github.com/fedoroff-vlad/ai-life/issues/289);
owner brief + all decisions signed 2026-07-07).** Owner item 2, was deferred behind the platform
migration + memory-driven orchestration; picked up spec-first because the MVP scope/store were open —
now resolved (see **Decisions** below). This file is the agreed design; implementation is phased below.
**CO-1 done (2026-07-07):** the `coach.*` schema + `mcp-coach` store (subject-scoped CRUD +
`/internal/coach/*` + Testcontainers IT) — see [domains/coach/mcp-coach/README.md](../domains/coach/mcp-coach/README.md).
**CO-2 done (2026-07-07):** `coach-agent` (port 8122) — safety gate + reactive Reflect on the shared
`Coordinator`, persisting sessions/observations/hypotheses; orchestrator-registered; E2E closer +
golden tests — see [domains/coach/coach-agent/README.md](../domains/coach/coach-agent/README.md).
*CO-2 note:* `subject` = the sender's `user_id` (the only authenticated identity a message carries —
core has no user↔person link yet; `coach.subject` is a soft uuid, so a later link can migrate it).
**Next: CO-3** = `intake` (questionnaire → profile/values).

A self-understanding agent for the owner: it reads the experience ai-life has accumulated about them
(second-brain notes, financial/calendar signals, past coach sessions) and, using **evidence-based
psychotherapy techniques**, helps them understand themselves first and build a development path second.
A consumer of the second brain + the hub — like `coordinator-agent`, but with a durable coaching memory
of its own.

## Safety & scope boundary (load-bearing — read first)
This is a **personal self-reflection assistant, not therapy and not a clinician.**
- **No diagnosis.** It never assigns a medical/psychiatric label or a disorder. It surfaces *patterns*
  and *open questions*, framed as the user's own material.
- **Crisis → refer out.** On any signal of crisis (self-harm, harm to others, acute distress) it drops
  the coaching frame, responds with care, and gently points to a real specialist / helpline. This check
  runs **before** any pattern analysis and short-circuits it. It never "handles" a crisis itself.
- **Evidence-based only.** Its moves come strictly from the methods below — no pop-psychology,
  no horoscopes, no invented frameworks.
- **Hypotheses are hypotheses.** Every pattern the agent proposes is labelled as a hypothesis (its own
  and revisable), never stated as a fact about the user.

## Evidence base (the only sanctioned toolbox)
Each method contributes a distinct move; the agent's prompts encode them and the coaching memory tags
which move produced an observation.
- **CBT** — name cognitive distortions (catastrophising, emotional reasoning, black-and-white thinking);
  trace *thought → emotion → behaviour*.
- **ACT** — values work + cognitive defusion ("I am not my thought"); act toward values rather than
  avoid discomfort.
- **Motivational Interviewing** — don't push; draw out the user's *own* motivation with open questions;
  respect ambivalence.
- **Solution-Focused Brief Therapy** — focus on resources + working exceptions ("when it was better,
  what was different?").
- **IFS (optional)** — a "parts" vocabulary for internal conflicts.

## Two modes
1. **Reflect (understand yourself)** — read a cross-sectional slice of the user's data, find *recurring
   patterns* (e.g. "rescue projects" spun up amid financial anxiety despite objective stability), and
   return them as **observations + open questions**, not finished conclusions. This is the priority half.
2. **Develop (a path)** — from an identified pattern, propose one **concrete, verifiable next step** tied
   to the user's **values** (e.g. flow states in code, immersive media, a calm family day), then track it.

## Personalization — per-person "vector" (first-class)
The coach serves **more than the owner** — each household member is a distinct subject with their own
material and their own **coaching vector**: which methods to lean on, tone calibration, focus areas,
and boundaries. The owner and, say, his wife need *different* vectors; the agent must never blend two
people's patterns or apply one person's approach to another.
- **Everything is scoped by subject.** Every coach record (values, observations, hypotheses, actions,
  sessions) carries a `subject` = the person it's about, resolved to a `person_id` via profile-service
  (reuse the existing person resolution; `"self"`/owner is just one subject). Reads (notes, recall,
  briefs) are filtered to that subject and respect the existing **per-user privacy** rules (e.g. a
  member's private finance accounts are not visible to another).
- **`coach_profile` = the vector.** A per-subject profile: emphasised methods (weighting over
  CBT/ACT/MI/SFBT/IFS), tone calibration, focus areas, and hard boundaries / off-limits topics. It
  shapes the system prompt for that person's sessions. Editable; seeded from that person's own sessions,
  never assumed.
- **Strictly private, per-person (owner-signed).** Like a therapy session: each subject's record is
  private to that subject. The **subject is always the authenticated message sender** (their own
  `person_id`) — the coach works only on the sender's own record; **no owner-override, no cross-member
  read**. Sharing is **subject-initiated only** ("если захотят, сам поделятся") — a deferred action, not
  MVP. See Decision 0.

## Intake (deliberate questionnaire → `coach_intake`)
Passive reading (notes/briefs) isn't enough to establish *targeted* things — values, focus areas, the
vector, a baseline. So the coach can run a short, deliberate **intake**: a small set of open questions
(one per turn, MI-style — never an interrogation), asked at onboarding or when a session needs a missing
piece. Answers persist in `coach_intake`; `coach_value` + `coach_profile` are seeded/updated from them.
Multi-turn intake rides the existing conversation-state route-lock/resume (same one-question-per-turn
rhythm). Kept light — a handful of questions, resumable, never blocking a reflect/develop request.

## Data it reads (cross-sectional slice)
Coach owns no source data — it gathers, like the `Coordinator`:
- **Second-brain notes** via `memory-service`: `journal` / `reflection` / `goal` note types (already
  accumulating through ambient capture) + `/v1/memories/recall` for semantic pull on a theme.
- **Cross-domain signals** via the existing read-only **`brief`** cross-agent query (through the hub):
  finance spend/anxiety signals, calendar load/rhythm. `finance` + `calendar` already expose `brief`.
- **Past coach sessions** from its own store (below) — for continuity ("last time you said…").
- **"Alive moments"** — see Open decisions; MVP reads them as `journal`/`reflection` notes until a
  dedicated capture exists.

## Coach memory (`coach.*` schema — its own store, via `mcp-coach`)
The one place coach is **not** stateless: a durable coaching record so sessions build on each other and
hypotheses can be revised. **Every table is scoped by `household_id` + `subject` (`person_id`)** — the
per-person vector (above) means records never cross subjects. Proposed tables:
- `coach_profile` — the per-subject **vector**: method weighting (CBT/ACT/MI/SFBT/IFS), tone calibration,
  focus areas, boundaries/off-limits, active. One per subject; shapes that person's session prompt.
- `coach_value` — a value (label, note, source: stated|inferred, weight?, active). Subject-owned,
  editable. Seeded from that person's conversation, never invented.
- `coach_observation` — one grounded observation from a session (text, method tag ∈ cbt|act|mi|sfbt|ifs,
  evidence refs = note/brief ids, session_id, ts).
- `coach_hypothesis` — a candidate recurring pattern (text, status ∈ open|supported|revised|dropped,
  supporting/contradicting observation ids, confidence, ts). **Explicitly a hypothesis**; revised as new
  data arrives (a `NoteReconciler`-style enrich/supersede, mirroring ambient capture).
- `coach_action` — a proposed next step (text, linked value, linked hypothesis, status ∈
  proposed|active|done|dropped, due?, ts) — the Develop-mode follow-up spine.
- `coach_intake` — stored answers to deliberate targeted questions (subject, topic, question, answer,
  asked_by ∈ onboarding|session, ts). The place the questionnaire (below) persists what it collects;
  `coach_value` / `coach_profile` are then derived/seeded from it. (Owner: "нужна таблица/место где
  хранить".)
- `coach_session` — a session envelope (subject, ts, mode, summary, produced observation/action ids) for
  continuity.

`mcp-coach` = a domain-MCP over `coach.*` (CRUD + a few reads: open hypotheses, active actions, values,
intake answers, recent sessions) + `/internal/*` passthroughs, per PATTERNS "add a domain-MCP".
Liquibase: a new `coach.*` schema in the reserved **`100-109`** feature-file range (added to the PATTERNS
numbering table).

## Agent shape
`domains/coach/coach-agent` (+ `domains/coach/mcp-coach`), following the finance/calendar agent recipe:
- **Reads** memory-service (shared runtime `MemoryClient` + a thin `NoteClient`) and cross-agent `brief`s
  (like the coordinator's `SpecialistBriefs`); **writes** its coaching record via `mcp-coach`
  `/internal/*`.
- **Reasoning on the shared `Coordinator`**: gather (notes + recall + briefs + prior sessions) →
  synthesize under `[AGENT.md + reflect|develop SKILL.md]`. Same substrate as the coordinator and the
  finance advisors.
- **Skills** (`domains/coach/skills/`): `reflect` (patterns + open questions), `develop` (one values-tied
  next step), `intake` (deliberate targeted questions → `coach_intake`), and a `safety-check` gate that
  runs first. AGENT.md carries the evidence-base doctrine, the tone, and the anti-goals so every skill
  inherits them; the subject's `coach_profile` vector tunes the prompt per person.
- **Delivery: chat-first, HTML seam laid.** Replies are plain chat for the MVP, but the flow constructs
  its result so the `DeliverablePublisher`/`doc-render` render→store→link seam (as in the finance report)
  can be switched on later for a "reflection board" — without reshaping the flow. Off for the MVP.
- **No new layer** — it's the established "agent that reads the second brain + the hub, owns a small
  domain store" shape (coordinator-agent + a domain-MCP). Flagged here because the *content* (clinical
  methods, safety) is new, not the architecture.

## Tone & anti-goals (encoded in AGENT.md)
- Warm, not saccharine. No praise for praise's sake. **At most one question per turn.**
- Does **not** encourage dependence on the agent or dialogue-for-dialogue's-sake — it aims to hand
  agency back, not to retain engagement.
- Anti-goals: don't flatter, don't validate a distortion, don't become an echo. **Honest feedback over
  comfort.** No medical/psychiatric diagnosis.

## Triggers
- **Reactive first** — the user asks ("помоги разобраться", "почему я опять…", "подведи итоги"): the
  orchestrator routes to `coach-agent`; a classifier picks `reflect` vs `develop`. Multi-turn uses the
  existing conversation-state route-lock/resume (one question per turn fits it well).
- **Proactive later** — an opt-in periodic check-in via scheduler → orchestrator wake (mirrors
  resurfacing/briefing), surfacing an open hypothesis or an active action. Off by default; a fast-follow
  slice, not the MVP.

## Phased slices (each = one small vertical slice / PR unless noted)
- **CO-1 — `coach.*` + `mcp-coach` store. ✅ DONE (2026-07-07).** Schema (profile/value/observation/
  hypothesis/action/session/**intake**, all `household_id`+`subject`-scoped, `100-coach.yml`) + domain-MCP
  (16 `@Tool` CRUD/reads + `/internal/coach/*` passthroughs) + Testcontainers IT. No agent yet. `subject`
  is a soft person ref (no FK, like `memory.note.person_id`); jsonb for the vector/evidence/obs-id fields.
- **CO-2 — `coach-agent` skeleton + `safety-check` + `reflect` (reactive). ✅ DONE (2026-07-07).**
  Agent module (port 8122), orchestrator registration, **subject = the authenticated sender**
  (`msg.userId()` — see the CO-2 note in the Status header), gather (that subject's notes + recall +
  recent sessions) → Reflect synthesis shaped by the subject's `coach_profile` vector (defaults when
  empty) → persists observations/hypotheses + a session. Safety gate short-circuits first; non-private
  scope declined. Golden-tested on qwen2.5:7b (structure-not-text) + `E2ECoachReflectFlowTest` closer.
- **CO-3 — `intake` (questionnaire → profile/values).** A light, resumable one-question-per-turn intake
  (conversation-state) that persists to `coach_intake` and seeds `coach_value` + `coach_profile` (the
  vector). Onboarding + on-demand when a session lacks a piece.
- **CO-4 — cross-domain `brief` gather.** Fold finance/calendar `brief`s into the Reflect gather (reuse
  `SpecialistBriefs`) so patterns span domains (the "rescue projects amid financial anxiety" case).
- **CO-5 — `develop` mode + action follow-up.** Values-tied next step → `coach_action`; continuity reads
  prior sessions/actions ("last time you said…").
- **CO-6 — hypothesis reconciliation.** New observations enrich/support/revise/drop existing hypotheses
  (mirror ambient capture's `NoteReconciler`), so the record sharpens over time.
- **CO-7 (later, opt-in) — proactive check-in** via scheduler wake; and the deferred subject-initiated
  **share** action + the HTML "reflection board".
- **E2E closer** at CO-2/CO-5 per the stage-closer rule.

## Decisions (owner-signed, 2026-07-07) — CO-1 unblocked
0. **Strictly private, per-person — like a therapy session.** Each subject's coaching record is **private
   to that subject**; sharing is **subject-initiated only** ("если захотят, сам поделятся"). The
   **subject = the authenticated message sender** (their own `person_id`) — the coach only ever works on
   the sender's own record; there is **no owner-override, no cross-member read** of another's coaching
   data. A subject-initiated **share** action is a deferred follow-up (not MVP). This makes scoping
   simple and safe: every `coach.*` read/write is filtered to the sender.
1. **"Alive moments" = notes for now.** MVP reads them as `journal`/`reflection` notes (ambient capture
   already produces these). A dedicated "alive-moment" capture is a later follow-up.
2. **Values mostly from sessions + a deliberate intake.** `coach_value` is seeded from what the subject
   says (no pre-seeded defaults). But to gather *targeted* information deliberately, the coach runs a
   short **intake / questionnaire** and stores the answers — see **Intake** below (a `coach_intake`
   store, per the owner's "нужна таблица/место где хранить").
3. **Chat first, HTML seam laid.** MVP delivery is reactive chat. Build the render→store→link seam
   (`DeliverablePublisher`/`doc-render`) so a "reflection board" HTML deliverable can be turned on later
   without reshaping the flow — but it's off for the MVP.
4. **Liquibase `100-109 = coach`.** Reserved for `coach.*`; the PATTERNS numbering table gets a `coach`
   row (added with this spec).

## Wiring touchpoints (for the build, not now)
Root `pom.xml` modules (`mcp-coach`, `coach-agent`); `infra/docker-compose.yml` + `.env.example` +
`infra/README.md` port table (proposed **mcp-coach 8121**, **coach-agent 8122** — next free after
chart-render 8120); orchestrator agent registry (`coach`); `plans/INDEX.md` row (added with this spec);
Liquibase master include. Per PATTERNS "add a domain-MCP" + "add a new agent".
