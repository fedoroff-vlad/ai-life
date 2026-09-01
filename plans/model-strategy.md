# Model strategy — local LLM stack for the Mac deploy

> **Status: decided (2026-07-28), forward-looking.** The Mac isn't purchased yet
> (hardware-blocked — see [STATUS.md](STATUS.md) / [lifecycle.md](lifecycle.md)). This file is the
> **rationale + per-channel model map + rollout**. It is **not** the tag SSOT: runtime model tags live
> in [`infra/.env.mac.example`](../infra/.env.mac.example) (the `llm-model-tag` coupling in
> `.skills/change-map.yaml`). Deploy / hot-cold / model-profile downshift live in
> [lifecycle.md](lifecycle.md); the channel doctrine + provider-switch rule in
> [architecture.md](architecture.md) §LLM strategy. Model landscape moves fast — figures below are
> **directional** (4-bit MLX/GGUF, as of 2026-07); re-validate at deploy.

## The box (constraint)
Mac Studio M4 Max, 64 GB unified → **~48 GB GPU model ceiling**, shared with the JVMs + backing +
(occasionally) the coder tenant. 70B-class does not fit; 128 GB would be needed to hold two dense-32B
resident. The stack is therefore **MoE-first**: a Mixture-of-Experts model activates only ~3B of its
params per token, so it delivers ~dense-32B quality at small-model speed and memory on Apple Silicon.

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
| **coder** (separate tenant) | **Qwen3-Coder-30B-A3B** (MoE) | ~18.5 GB | keep — MoE, ~130 tok/s on MLX; coexists with the ai-life MoE inside the budget. |

## Memory budget (why 6 configured ≠ 6 resident)
Ollama loads a model on demand and evicts idle ones (`keep_alive`), so only the actively-used channels
sit in VRAM; only **two picks are heavy** (the MoE pair). Realistic resident peaks (models only):

- **ai-life, normal chat:** embedding 0.7 + fast 6 + default 19.5 ≈ **~26 GB** (+ vision 7 on a photo, or
  whisper 6 on voice — brief spikes).
- **ai-life + coder both hot (rare):** + coder 18.5 ≈ **~45 GB** without downshift, **~34 GB** with the
  LC-4 downshift.
- Plus ~15 GB (OS + hot JVMs + backing) → fits 64 GB with margin; "both tenants fully hot at the same
  instant" is the rare worst case (usually one tenant is idle and evicts).

## Key finding — MoE softens the two-tenant problem
Both the ai-life default and the coder are now MoE-A3B (~19 GB each, ~3B active). Two resident ≈ **38 GB
of models < the 48 GB ceiling** → the LC-4 32B↔14B **downshift may become optional** rather than
mandatory. **Do not retire it on paper:** measure two-tenant residency live at deploy first; keep LC-4 as
a safety valve until proven. This is the real "freed resources" — architectural (MoE), not from collapsing
JVM services (the hot set is only ~6 GB).

## Plan B / watch list
Consolidation options (default + vision in one model, dropping a resident model):
- **Qwen3.6-35B-A3B itself is natively multimodal** — the default could also serve vision, dropping the
  separate `Qwen3-VL 8B` (~7 GB saved) with no philosophy change and no speed cost (still A3B). **Preferred
  consolidation** — validate OCR/receipt quality at deploy; if it lags a dedicated VL, keep Qwen3-VL 8B.
- **Gemma 4 26B-A4B** (MoE + multimodal, non-thinking → JSON-safe) — a lighter/faster alternative
  (~15 GB), but Gemma licence (not Apache) + weaker RU than Qwen; a fallback, not a default.
- **Gemma 3 27B** (dense, multimodal) — conservative consolidation; dense 27B is slow on Apple Silicon,
  same downside as the rejected dense-27B class below.

