# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

- **road-test §#486 CRUD/undo — Track H DESIGN LANDED, coding not started.** The full track is specced in
  [stage4.md](stage4.md) §Track H: (H.1) the cross-cutting **"отмени последнее / undo" primitive** — a near-exact
  mirror of Track F/G (a `last_mutation` handle on conversation-state + a reserved `undo` classifier outcome +
  agent-led reversal over the existing C1 `invoke`/`/actions/undo`, **no new `Agent` method**), sliced
  H1 (storage) → H2 (orchestrator+primitive) → H3 (first producer = tasks) → H-rollout; plus (H.2) the
  **per-domain edit/delete/correct holes** (finance/tasks/notes/calendar) on each domain's own SkillRouter path.
  WHEN/THEN acceptance criteria are in the plan. **H1 ✅** storage (`last_mutation_{agent,payload,desc}` on
  `core.conversation_state`, migration `012`). **H2 ✅ (orchestrator wiring + primitive)** — `IntentResponse`
  gains `UndoHandle` + `withUndo`; `LlmIntentClassifier` gains the reserved `undo` outcome inside a new
  `Undoable` block (offered only when a `last_mutation` exists); `IntentRouter` records the handle on a fresh
  write (carried forward across a read turn), and on `undo` reverses it via the recording agent's
  `/actions/undo` (C1 `invoke`) + surfaces the confirmation / honest "нельзя отменить" + clears only the
  consumed mutation (last-route preserved). Proven by 5 new `IntentRouterLockTest` cases with stub agents. **H3
  (first real producer = tasks) split in two:** **H3a ✅ (reversal side)** — mcp-tasks `DELETE
  /internal/task/{id}` passthrough + tasks-agent `DeleteTaskClient` + a `web/ActionController` registering the
  `undo` action (reverse a captured task by deleting it, honest `ok=false` when already gone); proven by
  `ActionControllerTest` + a DELETE case in `McpTasksIntegrationTest`. **H3b ✅ (producer side)** —
  `TaskCapturer` attaches an `UndoHandle` (task title + created id) on a successful capture via
  `CaptureResult.undo` → `IntentController` `.withUndo`; deferred/failed turns leave it null. Proven by
  `TaskCapturerTest` (handle present on capture / null on the deferred ask) + a real-model `GoldenUndoTest`
  (opt-in). **The undo primitive is now end-to-end on tasks: capture → "отмени последнее" → task gone.**
  **H-rollout in progress (finance):** **finance reversal ✅** — mcp-finance `DELETE
  /internal/transaction/{id}` passthrough + `TransactionClient.delete` + finance `ActionController` registers
  the `undo` action (delete a just-written transaction, honest `ok=false` when gone); proven by
  `ActionControllerTest` (+2) + a DELETE case in `McpFinanceIntegrationTest`. **finance producer ✅** —
  `ReceiptParser.resume` (the confirm write) attaches `withUndo` (created tx id); this also required
  generalizing the orchestrator's `applyLockLifecycle` so a **resume turn that writes reversibly** records the
  `last_mutation` (clearing the resolved lock in the same upsert) instead of just clearing — so any
  confirm-then-write is now undoable. Proven by `ReceiptParserTest` + an `IntentRouterLockTest` resume-write
  case. **Finance is now end-to-end: receipt confirm → "отмени последнее" → transaction deleted.** **notes
  rollout ✅** — `NoteClient.delete` (`DELETE /v1/notes/{id}`, which also drops the recall seed + wiki-link
  edges) + a notes `web/ActionController` registering the `undo` action (delete a just-captured note, honest
  `ok=false` when gone) + `NoteWriter` attaches `withUndo` (note id + title) on a successful "запомни …"
  capture; proven by `ActionControllerTest` (4) + a handle assertion in `NoteWriterTest`. **Notes is now
  end-to-end: capture → "отмени последнее" → note deleted.** **calendar reframed → H.2 (owner-approved):**
  calendar had no user-facing write (events came only from the inter-agent `create_event`, tasks→calendar),
  so the cross-cutting undo primitive had no producer to hang on — "calendar rollout" is really H.2's
  "create/move/cancel via chat", specced as HC-1…HC-4 in [calendar.md](calendar.md) §H.2. **HC-1 ✅
  (event capture via chat)** — a `CalendarIntentRouter` (shared `SkillClassifier`, #475) routes "запиши
  встречу …" to a new `event-capture` skill + `EventCapturer` (LLM parse `{summary,dtstart,dtend?}` with
  `now` for relative dates → resolve household via the shared `SharingResolver` → mcp-caldav
  `/internal/event` → confirm; empty plan asks for the time); the chat + #195 feed-nudge logic moved to
  `CalendarChat`. Proven by `EventCapturerTest` (create + ask-when) + updated `/intent` tests (routing turn).
  **HC-2 ✅ (undo a just-created event)** — mcp-caldav `DELETE /internal/event/{id}` passthrough (delegates
  to the existing `deleteEvent` tool) + `CaldavEventClient.deleteEvent` + calendar `/actions/undo` (cancel by
  the stored id, honest `ok=false` when gone) + `EventCapturer` attaches `withUndo` (event id + summary) on a
  successful create. **Calendar is now end-to-end: "запиши встречу…" → "отмени последнее" → событие
  отменено** — closes the original calendar-undo goal. Proven by `ActionControllerTest` (+3 undo cases) +
  `EventCapturerTest` undo-handle assertion + `McpCaldavIntegrationTest` DELETE passthrough case.
  **HC-3 ✅ (cancel an existing event by description via chat + destructive-confirm gate)** — a new
  `event-cancel` SKILL + `EventCanceller` flow: read the owner's upcoming events (personal ∪ shared via
  `ProfileClient.householdRouting`, else the envelope household — new `CaldavEventClient.eventsInWindow`
  household-set overload, −1d…+180d) → LLM picks the target (`{"pick":n}`/`{"ambiguous":[…]}`/`{}`) → reply
  asks to confirm with a `pendingAction` (route-locks to calendar, deletes **nothing** yet); the follow-up
  "да" hits a new `POST /agents/calendar/resume` (`ResumeController`, mirrors finance) → `EventCanceller.resume`
  deletes via mcp-caldav `DELETE /internal/event/{id}`, a decline leaves it. Router gains `event-cancel`.
  Proven by `EventCancellerTest` (confirm-before-delete / resume-affirmative deletes / decline leaves it).
  **HC-4 ✅ (move/reschedule via chat)** — new `event-move` SKILL + `EventMover` flow behind a
  confirm-before-move gate: read upcoming events (same household-set read as HC-3) → LLM picks the target +
  new time (`{"pick":n,"dtstart":…,"dtend"?}` / `{"pick":n}` → ask for time / `{"ambiguous"}` / `{}`) → reply
  asks to confirm with a `pendingAction` (deletes/changes **nothing** yet); the "да" hits `POST /resume`
  (`event-move-confirm` → `EventMover.resume`) → patches only the time via a new mcp-caldav
  `PUT /internal/event/{id}` passthrough (`updateEvent`, patches supplied fields) + `CaldavEventClient.updateEvent`.
  Router gains `event-move`. Proven by `EventMoverTest` (confirm-before-move / no-time asks / resume PUTs new
  time / decline leaves it) + a PUT passthrough case in `McpCaldavIntegrationTest`.
  **The calendar H.2 chat CRUD is now complete: create (HC-1) · undo (HC-2) · cancel (HC-3) · move (HC-4).**
  **Per-domain H.2 rollout (parallel line):** **tasks delete ✅** — a new `task-delete` intent skill +
  `TaskDeleter` flow on tasks' own `IntentRouter` path, behind the confirm-before-delete gate: read the
  owner's open tasks (personal ∪ shared via `TaskReads.openTasksUnion`, new; `NextActionClient.fetchTasks`
  generalized) → LLM picks (`{"pick":n}`/`{"ambiguous":[…]}`/`{}`) → reply asks to confirm with a
  `pendingAction` (route-locks to tasks, deletes **nothing**); the "да" hits `POST /agents/tasks/resume`
  (`task-delete-confirm` → `TaskDeleter.resume`) → deletes via mcp-tasks `DELETE /internal/task/{id}`
  (existing `DeleteTaskClient`), a decline leaves it. Proven by `TaskDeleterTest` (confirm / ambiguous /
  no-match / resume-deletes / decline). **finance delete ✅** — a new `transaction-delete` intent skill +
  `TransactionDeleter` flow on finance's own `IntentRouter` path (`delete` classifier action), behind the
  confirm-before-delete gate: read the owner's recent transactions (personal ∪ shared via
  `SpendingReads.households` + a fan-out of a **new** mcp-finance `GET /internal/transactions` list
  passthrough / `TransactionClient.list`) → LLM picks (`{"pick":n}`/`{"ambiguous":[…]}`/`{}`) → reply asks to
  confirm with a `pendingAction` (route-locks to finance, deletes **nothing**); the "да" hits `POST
  /agents/finance/resume` (`transaction-delete-confirm` → `TransactionDeleter.resume`) → deletes via the
  existing mcp-finance `DELETE /internal/transaction/{id}` (`TransactionClient.delete`, the same reversal the
  undo primitive uses), a decline leaves it. Proven by `TransactionDeleterTest` (confirm / ambiguous /
  no-match / resume-deletes / decline) + a list case in `McpFinanceIntegrationTest`. **notes delete ✅** — a
  new `note-delete` intent skill + `NoteDeleter` flow on notes' own `NotesIntentRouter` path, behind the
  confirm-before-delete gate: read the household's recent notes (`NoteClient.list`, `type=list` notes
  excluded — lists are LI-a's job) → LLM picks (`{"pick":n}`/`{"ambiguous":[…]}`/`{}`) → reply asks to
  confirm with a `pendingAction` (route-locks to notes, deletes **nothing**); the "да" hits `POST
  /agents/notes/resume` (`note-delete-confirm` → `NoteDeleter.resume`) → deletes via the existing
  memory-service `DELETE /v1/notes/{id}` (`NoteClient.delete`, the same reversal the undo primitive uses), a
  decline leaves it. No memory-service change (list + delete already existed). Proven by `NoteDeleterTest`
  (confirm / list-excluded / ambiguous / no-match / resume-deletes / decline) + updated notes routing tests.
  **H.2 edit holes in progress (on the new ADR-0004 runner — an edit is now a ~30-line adapter with an
  `update` act):** **notes edit ✅ (2026-08-23)** — a new `note-edit` intent skill + `NoteEditor` flow on the
  notes `NotesIntentRouter` path, behind a confirm-before-change gate: read the household's recent notes
  (`type=list` excluded) → the LLM picks the target **and** extracts the new title/body → reply asks to
  confirm (a `pendingAction` route-locks; nothing written); a bare pick with no stated change re-asks; the
  "да" hits `POST /agents/notes/resume` (`note-edit-confirm` → `NoteEditor.resume`) → re-reads the note
  (`NoteClient.get`), overlays the change, and PUTs it (`NoteClient.update`, mutable-field replace preserves
  untouched fields). First non-calendar **update** consumer of `PickConfirmActRunner`. Proven by
  `NoteEditorTest` (confirm / ask-when-no-change / ambiguous / no-match / list-excluded / resume-updates /
  decline) + `note-edit` added to the notes routing golden. Detail → [second-brain.md](second-brain.md) §H.2.
  **tasks edit ✅ (2026-08-25)** — a new `task-edit` intent skill + `TaskEditor` flow on the tasks
  `IntentRouter` path, behind a confirm-before-change gate: read the owner's open tasks (personal ∪ shared via
  `TaskReads.openTasksUnion`) → the LLM picks the target **and** extracts the change (`newTitle` / `newDue`
  ISO / `newNote`, `now` for relative dates) → reply asks to confirm (a `pendingAction` route-locks; nothing
  written); a bare pick with no change re-asks; the "да" hits `POST /agents/tasks/resume`
  (`task-edit-confirm` → `TaskEditor.resume`) → PUTs only the changed fields via a **new** mcp-tasks
  `PUT /internal/task/{id}` passthrough (`UpdateTaskClient` → the existing `update_task` tool, partial edit).
  Status moves stay clarify/complete's job. Proven by `TaskEditorTest` (confirm / ask-when-no-change /
  ambiguous / no-match / resume-updates / decline) + a PUT case in `McpTasksIntegrationTest` + `task-edit`
  added to the tasks routing golden. Detail → [tasks.md](tasks.md) §H.2.
  **finance edit ✅ (2026-08-25, amount/note + category)** — a `transaction-edit` intent skill (the `edit`
  classifier action) + `TransactionEditor` flow on finance's own `IntentRouter` path, behind a
  confirm-before-change gate: read the owner's recent transactions (personal ∪ shared, same read as delete) →
  the LLM picks the target **and** extracts the change (`newAmount` magnitude / `newNote` / `newCategory`) →
  reply asks to confirm (a `pendingAction` route-locks; nothing written); a bare pick re-asks; the "да" hits
  `POST /agents/finance/resume` (`transaction-edit-confirm` → `TransactionEditor.resume`) → re-reads the row
  to keep the sign convention (expense<0 / income>0 — magnitude re-signed from the existing row, never
  trusted from the LLM), resolves a category **name**→id within the row's own household, then PUTs only the
  changed fields via a mcp-finance `PUT /internal/transaction/{id}` passthrough (`TransactionClient.update` →
  the existing `update_transaction` tool). **Category = existing-only:** a new additive `decorateAsync` hook
  on the shared `PickConfirmActRunner` (ADR-0004 follow-on) injects the household's existing category names
  into the pick prompt so the LLM never invents one; `act` resolves it (case-insensitive, honest error on
  unknown). Creating a category from an edit stays `category-manager`'s job. Proven by `TransactionEditorTest`
  (confirm / ask-when-no-change / ambiguous / no-match / resume-updates-keeping-sign / recategorise-confirm /
  resume-resolves-name→id / decline) + a `decorateAsync` merge case in `PickConfirmActRunnerTest` + a PUT case
  in `McpFinanceIntegrationTest` + `edit` in the finance routing golden. Detail → [finance.md](finance.md) §H.2
  + [ADR-0004](adr/ADR-0004-confirm-act-flow.md) §Follow-on.
  **NEXT: the remaining H.2 edit follow-up** — the tasks state-move-via-chat follow-up (clarify/complete
  rather than `update_task`). Epic queue → [#491](https://github.com/fedoroff-vlad/ai-life/issues/491).
- **road-test §#485 transparency — ✅ COMPLETE (2026-08-20).** All three threads done: degraded-notice board
  rollout + "why did you do that" trace (G1 routing via `ExplainResponder` + G2 agent write-traces across
  tasks/finance/notes/docs/nutrition, `GoldenExplainTraceTest` on a real model) + finance/calendar **sanity
  spot-checks** (future-dated receipt, end-before-start / double-booking). Detail → [HISTORY.md](HISTORY.md);
  doctrine → [architecture.md](architecture.md) §Principles (soft-fail + sanity spot-checks) + [stage4.md](stage4.md)
  §Track G.
- **road-test §#484 misroute-repair — ✅ DONE (2026-08-18, PR#502 F1 + PR#503 F2).** The correction loop
  ("не то, я про задачи" → re-classify with the prior route as context) is built on the conversation-state
  substrate; per-agent routing goldens were already delivered by #475. Detail → [HISTORY.md](HISTORY.md) +
  [stage4.md](stage4.md) §Track F. **Pick the next item** — road-test epic
  [#491](https://github.com/fedoroff-vlad/ai-life/issues/491) queue (transparency/no-silent-failures
  [#485](https://github.com/fedoroff-vlad/ai-life/issues/485), CRUD/undo
  [#486](https://github.com/fedoroff-vlad/ai-life/issues/486), …), or an arch-#479 thread, or a future agent
  (see `## Next`).
- **arch epic [#479](https://github.com/fedoroff-vlad/ai-life/issues/479) → #475 in-agent routing — ✅ DONE
  (2026-08-17).** All 8 cue-routed agents (notes / creator / docs / nutritionist / briefing / stylist / chef
  / travel) migrated off their `*_CUES` keyword heuristics onto the shared `agent-runtime`
  `intent/SkillRouter`; every keyword router deleted. **Bucket 1 is now fully complete** (finance + tasks
  were the original two). Detail → [skills-vs-flows.md](skills-vs-flows.md) §Bucket 1 + per-agent HISTORY rows
  (2026-08-17). **Remaining #479 threads** (pick next): shared personalization-profile capability
  [#476](https://github.com/fedoroff-vlad/ai-life/issues/476) (ADR first), prove agent-led multi-domain
  coordination [#477](https://github.com/fedoroff-vlad/ai-life/issues/477), reconcile empty `shared/skills/`
  doctrine [#478](https://github.com/fedoroff-vlad/ai-life/issues/478); + the model-gated Bucket 2 cutover
  [#369](https://github.com/fedoroff-vlad/ai-life/issues/369).
  **NEW thread — confirm-act flow duplication [#547](https://github.com/fedoroff-vlad/ai-life/issues/547):**
  the `read candidates → LLM picks → confirm via pendingAction → resume → act` loop is copy-pasted across 5
  flows (delete×3 + calendar cancel/move) and grows with each per-domain edit hole. **ADR-0004 ✅ Accepted
  (2026-08-23, owner-approved)** ([adr/ADR-0004-confirm-act-flow.md](adr/ADR-0004-confirm-act-flow.md)): lift
  a generic `PickConfirmAct` primitive into `libs/agent-runtime/intent/` (terminal act as a seam: delete |
  update; a `missing`-field re-ask gate for move/edit); mcp-side base CRUD explicitly out of scope. **PR-1 ✅
  (2026-08-23)** — the primitive landed in `agent-runtime/intent/` (`PickConfirmActRunner` +
  `TargetedActionFlow` + `CandidateView` + `Nouns`, `PickConfirmActRunnerTest` 12 cases) and the 3 delete
  flows (`TaskDeleter`/`TransactionDeleter`/`NoteDeleter`) collapsed onto it as ~30-line adapters
  (`candidates`/`view`/`act`/`nouns`); their 5+5+6 existing tests pass **unchanged** (pure-refactor safety
  net — legacy `idField`/`labelField` names kept via seam overrides, new flows take the `targetId`/`label`
  defaults). **PR-2 ✅ (2026-08-23) — ADR-0004 epic COMPLETE.** Calendar `EventCanceller` + `EventMover`
  retrofitted onto the runner — the first non-delete consumers, proving the move seam: the wording that
  didn't fit the delete template lifted into a `Phrasing` seam (`NounPhrasing` = the delete default, kept
  byte-identical; calendar supplies its own), plus the `missing()` re-ask gate (picked event, no new time),
  `readyToAct()` (resume needs the stashed time), top-level `params` passthrough (the new time threaded
  through the `pendingAction`, so `EventMoverTest`'s `dtstart` assertion holds), and `requiresHousehold=false`
  + `decorateUserMessage` (`now`). Their 3+4 `@SpringBootTest` tests pass **unchanged**; the runner test grew
  to cover the move branches. #547 can close. Next #479 thread: personalization-profile capability
  [#476](https://github.com/fedoroff-vlad/ai-life/issues/476) (ADR first), or another epic item.
- **lists capability is COMPLETE (LI-a + LI-b + LI-c).** LI-c
  (feat/lists-li-c-packing-note) closed the last piece: `travel-agent`'s `PackingFlow` now best-effort
  **mirrors its packing list onto the note tier** — an upsert of a household-shared `type=list` «список
  вещей» note (flat `MarkdownChecklist` body, find-or-create by title, re-ask replaces) so the owner then
  checks items off through LI-a with no travel code involved. Second consumer of note list/update →
  `listNotes`/`updateNote` lifted onto the shared `agent-runtime` `MemoryClient`. Detail →
  [HISTORY.md](HISTORY.md) / [lists.md](lists.md) §LI-c. **Pick the next item from `## Next`.**
- **travel MVP + live search + trip wallet (EX-a…EX-c) COMPLETE** (context only — detail in [travel.md](travel.md) / [HISTORY.md](HISTORY.md)):
  TR-a…e MVP + TR-f1/f2 live flight/hotel over Travelpayouts. To *enable* live search the owner must obtain a
  free **Travelpayouts** token + marker and accept T&C (a "confirm before doing" step — keys live in `.env`,
  never committed); until then the planner degrades to the MVP. Only **TR-f3** (tours/JS sources → `mcp-browser`) deferred.
- **Prior epics COMPLETE** (context only — detail in [HISTORY.md](HISTORY.md), don't re-open):
  - **travel #190 — DONE 2026-08-12 (MVP TR-a…e + live-search TR-f1/f2).** Planner-first vacation agent:
    `mcp-weather` `climate` (TR-a) + `mcp-travel` profile store (TR-b) + `travel-agent`/`travel-profiler`
    (TR-c) + `trip-planner` gather→synthesize (TR-d) + the **HTML travel board** with a climate-by-month
    chart (TR-e); **live flight/hotel options** via `mcp-travel-search` over Travelpayouts, owner-key-gated
    (TR-f1 capability + TR-f2 planner wiring: rank min-transfers→price, over-budget flag, deep links,
    degrade-to-MVP when `unconfigured`). Booking boundary permanent (ADR-0003). Only **TR-f3** (tours /
    no-API sources → `mcp-browser`) stays deferred.
  - **Sharing capability ADR-0002 — DONE 2026-08-07.** Slices 2–7 retrofitted all opt-in domains
    (calendar/finance/tasks/nutrition/docs, write & read); item 8 memory-driven default (DS-0…DS-4,
    `LearnedSharingPolicy` over a `memory.sharing_decision` tally, deterministic majority); and **DS-N
    confirm-on-ambiguity** — a domain defers + asks "личное или общее?" via the reusable `SharingConfirm`
    when the default is genuinely ambiguous. DS-N consumers: tasks (reference), finance (unscoped account),
    docs (untyped document — gained its first `/resume`); calendar (inter-agent write) + nutrition
    (deterministically-shared basket) opt out by design. Detail → [HISTORY.md](HISTORY.md) +
    [ADR-0002](adr/ADR-0002-sharing-shared-capability.md). Only the separate memory/second-brain owner-tag
    reconciliation stays deferred (orthogonal).
  - identity **ADR-0001** slices 1–6 (rows 2026-08-01/04); **skills-vs-flows** #358/#360 (only the Mac-gated
    cutover #369 stays open, see `## Next`).

## Parked — blocked on hardware (Mac not yet purchased)
- **Mac deployment + hot/cold lifecycle — [lifecycle.md](lifecycle.md) (owner-signed 2026-07-10).** Target:
  Mac Studio M4 Max 64/512 running ai-life 24/7 (hot set always-on + auto cold start/stop via a new
  `platform/supervisor`, + dynamic model downshift when the coder tenant runs). **Shipped so far** →
  HISTORY: `deploy-mvp` config (`.env.mac.example`, per-JVM heap caps, infra/README), **LC-1** hot/cold
  compose profiles (2026-07-15), **LC-4** `/v1/model-profile` evict-before-load swap (2026-07-21). **Why
  parked:** the next slice **LC-2 (`platform/supervisor` + socket-proxy)** wraps `docker compose up -d` and
  can't be meaningfully built/tested without a Docker daemon — there is none on the dev box, and the Mac
  isn't purchased yet. `deploy-mvp`'s own remaining step (first real `--profile hot up` + cross-domain
  smoke) is likewise hardware-blocked. Ordering when hardware lands: LC-2 → LC-2.5 cold-tolerant discovery
  → LC-3 (+3a AOT) → LC-5. Model stack decided → [model-strategy.md](model-strategy.md) (MoE-first; two MoE
  tenants may make the LC-4 downshift optional — **measure residency live at deploy**). coach-agent parked (Backlog).

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **travel follow-ups on the now-built `travel.trip` store — both near-term items DONE.**
   - **[#436](https://github.com/fedoroff-vlad/ai-life/issues/436) route/itinerary import** — **DONE (RT-a…RT-d2)** for files (GPX/GeoJSON/KML/KMZ) + map links (see [HISTORY.md](HISTORY.md)); only browser-resolved short links / JS polylines remain → **TR-f3** (`mcp-browser`). #436 can be closed (TR-f3 tracks the remainder).
   - **[#438](https://github.com/fedoroff-vlad/ai-life/issues/438) packing-list** — **DONE (PK-a, PR457)**: a deterministic list seeded by the active trip's season + rest types + companions on the TR-e board seam. #438 closed.
   - Spec/ideas: [travel.md](travel.md) §Deferred + §Ideas from TREK.
2. **Pick the next future agent** — travel #190 is fully closed (MVP + live search + trip wallet). Backlog (owner priority): health [#187](https://github.com/fedoroff-vlad/ai-life/issues/187), email [#191](https://github.com/fedoroff-vlad/ai-life/issues/191), smart-home [#192](https://github.com/fedoroff-vlad/ai-life/issues/192); or resume the parked coach-agent [#289](https://github.com/fedoroff-vlad/ai-life/issues/289) (CO-3+). See the [`future-agent`](https://github.com/fedoroff-vlad/ai-life/labels/future-agent) label + `## Backlog`.
3. **Bucket 2 production cutover ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369), model-gated)** — the pilot / validate-only half is DONE and #360 is closed; #369 tracks the remaining half: rip the Java `FinancialAdvisor` flow out and run the recipe from `SKILL.md` in production. Gated on the Mac / a stronger local MoE default.
4. **travel TR-f3 (deferred)** — tours + no-API/JS sources → `mcp-browser` (browser-use), which also closes the general scraping gap. Not near-term (see `## Backlog`).

_(fast/slow test split — DONE 2026-08-07, see [HISTORY.md](HISTORY.md) + [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance lever 2.)_

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294.
**Lists capability** (owner idea, 2026-08-14) — **COMPLETE** (LI-a + LI-b + LI-c), see [lists.md](lists.md):
grocery/things lists as **structured item lists** (add/check-off/clear) on the `memory.note` tier, owned by
**notes-agent**; LI-a explicit ops (#466) + LI-b ambient keyword-free capture + LI-c travel packing-list
mirrored as a `type=list` note. Detail → [HISTORY.md](HISTORY.md). (**off-site DB backup replication** DONE 2026-08-09 → HISTORY: `offsite` compose profile, `rclone-offsite` with a `BACKUP_OFFSITE_REMOTES` flag choosing Yandex Disk and/or a Tailscale host.)
**Assistant hardening / road-test** (owner direction 2026-08-16: daily-drive it as a reliable personal
assistant *before* any autonomy/actuation) — **epic [#491](https://github.com/fedoroff-vlad/ai-life/issues/491)**
is the SSOT; order: prerequisite 24/7 instance [#483](https://github.com/fedoroff-vlad/ai-life/issues/483)
(blocked) → transparency/no-silent-failures [#485](https://github.com/fedoroff-vlad/ai-life/issues/485) +
routing reliability [#484](https://github.com/fedoroff-vlad/ai-life/issues/484) (rides #475) → CRUD/undo
[#486](https://github.com/fedoroff-vlad/ai-life/issues/486) → proactive-UX
[#487](https://github.com/fedoroff-vlad/ai-life/issues/487) + memory-quality
[#488](https://github.com/fedoroff-vlad/ai-life/issues/488) → multimodal/reply-UX
[#489](https://github.com/fedoroff-vlad/ai-life/issues/489) + family-onboarding
[#490](https://github.com/fedoroff-vlad/ai-life/issues/490). Jarvis-style autonomy (smart-home #192,
coordination #477) deferred until this is done.
**Architecture hardening** (2026-08-16 review) — **epic [#479](https://github.com/fedoroff-vlad/ai-life/issues/479)** is the SSOT; items (ROI order): in-agent routing → shared `SkillClassifier` for the 8 cue-routed agents [#475](https://github.com/fedoroff-vlad/ai-life/issues/475); factor the 5× personalization-profile pattern into a shared capability (ADR first) [#476](https://github.com/fedoroff-vlad/ai-life/issues/476); prove real agent-led multi-domain coordination [#477](https://github.com/fedoroff-vlad/ai-life/issues/477); reconcile empty `shared/skills/` doctrine [#478](https://github.com/fedoroff-vlad/ai-life/issues/478). Bucket 2 cutover #369 (above) is the model-gated fifth thread.
Tech-debt: Apache AGE upgrade #296 (gated). Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is done and closed; the only open thread is the Mac-gated production cutover #369 in `## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
