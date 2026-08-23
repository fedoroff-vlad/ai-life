# ADR-0004: Confirm-act flow — lift the pick→confirm→act loop into a shared `agent-runtime` primitive

**Status:** Accepted (2026-08-23 — owner-approved). The 2 retrofit PRs (§Slicing) are now active work;
**PR-1** (the primitive + the 3 delete flows) is next. No code lifted yet at acceptance time.
**Date:** 2026-08-23
**Deciders:** repo owner (holder/admin)
**Relates to:** issue [#547](https://github.com/fedoroff-vlad/ai-life/issues/547) under the
architecture-hardening epic [#479](https://github.com/fedoroff-vlad/ai-life/issues/479); a sibling of
[#476](https://github.com/fedoroff-vlad/ai-life/issues/476) (same "pattern reimplemented in N domains →
shared capability, ADR first" shape, different pattern). The flows it consolidates were built under
road-test [#486](https://github.com/fedoroff-vlad/ai-life/issues/486) (Track H.2). Mirrors the standing
"second consumer lifts it" rule already applied to `SkillClassifier` / `SharingResolver` / `Coordinator`
/ `AgentActionController` (architecture.md).

## Context

Building the per-domain CRUD holes (Track H.2) produced **five agent flows that are ~90% the same
class**, copy-pasted per domain:

| Flow | Domain | Read candidates | Terminal act |
|---|---|---|---|
| `TaskDeleter` | tasks | open tasks (personal ∪ shared) | delete |
| `TransactionDeleter` | finance | recent transactions (personal ∪ shared) | delete |
| `NoteDeleter` | knowledge | recent notes (envelope household) | delete |
| `EventCanceller` | calendar | upcoming events (personal ∪ shared) | cancel |
| `EventMover` | calendar | upcoming events (personal ∪ shared) | update (new time) + "picked but no time → re-ask" |

They share the **same two-turn shape over the Stage-4 pending-action lock**:

```
read candidates → LLM picks one (pick | ambiguous | none) → confirm via pendingAction
   → resume: affirmative → act, else leave; either way clear the lock
```

and the same boilerplate: the `AFFIRMATIVE` set, `parsePick` (`{pick:n}` / `{ambiguous:[…]}` / `{}`),
`candidateAt`, the numbered-candidate JSON handed to the LLM, the confirm text, the `pendingAction`
build + parse, and the per-stage soft-fail. Each class is ~200–260 lines of which ~30 are genuinely
domain-specific. The pending per-domain **edit** holes (note-edit / finance-edit / tasks-rename) are the
same shape again — the copy count only grows.

### Forces / constraints
- **"Second consumer lifts it" is repo doctrine.** `SkillClassifier`, `SharingResolver`, `Coordinator`,
  `BriefResponder`, `doc-render`, `MarkdownChecklist`, `AgentActionController` were all lifted into
  `libs/*` once a 2nd/3rd consumer appeared. Five consumers here is well past that bar.
- **The terminal act is not always "delete".** `EventMover` acts by *updating* the event's time, and
  has a "resolved target but missing a required field (the new time) → re-ask" branch. A delete-only
  helper would not be reusable by move/edit → a second fork. The abstraction has to make the **act a
  seam** and allow a **completeness gate**.
- **Domain reads differ** (own vs personal ∪ shared; which client/method) and must stay in the domain.
- **This is a privacy-adjacent mechanism** (a confirm gate before a destructive/mutating write) — it
  must stay deterministic and behavior-preserving. The lift is a **pure refactor**, not a redesign.
- **mcp-side persistence CRUD is a different question** — see §Out of scope.

## Decision

Lift a generic **PickConfirmAct** primitive into **`libs/agent-runtime/intent/`** (next to `SkillRouter`
/ `SkillClassifier`). A domain supplies a small adapter; the shared runner owns the orchestration.

### The seam (domain-supplied)

```java
// libs/agent-runtime/intent/TargetedActionFlow.java
public interface TargetedActionFlow<T> {
    String skillName();                                  // the *-delete/*-cancel/*-move SKILL — routing SSOT
    String flow();                                       // pendingAction discriminator (e.g. "note-delete-confirm")
    Mono<List<T>> candidates(NormalizedMessage msg);     // domain read (own vs personal ∪ shared lives here)
    CandidateView<T> view();                             // id(T) · label(T) · toNode(T, n) for the LLM prompt
    Mono<String> act(UUID targetId, JsonNode params);    // THE ACT: delete | cancel | update — returns confirm text
    default Optional<String> missing(JsonNode pick) {    // completeness gate: move w/o a time → the re-ask text
        return Optional.empty();                         // default: always complete (delete/cancel)
    }
    // confirm/decline wording is defaulted on the runner; overridable via small hooks if a domain needs it.
}
```

### The runner (shared, generic)

`PickConfirmActRunner` owns, once:
- the LLM round-trip (`[manifest, SKILL, {userText, candidates[]}]`, temperature 0);
- `parsePick` — `{pick:n}` → one, `{ambiguous:[…]}` → list, `{}`/junk → none — **plus passthrough of any
  extra fields the LLM returned** (the new time for move, a new amount for edit) into `params`;
- the empty-pool / no-match / ambiguous replies (no pendingAction);
- the single-match path: run `missing(pick)`; if it returns text → re-ask (no action); else build the
  confirm text + `pendingAction` (`{flow, targetId, label, params}`) and route-lock — **acting on
  nothing**;
- `resume`: affirmative → `act(targetId, params)` + its confirmation; anything else → "left unchanged";
  missing/broken id → a graceful reply. Every path clears the lock. Every stage soft-fails.

A domain flow collapses to ~30 lines: `candidates()`, `view()`, `act()`, and (only for move/edit)
`missing()` + the extra-field mapping. `IntentRouter`/`ResumeController` wiring is unchanged in shape —
they still dispatch to the domain object, which now delegates to the runner.

### What stays in the domain vs the runner

| Concern | Lives in |
|---|---|
| Which candidates to read, own-vs-shared cut | domain (`candidates()`) |
| How to render/label a candidate | domain (`view()`) |
| The terminal write (delete / cancel / update) | domain (`act()`) |
| "Missing required field" gate (move/edit) | domain (`missing()`) |
| LLM call, parse, confirm text, pendingAction, resume, soft-fail, affirmative-set | **runner (shared)** |

## Consequences

**Positive**
- One place for the confirm-act mechanism; the next edit hole is a ~30-line adapter, not another 250-line copy.
- The confirm gate (a privacy/safety boundary) is defined once and tested once — no per-domain drift.
- New unit surface: a focused `PickConfirmActRunnerTest` covers the orchestration for every consumer.

**Negative / cost**
- A one-time retrofit of 5 existing flows (mechanical, test-guarded).
- One more shared abstraction to learn — mitigated by keeping the seam tiny and the examples in place.
- A generic `JsonNode params` passthrough is slightly less type-safe than a per-flow field; acceptable
  (it is opaque and only round-trips through the pendingAction, exactly as today).

**Safety net (this is a pure refactor).** The five flows already have unit tests
(`TaskDeleterTest`, `TransactionDeleterTest`, `NoteDeleterTest`, `EventCancellerTest`, `EventMoverTest`)
asserting confirm / ambiguous / no-match / resume-affirmative / decline over the public `delete()`/
`move()`/`resume()` surface. **The lift is accepted only if those tests pass unchanged** — that is the
guarantee behavior did not move — plus the new runner test.

## Slicing (2 PRs, by seam complexity)
- **PR-1** — the primitive (`TargetedActionFlow` + `CandidateView` + `PickConfirmActRunner` + its test)
  and retrofit the **3 delete flows** (seam: `act = delete`, `missing` = default). Proves the core seam
  across three domains.
- **PR-2** — retrofit **calendar cancel + move** (exercises the `params` passthrough + the `missing`
  gate). Proves the non-delete act.

Each PR touches multiple domains (a lift is atomic by nature); the split keeps each reviewable and lets
the delete seam land and bake before the move seam. `check-consistency.sh` gets no new rule (no new
`/internal/*` coupling); the change is agent-internal.

## Alternatives considered
- **Leave the copies.** Rejected: 5 instances and growing; the boilerplate is where subtle drift hides
  (e.g. a soft-fail that one flow forgets).
- **A delete-only helper.** Rejected: `EventMover` (and every future edit) acts by update with a missing-
  field re-ask — a delete-only helper forces a second fork of the same loop.
- **A base `Agent`/controller method.** Rejected: the flow is not a transport concern; it belongs beside
  `SkillRouter` as a flow primitive, dispatched to by the existing per-agent `IntentRouter`.

## Out of scope (explicitly)
- **mcp-side base CRUD controller/service.** The generic persistence CRUD already exists — Spring Data
  `JpaRepository`. The `/internal/*` passthroughs are thin *because* they delegate to `@Tool`s carrying
  domain invariants (cross-household guards, the `transaction.uncategorised` one-shot hook, recall-seed +
  `[[wiki-link]]` cleanup on note delete, tenant-agnostic contracts). A generic base controller would be
  overridden almost entirely — the classic "generic base you override wholesale" anti-pattern — for ~15
  lines saved per endpoint, at the cost of leaking those invariants. Keep per-domain passthroughs.
- **The confirm-before-write *capture* flows** (`ReceiptParser`, `AmbientApprover`, `InboxClarifier`,
  `AccountManager` DS-N). They share the *pending-action lock* but not the *pick-one-candidate* shape
  (they confirm a single already-built draft, no candidate list). Not part of this primitive; a later
  ADR could generalise the lock lifecycle itself if a fourth capture flow appears.
