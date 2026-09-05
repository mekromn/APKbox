#!/usr/bin/env python3
import json
import os
import subprocess
from pathlib import Path

ZERO = "0" * 40

def changed_files():
    event = os.getenv("GITHUB_EVENT_NAME", "")
    before = os.getenv("BEFORE_SHA", "").strip()
    after = os.getenv("GITHUB_SHA", "HEAD").strip() or "HEAD"
    if event == "workflow_dispatch":
        return ["<workflow_dispatch>"]
    if before and before != ZERO:
        cmd = ["git", "diff", "--name-only", before, after]
    else:
        cmd = ["git", "diff", "--name-only", "HEAD^", "HEAD"]
    out = subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL)
    return [line.strip() for line in out.splitlines() if line.strip()]

files = changed_files()
low = [f.lower() for f in files]
manual = files == ["<workflow_dispatch>"]

cats = set()
runtime = manual
high_risk = False
physical = False
installer = False
storage = False
bridge = False
ui = False
performance = False
build_system = False

for original, f in zip(files, low):
    if original == "<workflow_dispatch>":
        cats.add("manual")
        continue
    if f.startswith("app/src/test/"):
        cats.add("tests")
    if f.startswith("app/src/main/"):
        runtime = True
        cats.add("runtime")
    if f in {"app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties"}:
        runtime = True
        build_system = True
        cats.add("build-system")
    if f.startswith("scripts/ci/") or f == ".github/workflows/android.yml":
        # The validation harness must prove itself through the full gate when it changes.
        runtime = True
        high_risk = True
        build_system = True
        cats.update({"ci", "validation-infrastructure"})
    if "/bridge/" in f or "/jobs/" in f or "/artifacts/" in f:
        bridge = True
        high_risk = True
        cats.add("bridge-lifecycle")
    if "/data/" in f or "/model/" in f or "librarystore" in f or "migration" in f:
        storage = True
        high_risk = True
        cats.add("storage-migration")
    if "installer" in f or "packageinstaller" in f or f.endswith("androidmanifest.xml"):
        installer = True
        high_risk = True
        cats.add("installer-package")
    if any(token in f for token in ("activity.kt", "screen.kt", "/ui/", "compose", "theme")):
        ui = True
        cats.add("ui")
    if any(token in f for token in ("performance", "benchmark", "watchdog", "oracle", "startup")):
        performance = True
        cats.add("performance")
    if any(token in f for token in ("shizuku", "sui", "adb", "wireless", "pairing", "display", "hdr", "camera")):
        physical = True
        cats.add("physical-device-sensitive")

if bridge and any(any(token in f for token in ("shizuku", "adbbridge", "wireless", "pairing")) for f in low):
    physical = True

if not cats:
    cats.add("metadata")

classification = {
    "schema": 1,
    "files": files,
    "categories": sorted(cats),
    "runtime": runtime,
    "highRisk": high_risk,
    "differentialRecommended": high_risk,
    "faultInjectionRecommended": high_risk,
    "physicalDeviceRecommended": physical,
    "installerScenario": installer,
    "storageMigrationScenario": storage,
    "bridgeLifecycleScenario": bridge,
    "uiEvidence": ui,
    "performanceEvidence": performance or runtime,
    "buildSystemChange": build_system,
}

runner_temp = Path(os.getenv("RUNNER_TEMP", "."))
out_path = runner_temp / "apkbox-change-classification.json"
out_path.write_text(json.dumps(classification, indent=2) + "\n")

outputs = {
    "runtime": str(runtime).lower(),
    "high_risk": str(high_risk).lower(),
    "differential": str(high_risk).lower(),
    "fault_injection": str(high_risk).lower(),
    "physical_device": str(physical).lower(),
    "installer": str(installer).lower(),
    "categories": ",".join(sorted(cats)),
    "changed_count": str(len(files)),
}
output_file = os.getenv("GITHUB_OUTPUT")
if output_file:
    with open(output_file, "a", encoding="utf-8") as fh:
        for key, value in outputs.items():
            fh.write(f"{key}={value}\n")

print(json.dumps(classification, indent=2))
