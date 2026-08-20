# docs-agent

Personal **document-archive** specialist (port **8117**). Ingests a document photo (receipt / contract
/ warranty / sick-note) into the archive and answers "find my X". Registered in the orchestrator as
`docs`; owns `mcp-docs`; binds the shared `mcp-media-processing` (OCR). Plan:
[plans/docs.md](../../../plans/docs.md).

## Status (ADR-0002 slice 7 + item 8 DS-4 + DS-N confirm-on-ambiguity — fully retrofitted, learned default wired)

Scaffold + the **ingest** and **search** flows, with **semantic recall** layered onto both. As of the
second-brain **SB-5** (epic #257) the semantic seed is an authored **note** in the shared substrate, not
a raw `memory.memories` row — docs-agent is the first consumer of the universal note-write seam
(`MemoryClient.note`/`getNote` in `libs/agent-runtime`). As of **ADR-0002 slice 7** documents are fully
retrofitted onto the shared `libs/sharing` capability — **7a (write)** routes each archived document to the
member's shared vs personal household via a local `DocsSharingPolicy` (warranty/contract → shared, else
private); **7b (read)** widens "find my X" to the member's personal ∪ shared households on an explicit
"наши документы" cue (default = own).

- **Ingest (D-c, +SB-5 note seed, +7a sharing)** — an inbound message with an `image` attachment →
  `doc-archiver`: OCR the photo (`mcp-media-processing` `POST /internal/ocr`) → one llm-gateway turn with
  the `doc-archiver` SKILL extracts the metadata (doc_type / title / party / date / amount / currency /
  tags) from the OCR text + the user's caption → **resolve the shared vs personal `household_id`** via the
  shared `SharingResolver` + `DocsSharingPolicy` (a warranty/contract is a household asset → the family's
  shared household; a receipt/note/ID stays private; degrades to personal with no family household or on a
  profile hiccup) → archive via `mcp-docs` `POST /internal/documents` under that household, storing the
  full OCR text as the search corpus → **seed the document into the second-brain as a note**
  (`MemoryClient.note` → `POST /v1/notes`, `source=docs-agent`, `type=reference`, body = OCR text,
  `frontmatter={kind:document, refId}`, seeded under the resolved household) so it lands in the one store
  every agent reads and memory-service auto-seeds it into recall (SB-2) → confirm what was filed. The note
  seed soft-fails: the document is already saved + text-searchable.
