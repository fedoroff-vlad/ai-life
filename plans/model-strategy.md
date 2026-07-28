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

| Channel | Pick | ~Q4 size | Why |
|---|---|---|---|
| **default** (reasoning / agentic / synthesis) | **Qwen3.5-35B-A3B** (MoE, 35B/~3.3B act) | ~19.5 GB | dense-32B quality at ~3B active → **2–3× faster** than a dense 27–32B at the same footprint; Apache-2.0; strong RU. Thinking-toggle → run with `LLM_SUPPRESS_THINKING` for strict-JSON skills. |
| **fast** (routing / classify / JSON) | **Qwen3.5-9B** (non-thinking), or reuse the A3B | ~6 GB | non-thinking is safer for the strict-JSON classifier surface; the A3B is fast enough to **double as fast** and drop a model if we want fewer. |
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
- **Gemma 4 26B-A4B** (MoE + multimodal) — could serve **default + vision in one model** (and it's
  non-thinking → JSON-safe), but it's fresh/less-verified. **Re-evaluate when the Mac arrives** — it may be
  properly benchmarked by then, or a newer model may land.
- **Gemma 3 27B** (dense, multimodal, non-thinking) — the conservative default+vision consolidation; trades
  speed (dense 27B is slower) for one fewer model + JSON-safety.

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

## Sources (as-of 2026-07, noisy field — treat figures as directional)
Qwen3.5/3.6 lineup + MLX sizing; MLX vs llama.cpp/Ollama on Apple Silicon; open-weight VLMs (Qwen3-VL /
Gemma / GLM); embedding leaderboards (Qwen3-Embedding / bge-m3); STT (whisper v3-turbo vs Parakeet). Some
names (Gemma 4, GLM-5, DeepSeek V4) are fresh or semi-announced — verify before adopting.
