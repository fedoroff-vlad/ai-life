# ambient / intuitive capture — the quality-intake foundation

Authority file for **ambient capture**: making the second brain fill itself *intuitively* — the system
deciding, on its own, from ordinary conversation, **what is worth keeping and about whom** — instead of
only via the explicit "запомни …" keyword. A post-epic follow-on to the second-brain substrate
([second-brain.md](second-brain.md), epic [#257](https://github.com/fedoroff-vlad/ai-life/issues/257)).

## North-star (why this exists)
The owner's aim (2026-07-03): the second brain should be stored **well enough to reason over** — a
request comes in, the LLM reads memory, **pulls the right agents from what the data says**, synthesizes
one answer, ideally in one shot. The lever is *storage quality*, not call speed.

That splits the work into two sides:
- **Input — quality intake (this file).** Curated, typed, attributed, linked, deduplicated notes, filled
  ambiently. This is the foundation that makes memory *reason-able*.
- **Output — memory-driven orchestration (a separate follow-on track).** On a request, read this memory
  → pick the right agents from the data → synthesize one answer. This is the owner's item 3
  (conversation-state / inter-agent, [architecture.md](architecture.md) §Orchestrator routing doctrine);
  the `Coordinator` gather→synthesize scaffold in `libs/agent-runtime` already exists for it. Good
  capture is its prerequisite — so we invest quality here first.

## Decision logic — what the system does with each message
Ambient capture is **not** "write everything down." On each inbound message the extractor classifies
every note-worthy candidate into one of three outcomes:

1. **Explicit fixation** — the message carries words that signal an intent to record ("запомни", "отметь",
   "зафиксируй", "не забудь", …). → **Write immediately, no approval** (the user asked). Provenance
   `source = user` (user-authored, just not routed through notes-agent).
2. **Important inferred fact** — the system judged it note-worthy on its own, with no explicit cue
   ("у мамы аллергия на орехи", "решил сменить работу"). → **Ask for approval before saving.** On
   confirm, write with `source = ambient` (system-noticed, lower default trust than user-authored).
3. **Trivial / operational / small-talk** — commands, questions, scheduling mechanics, chit-chat. →
   **Ignore** — nothing written, nothing asked.

So capture carries not just *what* + *about whom*, but also *auto-save vs approve*.

## Approach — evolve memory-from-chat, don't build a new path (architecture "A")
memory-from-chat (MFC) already runs on **every inbound message**, async and off the reply path:
orchestrator `IntentController` → `memory.observe(...)` → `POST /v1/observations` → bus
`message.received` → `MessageCaptureHandler.onEvent` → **`CaptureService.capture(CaptureRequest)`**, which
today produces two outputs (opaque `chat-capture` facts via `FactExtractor`; person-graph edges via
`RelationExtractor`). We add a **third output — curated ambient notes** — by extending `CaptureService`,
leaving the working `FactExtractor` / `RelationExtractor` untouched.

Decision (add a note extractor vs unify all three into one LLM call): both produce the *same* note
quality, so pick the lower-risk path — add alongside, consolidate later if ever needed. Facts stay as the
broad associative net; notes are the high-signal, reason-able tier consumers already prefer (SB-6
`GiftRecommender` filters recall to notes). Best-effort throughout, same posture as MFC (never throws,
never blocks the message).

## Phases (each a small vertical slice / PR unless noted)

### AC-1 — decision engine (extract + classify), no writes yet ✅ DONE
New `platform/memory-service/.../capture/NoteWorthinessExtractor` — mirrors `FactExtractor` (LlmClient
DEFAULT channel, strict-JSON, lenient parse, best-effort → empty on junk). From a message it emits 0..N
`NoteCandidate`s: `{title, type, body, subject, importance, explicitFixation}` where `type` ∈
`person|fact|idea|goal|journal|reflection` (the note-manifest types), `subject` = `"self"` | a person name
| null, `importance` marks how note-worthy, `explicitFixation` marks the presence of a fixation cue.
`NoteCandidate.outcome()` derives the three-way `CaptureOutcome` (`EXPLICIT_FIXATION` / `IMPORTANT_INFERRED`
/ `TRIVIAL`). No writes — just extraction + classification. `NoteWorthinessExtractorTest` (11 cases) +
opt-in `GoldenNoteWorthinessTest`.

### AC-2 — write the unambiguous (explicit fixation) + attribution ✅ DONE
Explicit-fixation candidates → a curated note via `NoteService.create` (already auto-seeds recall SB-2 +
`[[wiki-link]]` graph SB-3). Attribution: `subject == "self"` → owner-scoped (`ownerId = userId`, keep the
`type`, e.g. `goal`/`journal`); a name → `ProfileClient.resolvePersonId` (reuse) sets `personId` **and**
appends `\n[[<name>]]` so SB-3 projects a note→person edge (what `GiftRecommender` reads); an unresolved
name stays a dangling `[[<name>]]` link, note still saved; never auto-create a person. Gated by
`memory.ambient-capture.enabled` (off by default). Important-but-inferred candidates are **not** written
here — they wait for AC-4's approval flow. Wired as the third best-effort output of `CaptureService`
(`captureNotes`); `NOTE_SOURCE_USER = "user"`. `CaptureServiceTest` +7 cases (flag gating, self/named/
unresolved attribution, inferred-not-written, blank-title skip, write-failure-never-breaks). This is a
working intuitive capture for the explicit cases.

### AC-3 — dedup on write ("scan memory: duplicate or unique?") ✅ DONE
Before `create`, `CaptureService.isDuplicate` runs `MemoryService.recall(household, userId, personId,
title+body)` → filters to `source=note` hits → if the nearest note's cosine `distance <
memory.ambient-capture.dedup-distance` (default 0.15), **skip** (log); else create. Query mirrors
`NoteService`'s seed corpus (`title + "\n\n" + body`) so distances are comparable. Best-effort and
**fail-open** — a recall blip yields "not a duplicate" so a lookup failure never silently drops a note.
Stops the same fact piling up on repeated mentions. `CaptureServiceTest` +4 cases (near-dup skipped,
distant written, non-note neighbour ignored, recall-failure writes anyway).

### AC-4 — approval of important inferred facts ✅ DONE
Important **inferred** candidates (outcome 2) are not written silently: they are surfaced for the owner's
approval. **Design settled (owner, 2026-07-04): proactive push + resume** — reusing the *already-built*
conversation-state + route-lock + `/resume` primitive (Track A / conversation-service; the earlier "needs
item 3" gating was stale — it exists). The loop:
1. **Capture side (memory-service, AC-4b) ✅ DONE.** On an `IMPORTANT_INFERRED` candidate,
   `CaptureService.askApproval` does the attribution now (resolve `personId`, append `[[name]]`,
   `source=ambient`), sets a conversation-state lock (`routeLock=notes`,
   `pendingAction={flow:"ambient-approve", note:<WriteNoteRequest>}`) via `http/ConversationStateClient`, and
   pushes "заметил: … — записать?" via `http/NotifierClient`. The **channel** (`MessageReceivedEvent.source`)
   is threaded into capture as `CaptureRequest.channel` — only the async bus path has it, so approval rides
   the bus path (sync `/v1/capture` skips). We only ask if the lock persisted (else the reply has nowhere to
   resume). All best-effort + flag-gated; `CaptureServiceTest` +5 AC-4 cases.
2. **Resume side (notes-agent, AC-4a) ✅ DONE.** `approve/AmbientApprover` + `web/ResumeController`: the
   owner's reply resumes at `POST /agents/notes/resume`; an affirmative writes the pre-built note
   (`source=ambient`) via `NoteClient.create`, anything else drops it; both clear the lock. The orchestrator
   already routes a route-locked reply to `/resume` (`IntentRouter`) — no orchestrator change needed.
   `AmbientApproveResumeTest` (4 cases): affirmative writes, negative drops, unknown flow / missing note
   degrade gracefully.

Slices: **AC-4a** (resume side) ✅ → **AC-4b** (capture side: thread channel + lock + notifier push) ✅ →
**AC-4c** (E2E closer) ✅ — `AmbientApprovalPushE2ETest` drives an inferred message through the real
`POST /v1/capture` and asserts the outbound wire contracts across real MockWebServer boundaries: the
route-lock PUT to conversation-service (pendingAction `note` deserializes to a valid `ambient`
`WriteNoteRequest`) + the approval-question POST to notifier — closing the loop with the resume half
(`AmbientApproveResumeTest`). Golden `GoldenNoteWorthinessTest` gains the IMPORTANT_INFERRED case (verified
vs qwen2.5:7b) — the real-model classification the trigger hinges on.

### AC-5 — merge / update / supersede (later, deferred)
A near-duplicate is not always a no-op: a *new detail* should enrich the existing note's body; a
*contradiction* ("передумал") should update/supersede it. An LLM decides new-detail vs contradiction vs
nothing-new. Its own slice; not in the first working cut.

## Reuse (no new client/endpoint)
`FactExtractor` (the extractor shape), `ProfileClient.resolvePersonId` (attribution),
`MemoryService.recall` + the `NoteFinder.refId` filter (dedup), `NoteService.create` (write + auto-seed +
graph), `CaptureService` (the single capture entry).

## Deferred / follow-on
- **Consolidate the 3 extractors into 1 LLM call** (token economy) — optional optimization, not a blocker.
- **Weekly "вот что я подметил" digest** — batch trust/control surface, via the resurfacing path.
- **Memory-driven orchestration** — the north-star *output* side (read memory → pick agents →
  synthesize one-shot). Separate track (owner item 3 / inter-agent); this intake is its prerequisite.

## Verification
- **Unit** `NoteWorthinessExtractorTest` — strict-JSON parse, fences/prose tolerance, empty-on-junk,
  correct three-way classification (mirror `FactExtractorTest`).
- **Golden (opt-in, `GOLDEN_LLM`)** `GoldenNoteWorthinessTest` — the real local model, given a natural
  message, emits parseable candidates with a plausible `type`/`subject`/classification (structure, not
  wording), mirroring `GoldenNoteWriterTest`.
- **Integration (Testcontainers)** ✅ `AmbientCaptureIntegrationTest` — the AC-2/AC-3 chain across the real
  `POST /v1/capture` boundary into Postgres (extractors + `ProfileClient` mocked; persistence/embedding/dedup
  real): an explicit-fixation about "Мама" → a `memory.note` (`source=user`, `person_id=мама`, body `[[Мама]]`,
  note→person edge in `memory.relations`); a "self" fixation → an owner-scoped `goal` note; the same message
  twice (AC-3) → exactly one note.
- **Manual** — `docker compose up`, send a Telegram message with a fixation cue mentioning a known person,
  then ask notes-agent "что я думал про <person>" → the ambiently-captured note comes back.
