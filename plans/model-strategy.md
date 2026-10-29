# Model strategy — local LLM stack for the Mac deploy

> **Status: decided (2026-07-28), amended 2026-10-29 — ONE model serves both tenants.** The owner's
> decision: a single universal model answers both ai-life and the coding agent; there is no separate
> resident coder model and no planned downshift between them. The reasoning and what it changes are in
> §One model for both tenants below, and the consequence for `lifecycle.md`'s LC-4 (demoted to a
> safety valve, kept but off the default path) is recorded there. A second requirement landed with the
> same decision: the model must be reachable **off-host** from the owner's work machine →
> [lifecycle.md](lifecycle.md) §Off-host access.
>
> **Forward-looking.** The Mac isn't purchased yet
> (hardware-blocked — see [STATUS.md](STATUS.md) / [lifecycle.md](lifecycle.md)). This file is the
> **rationale + per-channel model map + rollout**. It is **not** the tag SSOT: runtime model tags live
> in [`infra/.env.mac.example`](../infra/.env.mac.example) (the `llm-model-tag` coupling in
> `.skills/change-map.yaml`). Deploy / hot-cold / model-profile downshift live in
> [lifecycle.md](lifecycle.md); the channel doctrine + provider-switch rule in
> [architecture.md](architecture.md) §LLM strategy. Model landscape moves fast — figures below are
> **directional** (4-bit MLX/GGUF, as of 2026-07); re-validate at deploy.

## The box (constraint)
Mac Studio M4 Max, 64 GB unified → **~48 GB GPU model ceiling**, shared with the JVMs + backing.
**The box is fixed at 64 GB** (bought, arriving — no larger config planned). 70B-class does not fit.
The stack is **MoE-first**: a Mixture-of-Experts model activates only ~3B of its params per token, so
it delivers ~dense-32B quality at small-model speed and memory on Apple Silicon — and on Apple Silicon
a dense model of the same quality costs ~5–10× the throughput, which a 24/7 interactive assistant
cannot pay.

Since 2026-10-29 the budget holds **one** chat model rather than two (§One model for both tenants), so
the ceiling is no longer the binding constraint it was — throughput and KV-cache headroom are.

## Per-channel picks
Four LLM channels go through `llm-gateway` (`default`, `fast`, `vision`, `embedding`); STT is separate
(inside `mcp-media-processing`); the coder is a separate tenant (the `coding-agent` repo).

> These are the **deploy targets**, adopted at the Mac cutover. Until then
> [`infra/.env.mac.example`](../infra/.env.mac.example) deliberately runs the **interim validated tags**
> (qwen3:32b / qwen3:8b / minicpm-v / nomic-embed-text 768-dim) — swapping to the picks below is
> hardware-gated (and the embedding change needs a reindex + an `embed-1024` migration). See #600.

| Channel | Pick | ~Q4 size | Why |
|---|---|---|---|
| **default** (reasoning / agentic / synthesis) | **Qwen3.6-35B-A3B** (MoE, 35B/~3B act) | ~20 GB | same MoE-A3B slot as 3.5 at the same footprint, one generation newer (Apr 2026): stronger agentic/reasoning **and** ~40% less KV-cache at long context via Gated DeltaNet hybrid attention (a real RAM win on this box); Apache-2.0; strong RU; natively multimodal (can double as vision — see Plan B). Thinking-toggle → run with `LLM_SUPPRESS_THINKING` for strict-JSON skills. Bumped from 3.5-35B-A3B 2026-09-01 (still the deploy target, not yet in the interim env tags). |
| **fast** (routing / classify / JSON) | **Qwen3.5-9B** (non-thinking), or reuse the A3B | ~6 GB | non-thinking is safer for the strict-JSON classifier surface; the A3B is fast enough to **double as fast** and drop a model if we want fewer. Take the 3.6 small-series equivalent if one ships. |
| **vision** (receipts / docs / outfits) | **Qwen3-VL 8B** | ~7 GB | best-in-class OCR for receipts/documents; replaces the current `minicpm-v`. |
| **embedding** (second-brain recall, RU) | **Qwen3-Embedding-0.6B** (or `bge-m3`) | <1 GB | tops MTEB v2, strong Russian. ⚠️ dim change from `nomic-embed-text` (768→1024) → **reindex + a pgvector Liquibase migration**, not free. |
| **stt** | **whisper large-v3-turbo** | ~6 GB | ~5× large-v3, 99 languages incl. good Russian; runs in `mcp-media-processing`, outside the gateway. |
| ~~**coder** (separate tenant)~~ | ~~Qwen3-Coder-30B-A3B~~ → **retired 2026-10-29: the `default` pick above serves the coding agent too** | — | §One model for both tenants. The coding agent calls the same `default` channel; at work it keeps using the employer gateway's `openai:` tier, so a local coder model would have served only home sessions. |