Watch-list triggers (revisit the default only when one fires):
- **A Qwen 3.8 / Qwen4 model in the A3B-MoE ~30–35B format** — the only reason to jump off 3.6. Today's
  3.8 has no fitting fast variant: `3.8-Max`/`2.4T-A95B` don't fit, `3.8-27B` is **dense** (slow, below).
  `Qwen3.8-Flash-Next` (MoE, "Qwen4 preview") is the one to watch.
- **A 128 GB Mac instead of 64 GB** — changes the whole slot: unlocks **GLM 5.2 Air** (~106B MoE, MIT,
  ~30 tok/s) and **DeepSeek V4 Flash** (284B-A13B, MIT, strong RU reasoning) — both need 96–128 GB and are
  out of reach on 64 GB. Re-run this whole file if the box grows.

Rejected for the 64 GB interactive default (recorded so we don't re-litigate at deploy):
- **Dense ~27–30B (Qwen3.8-27B, Meta Muse Glimmer 30B)** — all params active per token → **~5–15 tok/s on
  M4/M4 Max**, vs ~50–130 tok/s for MoE-A3B. Newer-gen quality doesn't buy back a ~5–10× throughput loss on
  a 24/7 interactive assistant. (Muse Glimmer also benches mediocre, ~#119/228, and Meta is weak in RU.)
- **DeepSeek V4 Flash** — 2-bit floor ~81 GB, practical 96–128 GB; on 64 GB it streams experts from disk
  and crawls. Great model, wrong box (see 128 GB trigger).
- **GLM 5.2 Air (~106B)** — ~55–60 GB Q4 eats almost the whole 64 GB, leaving no room for the ~6 GB hot-set
  JVMs + backing + coder tenant. (See 128 GB trigger.)
- **Kimi K2.6** — top of the leaderboard but needs 8× H100; not a local model.

## Rollout (config swap, low-risk)
This is the `llm-model-tag` coupling — a config change, not code:
1. Enable Ollama's **MLX engine** (Apple Silicon); pull the MoE tags.
2. Update the coupling in one PR: `infra/.env.mac.example` (SSOT) · `infra/.env.example` ·
   `scripts/golden.sh` · [`platform/llm-gateway/README.md`](../platform/llm-gateway/README.md) ·
   [lifecycle.md](lifecycle.md) · this file. Retiring a tag → add it to `RETIRED_TAGS` in
   `scripts/check-consistency.sh`.
3. **Re-run goldens** (`scripts/golden.sh`) against the new `default` — the models "think" differently, so
   strict-JSON + tool-routing must survive (`LLM_SUPPRESS_THINKING` on).
4. **Measure two-tenant residency** on the Mac → decide whether to keep or retire the downshift.

## Runtime note
Stay on **Ollama on its MLX engine** (Apple Silicon, preview since 2026-03): keeps the openai-compatible
API + the Ollama-native evict-before-load handshake LC-4 depends on (`ollama stop` / `/api/ps`), while
gaining MLX speed. Switching to LM Studio / `mlx_lm.server` / `llama-server` would change the eviction
contract → integration cost; only if the Ollama MLX engine underperforms.

## Sources (as-of 2026-09-01, noisy field — treat figures as directional)
Qwen3.5/3.6/3.8 lineup + MLX sizing; MLX vs llama.cpp/Ollama on Apple Silicon; open-weight VLMs (Qwen3-VL /
Gemma / GLM); embedding leaderboards (Qwen3-Embedding / bge-m3); STT (whisper v3-turbo vs Parakeet).
2026-09-01 competitive scan (Plan B) covered Qwen3.6/3.8, DeepSeek V4 Flash, GLM 5.2 Air, Meta Muse
Glimmer 30B, Gemma 4, Kimi K2.6 — confirmed the fits-in-64GB-fast-MoE-with-strong-RU slot is still Qwen's;
the "much cooler" models (DeepSeek V4, GLM 5.2, Kimi) all need 96–128 GB+. Re-verify tok/s + RU at deploy.
