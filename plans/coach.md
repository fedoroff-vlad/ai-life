# Coach domain — spec (spec-first, no code yet)

**Status: SPEC only ([#289](https://github.com/fedoroff-vlad/ai-life/issues/289), owner brief 2026-07-07).**
Owner item 2, deferred behind the platform migration + memory-driven orchestration; picked up spec-first
because the MVP scope/store were open. This file is the agreed design; implementation is phased below and
starts only after the owner signs off the **Open decisions**.

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
hypotheses can be revised. Proposed tables:
- `coach_value` — a user value (label, note, source: stated|inferred, weight?, active). User-owned,
  editable. Seeded from conversation, never invented.
- `coach_observation` — one grounded observation from a session (text, method tag ∈ cbt|act|mi|sfbt|ifs,
  evidence refs = note/brief ids, session_id, ts).
- `coach_hypothesis` — a candidate recurring pattern (text, status ∈ open|supported|revised|dropped,
  supporting/contradicting observation ids, confidence, ts). **Explicitly a hypothesis**; revised as new
  data arrives (a `NoteReconciler`-style enrich/supersede, mirroring ambient capture).
- `coach_action` — a proposed next step (text, linked value, linked hypothesis, status ∈
  proposed|active|done|dropped, due?, ts) — the Develop-mode follow-up spine.
- `coach_session` — a session envelope (ts, mode, summary, produced observation/action ids) for
  continuity.

`mcp-coach` = a domain-MCP over `coach.*` (CRUD + a few reads: open hypotheses, active actions, values,
recent sessions) + `/internal/*` passthroughs, per PATTERNS "add a domain-MCP". Liquibase: a new
`coach.*` schema, next free feature-file range (see Open decisions — the numbering table needs a `coach`
row).

## Agent shape
`domains/coach/coach-agent` (+ `domains/coach/mcp-coach`), following the finance/calendar agent recipe:
- **Reads** memory-service (shared runtime `MemoryClient` + a thin `NoteClient`) and cross-agent `brief`s
  (like the coordinator's `SpecialistBriefs`); **writes** its coaching record via `mcp-coach`
  `/internal/*`.
- **Reasoning on the shared `Coordinator`**: gather (notes + recall + briefs + prior sessions) →
  synthesize under `[AGENT.md + reflect|develop SKILL.md]`. Same substrate as the coordinator and the
  finance advisors.
- **Skills** (`domains/coach/skills/`): `reflect` (patterns + open questions), `develop` (one values-tied
  next step), and a `safety-check` gate that runs first. AGENT.md carries the evidence-base doctrine,
  the tone, and the anti-goals so every skill inherits them.
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
- **CO-1 — `coach.*` + `mcp-coach` store.** Schema (value/observation/hypothesis/action/session) +
  domain-MCP CRUD + `/internal/*` + Testcontainers IT. No agent yet.
- **CO-2 — `coach-agent` skeleton + `safety-check` + `reflect` (reactive).** Agent module, orchestrator
  registration, gather (notes + recall) → Reflect synthesis → persists observations/hypotheses + a
  session. Safety gate short-circuits first. Golden-tested on qwen2.5:7b (structure-not-text).
- **CO-3 — cross-domain `brief` gather.** Fold finance/calendar `brief`s into the Reflect gather (reuse
  `SpecialistBriefs`) so patterns span domains (the "rescue projects amid financial anxiety" case).
- **CO-4 — `develop` mode + action follow-up.** Values-tied next step → `coach_action`; continuity reads
  prior sessions/actions ("last time you said…").
- **CO-5 — hypothesis reconciliation.** New observations enrich/support/revise/drop existing hypotheses
  (mirror ambient capture's `NoteReconciler`), so the record sharpens over time.
- **CO-6 (later, opt-in) — proactive check-in** via scheduler wake.
- **E2E closer** at CO-2/CO-4 per the stage-closer rule.

## Open decisions (need owner sign-off before CO-1)
1. **"Alive moments" source.** New concept — nothing in the system captures it today. MVP proposal: read
   it as `journal`/`reflection` notes (ambient capture already produces these); a **dedicated
   "alive-moment" capture** (a distinct note type or a quick log seam) is a separate follow-up. Confirm:
   MVP-on-notes now, dedicated capture later? Or is a distinct capture part of the MVP?
2. **Values seed.** Seed `coach_value` purely from what the user says in sessions (recommended), or
   pre-seed the examples from the brief (flow-in-code, immersive media, calm family) as editable
   defaults?
3. **Delivery surface.** Reactive chat only for the MVP (recommended), or also an HTML "reflection board"
   deliverable (via `doc-render`, like the finance report) from the start?
4. **Liquibase numbering.** `coach.*` needs a feature-file range; the PATTERNS numbering table has no
   `coach` row yet (090 is taken by `memory-note`). Proposal: reserve `100-109 = coach`.

## Wiring touchpoints (for the build, not now)
Root `pom.xml` modules (`mcp-coach`, `coach-agent`); `infra/docker-compose.yml` + `.env.example` +
`infra/README.md` port table (proposed **mcp-coach 8121**, **coach-agent 8122** — next free after
chart-render 8120); orchestrator agent registry (`coach`); `plans/INDEX.md` row (added with this spec);
Liquibase master include. Per PATTERNS "add a domain-MCP" + "add a new agent".