- **Search (D-d, +SB-5 recall, +7b sharing)** — the `DocsIntentRouter` classifies the text intent (find
  vs chat) via the shared `agent-runtime` `SkillRouter` (#475), then `doc-finder`: one llm-gateway turn
  with the `doc-finder` SKILL distils a search query + optional docType filter → **resolve the household
  set** (default = own; an explicit "наши документы"/family cue widens to the member's personal ∪ shared
  households via `ProfileSharingClient.households`) → **two searches in parallel, each unioned across the
  set** — `mcp-docs` `GET /internal/documents/search` (trigram, one call per household) **and** a
  memory-service semantic recall over the second-brain (one recall per household): a `{kind:note, refId}`
  recall hit is fetched (`MemoryClient.getNote` → `GET /v1/notes/{id}`) and its `frontmatter`
  `{kind:document, refId}` back-pointer resolves to the document row (`GET /internal/documents/{id}`) —
  merged + de-duplicated → the reply lists the hits (title / type / date / party) each with an open link
  to the stored blob. Each source soft-fails independently; the own cut is a single household (identical
  to the pre-sharing search).

Otherwise a message falls through to a chat fallback. Every stage soft-fails to a friendly reply.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/docs/intent` | orchestrator entry. Image attachment → `doc-archiver` ingest (deterministic pre-check); otherwise the `DocsIntentRouter` LLM-classifies the text → `doc-finder` search or the chat fallback (#475). |
| GET | `/agents/docs/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`doc-archiver`** (`domains/docs/skills/doc-archiver/SKILL.md`) — strict-JSON extract of the archive
  metadata from a document's OCR text + the user's note.
- **`doc-finder`** (`domains/docs/skills/doc-finder/SKILL.md`) — strict-JSON distil of a search query +
  optional docType filter from a "find my X" request.

## Env

| Var | Default | Purpose |
|---|---|---|
| `DOCS_AGENT_PORT` | `8117` | HTTP port. |
| `MCP_DOCS_URL` | `http://mcp-docs:8116` | docs domain-MCP (its data — `/internal/documents`). |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared media capability (`/internal/ocr`). |
| `DOCS_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | public base for a search hit's open link (`<base>/v1/media/{mediaId}`). |
| `DOCS_AGENT_MCP_CLIENT_ENABLED` | `true` | bind mcp-docs + mcp-media-processing over MCP/SSE (toggle off in degraded envs). |
| `DOCS_AGENT_MEMORY_RECALL_K` | `5` | memory-recall fan-in (shared agent-runtime). |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway for the metadata extract. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | shared agent-runtime clients. |

## Key classes

- `DocsAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/DocsAgentProperties` (`docs-agent.*` base URLs) + `config/OutboundHttpConfig`
  (`mcpDocsWebClient` + `mcpMediaProcessingWebClient`).
- `http/OcrClient` — `POST /internal/ocr` → `OcrResult` (mirrors finance `CaptionClient`).
- `http/DocumentClient` — `POST /internal/documents` → `DocumentDto` (mirrors `BriefingProfileClient`).
- `archive/DocArchiver` — the ingest flow: OCR → LLM metadata extract (`doc-archiver` SKILL,
  temperature=0) → **resolve shared vs personal `household_id`** (`SharingResolver.resolve` + `DocsSharingPolicy`,
  ADR-0002 slice 7a; degrades to the envelope household on a profile hiccup) → `saveDocument` under that
  household (stores the full OCR text) → second-brain note seed (`MemoryClient.note`, `source=docs-agent`,
  body = OCR text, `frontmatter={kind:document, refId}`); soft-fails per stage. The save+note persist lives in
  one `persist(...)` helper shared with the resume path. **Confirm-on-ambiguity (item 8, DS-N):** when the type
  is `other`/unreadable the policy abstains → `resolve` returns `NeedsConfirm` → the archive is deferred (not
  saved) and the agent asks "«…» — личное или общее?" via the shared `SharingConfirm`, stashing the OCR corpus +
  metadata draft; `finishArchive` (from `ResumeController`) files it into the chosen household on the reply +
  learns. **Why-trace (#485/G2):** a successful archive attaches a payload-free `IntentResponse.trace`
  "wrote: archived a document"; a DS-N defer and failures set none (→ the explain answer falls back to routing-only).
- `web/ResumeController` — `POST /agents/docs/resume` (docs' **first** resume surface, added by DS-N). Hit by
  the orchestrator when the user replies to the route-locked docs confirm; dispatches `sharing-confirm` →
  `SharingConfirm.resume` with `DocArchiver::finishArchive`. A null `pendingAction` clears the lock.
- `sharing/DocsSharingPolicy` — docs' `DefaultSharingPolicy` (ADR-0002 slice 7): the default privacy of a
  document by `docType` — warranty/contract → shared (household asset), receipt/note → private. The only "what
  is shared here" rule docs owns; the routing mechanism lives in `libs/sharing`. **DS-N:** `maybeDecide` abstains
  on `other`/blank/unreadable so the resolver asks instead of silently filing a privacy boundary.
- `config/OutboundHttpConfig` also wires the sharing beans (`ProfileSharingClient` over the shared
  `profileServiceWebClient` + `SharingResolver` with `DocsSharingPolicy` + the DS-N `SharingConfirm` over the
  resolver + `ObjectMapper`). **Memory-driven default (item 8,
  DS-4):** `DocsSharingPolicy` is wrapped in `libs/sharing`'s `LearnedSharingPolicy` and the resolver uses
  its learning-enabled constructor (+ a `SharingLearningClient` bean over the shared
  `memoryServiceWebClient`), so a document with no explicit household/personal signal defaults to the
  owner's learned choice for the same signal profile once the tally is deep + decisive, else the static
  doc-type rule; explicit choices are recorded. Both best-effort — routing mechanism unchanged. Mirrors
  calendar / finance / tasks / nutrition.
- `find/DocFinder` — the search flow: LLM query distil (`doc-finder` SKILL, temperature=0) → resolve the
  household set via `read/DocReads` (ADR-0002 slice 7b) → parallel trigram `searchDocuments` +
  memory-service `recall` (SB-5; a `{kind:note, refId}` hit → `MemoryClient.getNote` → its `frontmatter`
  `{kind:document, refId}` → `DocumentClient.get`), each unioned across the set, merged + de-duplicated →
  a hit list with open links; each source soft-fails independently.
- `read/DocReads` — the sharing-aware read helper (ADR-0002 slice 7b, sibling of finance `SpendingReads`
  / tasks `TaskReads` / nutrition `MealReads`): `households(envelope, userId, shared)` resolves the set
  (own = envelope; shared = personal ∪ shared via `ProfileSharingClient.households`, degrading to envelope)
  + `searchUnion(...)` fans the trigram search across the set.
- `chat/DocsChat` — the open-question fallback (AGENT.md system prompt).
- `intent/DocsIntentRouter` — a thin binding over the shared `agent-runtime` `SkillRouter` (#475): the
  text-intent router (find vs chat) for docs. Its dispatch map holds only `doc-finder` (`doc-archiver` is
  attachment-gated, excluded like creator's hub-invoked skill); the `FAMILY_CUES` family/own read-scope
  stays a deterministic keyword match applied inside the finder dispatch lambda (a read-breadth choice,
  never a routing/privacy decision → kept off the LLM classifier).
- `web/IntentController` — image attachment → archive (deterministic pre-check); otherwise delegates to
  `DocsIntentRouter`; `web/ManifestController`.