## Memory budget (why 5 configured ≠ 5 resident)
Ollama loads a model on demand and evicts idle ones (`keep_alive`), so only the actively-used channels
sit in VRAM; only **one pick is heavy** (the `default` MoE). Realistic resident peaks (models only):

- **normal operation, either tenant:** embedding 0.7 + fast 6 + default 19.5 ≈ **~26 GB** (+ vision 7
  on a photo, or whisper 6 on voice — brief spikes).
- Plus ~15 GB (OS + hot JVMs + backing) → ~41 GB, with the rest of the 64 GB free for **KV cache**.
- There is no longer a "both tenants hot" worst case: both tenants share the same resident weights, so
  a second concurrent session costs its KV cache, not another ~19 GB of model.

## One model for both tenants (owner decision, 2026-10-29)
**Decision: one universal model — the `default` pick above — answers both ai-life and the coding agent.
No separate resident coder model, and the LC-4 downshift is demoted from a requirement to a safety
valve** (kept in code, off the default path → [lifecycle.md](lifecycle.md) §B).

The question the owner asked was why downshift at all, given that one model at work serves 20–30 people
with several sessions each. That observation is about **concurrency, not capacity**: one resident model
serves many sessions through batching, because weights are paid once and each session costs only its KV
cache. Applied here it does not argue for a smaller model during coding — it argues against the second
model existing.

Decomposed, the two-model plan only ever bought something in one case:

| Situation | Two models | One shared model |
|---|---|---|
| One tenant active (the norm — the owner is either coding or chatting) | Ollama's own `keep_alive` eviction already suffices; **LC-4 was unnecessary here** | nothing to evict |
| Both at once (rare) | needs LC-4 or both sit resident ≈38 GB and the box thrashes | **no problem at all** — requests queue on one model |

So one model collapses both cases, and the whole evict-before-load apparatus exists for a case that no
longer arises. What this buys, concretely:

- **~19 GB resident instead of ~38** — and the point is not "space freed" but space freed **for KV
  cache**: longer context and more concurrent sessions, which is exactly the property the 20–30-user
  observation demonstrates.
- **The cross-repo coupling goes dormant.** LC-4's caller is `lifecycle.py` in the `coding-agent` repo;
  CLAUDE.md's change-propagation map already flags that half as invisible from here and therefore
  silently drifting. One fewer live coupling.
- One fewer model in the `llm-model-tag` cascade.

What it costs, and why that cost is smaller here than it looks: a coder-tuned model is better at code.
But the coding agent uses the employer gateway at work, so a local coder model would only ever have
served home sessions; and the `default` pick is chosen for agentic/reasoning strength, not as a chat
model. The one real downside is **latency under genuine concurrency** — a short assistant reply can
queue behind a long code generation. Mitigated by `OLLAMA_NUM_PARALLEL`, and cheap at ~3B active
params.

**What the freed budget does NOT unlock** (checked against §Rejected below, so this is not re-litigated
at deploy): the next tiers up still do not fit. GLM 5.2 Air is ~55–60 GB in Q4 and DeepSeek V4 Flash
needs ~90 GB+. The freed ~18.5 GB makes a **~40 GB-class MoE** thinkable for the first time if one
ships — that, and nothing larger.

**Open, decided at deploy:** whether the general `default` is good enough at code in this monorepo for
the owner's taste. That is a **quality** judgement, not a memory one, and it is the only thing that
would reinstate a coder model. Test it with a real task from the Java monorepo before concluding.

