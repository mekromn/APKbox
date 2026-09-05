#!/usr/bin/env python3
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
project_path = root / ".apkbox/project.json"
known_good_path = root / ".apkbox/known-good.json"
project = json.loads(project_path.read_text())
known = json.loads(known_good_path.read_text())

errors = []
if project.get("schema") != 1:
    errors.append("project schema must be 1")
if project.get("repository", {}).get("fullName") != "mekromn/APKbox":
    errors.append("repository.fullName must be mekromn/APKbox")
if project.get("branches", {}).get("stable") != "main":
    errors.append("stable branch must remain main")
if project.get("branches", {}).get("development") != "feature/screen-agent":
    errors.append("development branch must remain feature/screen-agent")
build = project.get("build", {})
for key, expected in {
    "applicationId": "com.mekromn.apkbox",
    "mainActivity": "com.mekromn.apkbox/.MainActivity",
    "compileSdk": 36,
    "targetSdk": 36,
    "minSdk": 26,
    "jdk": 17,
    "gradle": "8.13",
}.items():
    if build.get(key) != expected:
        errors.append(f"build.{key} expected {expected!r}, got {build.get(key)!r}")
validation = project.get("validation", {})
if validation.get("androidEmulatorFirst") is not True or validation.get("androidApi") != 36:
    errors.append("Android validation must remain emulator-first on API 36")
if known.get("stableRef") != "main":
    errors.append("known-good stableRef must remain main")
if known.get("packageName") != "com.mekromn.apkbox":
    errors.append("known-good packageName mismatch")
if project.get("ciEconomics", {}).get("docsSkillsAgentMetadataTriggerAndroidCI") is not False:
    errors.append("project must preserve CI-silent docs/skills/agent metadata")

if errors:
    for error in errors:
        print("PROJECT CONTRACT ERROR:", error)
    raise SystemExit(1)
print("APKbox project contract OK")
