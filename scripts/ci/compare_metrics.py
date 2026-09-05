#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--baseline")
parser.add_argument("--candidate", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()

candidate = json.loads(Path(args.candidate).read_text())
baseline = json.loads(Path(args.baseline).read_text()) if args.baseline and Path(args.baseline).is_file() else None

result = {
    "schema": 1,
    "baselinePresent": baseline is not None,
    "candidate": candidate,
    "baseline": baseline,
    "startupDeltaMs": None,
    "startupRatio": None,
    "pssDeltaKb": None,
    "pssRatio": None,
    "verdict": "INCONCLUSIVE",
    "reasons": [],
}

if baseline:
    b_start = baseline.get("startupMs", -1)
    c_start = candidate.get("startupMs", -1)
    b_pss = baseline.get("pssKb", -1)
    c_pss = candidate.get("pssKb", -1)
    startup_regression = False
    memory_regression = False
    startup_improvement = False
    memory_improvement = False

    if b_start and b_start > 0 and c_start and c_start > 0:
        result["startupDeltaMs"] = c_start - b_start
        result["startupRatio"] = round(c_start / b_start, 3)
        startup_regression = c_start - b_start >= 300 and c_start >= b_start * 1.5
        startup_improvement = b_start - c_start >= 200 and c_start <= b_start * 0.8
    if b_pss and b_pss > 0 and c_pss and c_pss > 0:
        result["pssDeltaKb"] = c_pss - b_pss
        result["pssRatio"] = round(c_pss / b_pss, 3)
        memory_regression = c_pss - b_pss >= 20 * 1024 and c_pss >= b_pss * 1.35
        memory_improvement = b_pss - c_pss >= 20 * 1024 and c_pss <= b_pss * 0.8

    if startup_regression or memory_regression:
        result["verdict"] = "REGRESSION"
        if startup_regression:
            result["reasons"].append("startup exceeded both +300 ms and 1.5x known-good")
        if memory_regression:
            result["reasons"].append("PSS exceeded both +20 MiB and 1.35x known-good")
    elif startup_improvement or memory_improvement:
        result["verdict"] = "IMPROVEMENT"
        if startup_improvement:
            result["reasons"].append("startup improved by at least 200 ms and 20%")
        if memory_improvement:
            result["reasons"].append("PSS improved by at least 20 MiB and 20%")
    else:
        result["verdict"] = "UNCHANGED"
        result["reasons"].append("no large same-environment startup/memory change crossed conservative thresholds")
else:
    result["reasons"].append("no known-good runtime baseline was requested for this change class")

Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
