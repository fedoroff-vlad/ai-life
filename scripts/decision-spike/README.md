# decision-spike — the ADR-0010 calibration harness

**Status (dev-only tooling, 2026-10-29).** Measures whether an open-weight **typed-decision model**
(System One / `/v1/systemone`) can replace the strict-JSON classifier prompts that
[ADR-0010](../../plans/adr/ADR-0010-typed-decision-channel.md) is about. It is **not** part of the
build, not a module, and nothing in the system calls it: a standalone stdlib-only Python file plus a
frozen case set, run by hand against a self-hosted engine. The findings it produced are recorded in
ADR-0010 §Spike result; the raw runs are in [`results/`](results).

Keep it dev-only. If the `decision` channel is ever built, its *behaviour* belongs in a
`Golden*DecisionTest` in the module that owns the surface, like every other model-facing assertion —
not here.

## What it measures

Per family (one classifier surface = one family):

| Metric | Why it is the metric |
|---|---|
| `accuracy_asserted` | accuracy on the labels an **existing golden test asserts today** — the headline, because that gate is currently green, so this is the number a swap has to match |
| `accuracy_all_labelled` | adds labels inferred by the spike author from structure-only golden cases — secondary, softer |
| `mean_confidence_correct` / `_wrong` | the calibration signal. A threshold is only a usable design if these separate |
| `calibration` cases | cases where the repo **recorded genuine ambiguity** (e.g. #395's account-less balance request). They assert no label: the question is whether the engine is *unsure*, not whether it is right |
| `mean_server_ms` | the engine's own `decis.latency_ms`, not the wall time |
| `max_input_tokens` | how close the real option set sits to the engine's per-question limit |
| `batching_probe` | 1 question vs 3 in one request — whether batching is actually cheaper than separate calls |

Every case is lifted **verbatim** from a golden test; `frozen_set.json` names the source file per
family and marks each case `asserted` / `inferred` / `calibration`. Do not add cases by writing new
phrasings — add them by lifting from a golden, or the headline metric stops meaning "matches the
gate".

## Running it

Any server speaking the System One contract works. What the recorded runs used:

```bash
# one engine per image, weights baked in; ~7-10 GB per image, CPU-only on this box
docker run -d --rm -p 127.0.0.1:8000:8000 -e DECIS_API_KEY=local \
  --name decis chaitin/decis:kev-0.8b
curl -fsS --retry 90 --retry-delay 3 --retry-all-errors --retry-connrefused \
  http://127.0.0.1:8000/readyz

python -I scripts/decision-spike/run_spike.py \
  scripts/decision-spike/frozen_set.json /tmp/result.json \
  --label "kev-0.8b CPU, full criteria"
```

Flags: `--criteria-mode full|short|names` (how much of each option's description to send — `names`
sends none, letting the engine decide on the option name alone), `--criteria-lang en|ru` (uses a
family's `criteria_ru`/`instructions_ru` when it has them), `--only <family>[,<family>]`,
`--url`, `--key`.

Use `python -I` so the interpreter does not pick code up from the working directory.

## The recorded runs (2026-10-29, dev VDI, CPU-only)

Findings and their consequences live in ADR-0010 §Spike result; this is only what was executed, so a
re-run can be compared like for like.

| Run | Engine / command | Result file |
|---|---|---|
| 1 | `decis:laya-multilingual`, full criteria, all families | `results/result_laya_full.json` |
| 2 | `decis:laya-multilingual`, English control family | `results/result_laya_en.json` |
| 3 | `decis:laya-multilingual`, `--criteria-lang ru` | `results/result_laya_rucrit.json` |
| 4 | `decis:kev-0.8b`, full criteria, all families | `results/result_kev_full.json` |
| 5–7 | **baseline**: `scripts/golden.sh -pl domains/tasks/tasks-agent -Dtest=GoldenRoutingTest`, three times | not kept (maven logs) — 119.6 s / 142.2 s / 78.52 s, **1 failure in 3** |

The baseline runs are the other half of any claim made here: the incumbent is a real model on the same
box on the same day, and it flakes. Always re-measure it **beside** the candidate, several times — a
single comparison run cannot tell a candidate's miss from the incumbent's flake.

## Reading a result

`accuracy_asserted` is the only number to quote next to "the gate is green". Watch three traps the
recorded runs walked into:

1. **The option set may not fit.** A family is rejected with HTTP 422 when the question exceeds the
   engine's `max_question_tokens`; `GET /v1/models` reports the limit per engine. Russian
   descriptions cost more tokens than English ones for the same text.
2. **Confidence is not comparable across families.** A 22-option surface spreads probability mass
   far thinner than a 10-option one, so a threshold has to be **per surface**, never one global
   number.
3. **Absolute latency here is a float32-on-CPU number** (`decis.device` / `decis.dtype` say which),
   on a box with no GPU. It bounds nothing about the deploy host.
