"""ADR-0010 calibration spike: run the frozen routing set against a Decis /v1/systemone engine.

Usage:  python -I run_spike.py <frozen_set.json> <out.json> [--url http://127.0.0.1:8000] [--key local]

Measures, per family: accuracy on repo-ASSERTED labels (headline), accuracy on all labelled
cases, mean confidence split by correct/incorrect (the calibration signal), and latency.
Calibration cases assert no label - they only report confidence, to see whether a recorded
ambiguity comes back as low confidence rather than a confident mistake.
"""
import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request


def post(url, key, payload, timeout=180):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"authorization": "Bearer " + key, "content-type": "application/json"},
        method="POST",
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            out = json.loads(r.read().decode("utf-8"))
        return out, (time.perf_counter() - t0) * 1000.0, None
    except urllib.error.HTTPError as e:
        return None, (time.perf_counter() - t0) * 1000.0, "HTTP %s: %s" % (
            e.code, e.read().decode("utf-8", "replace")[:400])
    except Exception as e:  # noqa: BLE001 - a spike wants the message, not a traceback
        return None, (time.perf_counter() - t0) * 1000.0, repr(e)


def get(url, key, timeout=60):
    req = urllib.request.Request(url, headers={"authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8")), None
    except Exception as e:  # noqa: BLE001
        return None, repr(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frozen")
    ap.add_argument("out")
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    ap.add_argument("--key", default="local")
    ap.add_argument("--label", default="")
    ap.add_argument("--criteria-mode", default="full", choices=["full", "short", "names"],
                    help="full = the descriptions as written; short = first 40 chars; "
                         "names = null values (the engine decides by the option name alone)")
    ap.add_argument("--criteria-lang", default="en", choices=["en", "ru"],
                    help="ru uses the family's instructions_ru/criteria_ru when it has them")
    ap.add_argument("--only", default="", help="comma-separated family names to run")
    a = ap.parse_args()

    with open(a.frozen, encoding="utf-8") as f:
        fs = json.load(f)

    models, merr = get(a.url + "/v1/models", a.key)
    report = {"label": a.label, "url": a.url, "models": models, "models_error": merr, "families": {}}

    report["criteria_mode"] = a.criteria_mode
    report["criteria_lang"] = a.criteria_lang
    only = [s for s in a.only.split(",") if s]

    for fam_name, fam in fs["families"].items():
        if only and fam_name not in only:
            continue
        criteria = fam["criteria"]
        instructions = fam["instructions"]
        if a.criteria_lang == "ru":
            criteria = fam.get("criteria_ru", criteria)
            instructions = fam.get("instructions_ru", instructions)
        fam = dict(fam, instructions=instructions)
        if a.criteria_mode == "short":
            criteria = {k: (v[:40] if v else v) for k, v in criteria.items()}
        elif a.criteria_mode == "names":
            criteria = {k: None for k in criteria}
        rows = []
        for case in fam["cases"]:
            payload = {
                "state": case["text"],
                "model": "jev-latest",
                "questions": {
                    "route": {
                        "type": "choice",
                        "instructions": fam["instructions"],
                        "criteria": criteria,
                    }
                },
            }
            resp, wall_ms, err = post(a.url + "/v1/systemone", a.key, payload)
            row = {"id": case["id"], "text": case["text"], "expect": case.get("expect"),
                   "source": case["source"], "wall_ms": round(wall_ms, 1), "error": err}
            if resp:
                ans = resp["answers"]["route"]
                probs = ans.get("probabilities") or {}
                top = sorted(probs.items(), key=lambda kv: -kv[1])[:3]
                row.update({
                    "got": ans.get("choice"),
                    "confidence": ans.get("confidence"),
                    "top3": [[k, round(v, 4)] for k, v in top],
                    "server_ms": (resp.get("decis") or {}).get("latency_ms"),
                    "device": (resp.get("decis") or {}).get("device"),
                    "engine": (resp.get("decis") or {}).get("engine"),
                    "input_tokens": (resp.get("usage") or {}).get("input_tokens"),
                })
                if case.get("expect") is not None:
                    row["correct"] = (row["got"] == case["expect"])
            rows.append(row)
            print("  %-10s %-6s got=%-22s conf=%s" % (
                case["id"], row.get("correct", "-"), str(row.get("got"))[:22],
                round(row["confidence"], 3) if row.get("confidence") is not None else row.get("error")),
                flush=True)

        labelled = [r for r in rows if "correct" in r]
        asserted = [r for r in labelled if r["source"] == "asserted"]
        ok = [r for r in labelled if r["correct"]]
        bad = [r for r in labelled if not r["correct"]]
        calib = [r for r in rows if r["source"] == "calibration"]
        errs = [r for r in rows if r["error"]]

        def mean(xs):
            xs = [x for x in xs if x is not None]
            return round(statistics.fmean(xs), 4) if xs else None

        report["families"][fam_name] = {
            "surface": fam["surface"],
            "n_options": len(criteria),
            "n_cases": len(rows),
            "errors": len(errs),
            "accuracy_asserted": (
                None if not asserted
                else round(sum(1 for r in asserted if r["correct"]) / len(asserted), 4)),
            "n_asserted": len(asserted),
            "accuracy_all_labelled": (
                None if not labelled else round(len(ok) / len(labelled), 4)),
            "n_labelled": len(labelled),
            "mean_confidence_correct": mean([r.get("confidence") for r in ok]),
            "mean_confidence_wrong": mean([r.get("confidence") for r in bad]),
            "mean_server_ms": mean([r.get("server_ms") for r in rows]),
            "mean_wall_ms": mean([r.get("wall_ms") for r in rows]),
            "max_input_tokens": max([r.get("input_tokens") or 0 for r in rows] or [0]),
            "calibration": [{"id": r["id"], "got": r.get("got"),
                             "confidence": r.get("confidence"), "top3": r.get("top3"),
                             "error": r["error"]} for r in calib],
            "misses": [{"id": r["id"], "text": r["text"], "expect": r["expect"],
                        "got": r["got"], "confidence": r.get("confidence"),
                        "top3": r.get("top3")} for r in bad],
            "rows": rows,
        }
        f = report["families"][fam_name]
        print("%s: asserted %s (n=%d), all %s (n=%d), errors %d, server %sms" % (
            fam_name, f["accuracy_asserted"], f["n_asserted"], f["accuracy_all_labelled"],
            f["n_labelled"], f["errors"], f["mean_server_ms"]), flush=True)

    # Batching probe: 1 vs 3 questions in one request. Uses the orchestrator option set, which is
    # the one that fits every engine's per-question token limit.
    fam = fs["families"]["orchestrator-agent"]
    base_q = {"route": {"type": "choice", "instructions": fam["instructions"],
                        "criteria": fam["criteria"]}}
    multi_q = dict(base_q)
    multi_q["has_args"] = {"type": "noul",
                           "instructions": "Does the message already contain every detail needed to act (amount, account, date) without asking the user?"}
    multi_q["shared"] = {"type": "choice",
                         "instructions": "Is this about the user alone or the whole household?",
                         "criteria": {"personal": "about the user alone",
                                      "shared": "about the family or household"}}
    probe = {}
    for name, qs in (("one_question", base_q), ("three_questions", multi_q)):
        resp, wall, err = post(a.url + "/v1/systemone", a.key,
                               {"state": "запиши расход 1500 рублей на продукты",
                                "model": "jev-latest", "questions": qs})
        probe[name] = {"wall_ms": round(wall, 1), "error": err,
                       "server_ms": (resp or {}).get("decis", {}).get("latency_ms"),
                       "answers": (resp or {}).get("answers")}
    report["batching_probe"] = probe
    print("batching: 1q=%sms 3q=%sms (server)" % (
        probe["one_question"]["server_ms"], probe["three_questions"]["server_ms"]), flush=True)

    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print("wrote " + a.out, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