## Image generation — a separate model, not the LLM
Image **generation** (creating pictures — e.g. the stylist's outfit visualisations) is **not** something the
default LLM does. Qwen3.6 and Gemma 4 are multimodal for **understanding** (image/video *in*), not generation
(image *out*) — a common mix-up (so "Gemma 4 can generate" is wrong; it reads images, doesn't draw them).
Generating an image needs a **dedicated diffusion/image model**, which the architecture already isolates
behind the `mcp-image-gen` capability-MCP (real engine + stylist binding tracked by
[#293](https://github.com/fedoroff-vlad/ai-life/issues/293), GPU-gated). It is a **cold, on-demand**
capability (stylist is a cold agent), so it loads only on a request and never sits in the resident 64 GB
budget — no change to the LLM channel math above.
- **Engine pick (local, M4 Max, MLX/MPS):** **Qwen-Image / Qwen-Image-Edit** — same lab as the default LLM,
  local leader on text-in-image, permissive licence; **FLUX.2 [klein]** — strongest aesthetics, permissive;
  **SANA** — tiny/fast for quick iteration. All run via Diffusers-MPS / ComfyUI / native Metal (e.g. mlx-gen).
- **Stylist try-on caveat:** accurate *garment* virtual try-on is a specialised, harder task with limited
  local open-weight options today. The stylist MVP starts with **general outfit visualisation** (Qwen-Image/
  FLUX from a text/reference prompt); true try-on is a later slice — matches #293 being deferred.

## Plan B / watch list
Consolidation options (default + vision in one model, dropping a resident model):
- **Qwen3.6-35B-A3B itself is natively multimodal** — the default could also serve vision, dropping the
  separate `Qwen3-VL 8B` (~7 GB saved) with no philosophy change and no speed cost (still A3B). **Preferred
  consolidation** — validate OCR/receipt quality at deploy; if it lags a dedicated VL, keep Qwen3-VL 8B.
- **Gemma 4 26B-A4B** (MoE + multimodal, non-thinking → JSON-safe) — a lighter/faster alternative
  (~15 GB), but Gemma licence (not Apache) + weaker RU than Qwen; a fallback, not a default.
- **Gemma 3 27B** (dense, multimodal) — conservative consolidation; dense 27B is slow on Apple Silicon,
  same downside as the rejected dense-27B class below.

Watch-list trigger (revisit the default only when it fires):
- **A Qwen 3.8 / Qwen4 model in the A3B-MoE ~30–35B format** — still the only reason to jump off 3.6.
  `3.8-Max`/`2.4T-A95B` don't fit; `3.8-27B` is **dense** (slow on Apple Silicon, see Rejected).
- ~~`Qwen3.8-Flash-Next`~~ — **trigger fired and resolved 2026-10-29: it does not fit.** 125B MoE with
  6B active, plus a 51B n-gram embedding table and a 4B MTP head ≈ **180B stored**; a quantized build
  was observed at **~61 GB**. The 6B-active figure makes it fast but does **not** shrink residency —
  the classic MoE mistake. Out for a 64 GB box that also hosts the JVMs. Closed, not watched.
- **NEW candidate — `Laguna XS 2.1` (poolside), the one worth a bench at deploy.** 33B total / **3B
  active** MoE built explicitly for *agentic coding on local machines* — i.e. the same footprint slot
  as our default (~36 GB at Q8, so ~Q4 lands in the 19–20 GB band). Vendor-reported SWE-bench
  Multilingual 63.1%, Terminal-Bench 2.1 33.4; 256K context on the hosted API, local long-context
  depends on the runner. **Caveats that keep it off the default slot:** benchmarks are vendor-reported,
  there is **no Russian evaluation** published, and the licence is OpenMDW-1.1 **plus an Acceptable Use
  Policy** — permissive-ish but not OSI-classic, against a stack that is otherwise Apache-2.0. It is a
  *coding* model, so adopting it as the single universal model would make the assistant side the
  compromise — exactly the coder-first option the owner rejected. Treat it as **the fallback if the
  general default disappoints at code**, not as a contender for the default.

Rejected for the 64 GB box (recorded so we don't re-litigate at deploy — the box is **fixed at 64 GB**):
- **Dense ~27–30B (Qwen3.8-27B, Meta Muse Glimmer 30B)** — all params active per token → **~5–15 tok/s on
  M4/M4 Max**, vs ~50–130 tok/s for MoE-A3B. Newer-gen quality doesn't buy back a ~5–10× throughput loss on
  a 24/7 interactive assistant. (Muse Glimmer also benches mediocre, ~#119/228, and Meta is weak in RU.)
- **DeepSeek V4 Flash** (284B-A13B, MIT) — needs ~90 GB+ (2-bit floor ~81 GB); on 64 GB it streams experts
  from disk and crawls. Great model, wrong box.
- **GLM 5.2 Air (~106B, MIT)** — ~55–60 GB Q4 eats almost the whole 64 GB, leaving no room for the ~6 GB
  hot-set JVMs + backing + coder tenant.
- **Kimi K2.6** — top of the leaderboard but needs 8× H100; not a local model.

Added by the 2026-10-29 scan, now that **one** model has to serve both tenants (so "it also has to code"
is a hard requirement, and bigger-is-better is tempting):
- **`gpt-oss-120b`** — the loudest "best for 64 GB" recommendation in blog-land, and **wrong for this
  box**: the 6.5-bit MLX build measures **~95 GB**. Even where it fits (a 128 GB machine) it runs
  ~30 tok/s against Qwen3.6-35B-A3B's >50, and it **loses on coding** — SWE-bench Verified ~62.4% vs
  ~73.4%, and −11.5 points on one coding aggregate. Bigger, slower, worse at the job. Out.
- **`Mistral Small 4` (119B-A6B, ~60 GB)** and **`Llama 4 Scout` (109B/17B active, ~58 GB Q4)** — both
  eat essentially the whole box before the JVMs; same failure mode as GLM 5.2 Air. Out.
- **`Qwen3.8-27B` (dense)** — newer generation, but dense means every param active per token: the
  ~5–10× Apple-Silicon throughput penalty that already rejected this whole class. Out.

**Net of that scan: the existing `default` pick stands, and is now better justified than when it was
made** — it beats a model 3× its size at coding while fitting with room to spare.

## Rollout (config swap, low-risk)
This is the `llm-model-tag` coupling — a config change, not code:
1. Enable Ollama's **MLX engine** (Apple Silicon); pull the MoE tags.
2. Update the coupling in one PR: `infra/.env.mac.example` (SSOT) · `infra/.env.example` ·
   `scripts/golden.sh` · [`platform/llm-gateway/README.md`](../platform/llm-gateway/README.md) ·
   [lifecycle.md](lifecycle.md) · this file. Retiring a tag → add it to `RETIRED_TAGS` in
   `scripts/check-consistency.sh`.
3. **Re-run goldens** (`scripts/golden.sh`) against the new `default` — the models "think" differently, so
   strict-JSON + tool-routing must survive (`LLM_SUPPRESS_THINKING` on). **This is the first place the
   full lane can actually run.** On the dev VDI (no GPU) `qwen3:8b` Q4 measures **~7.4 tok/s** generation
   (2026-09-24), so only the cheap subset — `Golden*RoutingTest` / `Golden*InjectionResistanceTest` — is
   runnable there; the generation-heavy ones fail on *timeouts*, not on content (a full-reactor attempt
   died after 13 classes: `GoldenAdvisorSynthesisTest` soft-failed on the advisor's own timeout and
   finance's `GoldenRoutingTest` blew its 120 s `block`). Detail + the per-class recipe →
   [`platform/llm-gateway/README.md`](../platform/llm-gateway/README.md) §Golden tests. Budget the lane as
   deploy work, and treat any local heavy-golden red as unproven rather than as a regression.
4. **Measure two-tenant residency** on the Mac → decide whether to keep or retire the downshift.

## Runtime note
Stay on **Ollama on its MLX engine** (Apple Silicon, preview since 2026-03): keeps the openai-compatible
API + the Ollama-native evict-before-load handshake LC-4 depends on (`ollama stop` / `/api/ps`), while
gaining MLX speed. Switching to LM Studio / `mlx_lm.server` / `llama-server` would change the eviction
contract → integration cost; only if the Ollama MLX engine underperforms.

## Sources

**2026-10-29 scan (triggered by the one-model decision; the field is noisy and much of it is SEO-grade,
so every figure here is directional and vendor-reported unless it says measured).** Covered:
`Qwen3.8-Flash-Next` (official Qwen blog + HF card + the Qwen3.8-Next architecture paper
arXiv:2608.30320 — 125B/6B-active, 51B n-gram embeddings, 4B MTP head, 262K native context; released
2026-08-26, *not* October as rumoured), `gpt-oss-120b` vs `Qwen3.6-35B-A3B` (Artificial Analysis
comparison + MLX community 6.5-bit build at ~95 GB + two independent local runs), `Laguna XS 2.1`
(poolside blog + HF card + OpenRouter listing: 33B-A3B, OpenMDW-1.1 + AUP, SWE-bench Multilingual
63.1%), plus the Apple-Silicon round-ups that put Qwen3.6-35B-A3B at ~45 tok/s on an M4 Max via MLX and
~24 GB at Q4_K_M. **Re-verify tok/s, RU quality and coding on the actual box at deploy** — no figure in
this file was measured on this hardware.

### Earlier scan (as-of 2026-09-01, noisy field — treat figures as directional)
Qwen3.5/3.6/3.8 lineup + MLX sizing; MLX vs llama.cpp/Ollama on Apple Silicon; open-weight VLMs (Qwen3-VL /
Gemma / GLM); embedding leaderboards (Qwen3-Embedding / bge-m3); STT (whisper v3-turbo vs Parakeet).
2026-09-01 competitive scan (Plan B) covered Qwen3.6/3.8, DeepSeek V4 Flash, GLM 5.2 Air, Meta Muse
Glimmer 30B, Gemma 4, Kimi K2.6 — confirmed the fits-in-64GB-fast-MoE-with-strong-RU slot is still Qwen's;
the "much cooler" models (DeepSeek V4, GLM 5.2, Kimi) all need far more than 64 GB. Re-verify tok/s + RU at deploy.
