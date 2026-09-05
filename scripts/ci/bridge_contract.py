#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / "app/src/main/java/com/mekromn/apkbox/bridge/BridgeModels.kt"
CATALOG = ROOT / "app/src/main/java/com/mekromn/apkbox/bridge/BridgeCapabilityCatalog.kt"
RELAY = ROOT / "app/src/main/java/com/mekromn/apkbox/bridge/GitHubRelayClient.kt"

models = MODELS.read_text()
catalog = CATALOG.read_text()
relay = RELAY.read_text()

commands = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$", re.search(r"enum class BridgeCommandType\s*\{(.*?)\n\}", models, re.S).group(1), re.M)
request_block = re.search(r"data class BridgeRequest\((.*?)\n\) \{", models, re.S).group(1)
request_fields = [m.group(1) for m in re.finditer(r"^\s*val\s+(\w+)\s*:", request_block, re.M)]
request_schema_match = re.search(r"fun toJson\(\): JSONObject = JSONObject\(\)(.*?)data class BridgeShellResult", models, re.S)
request_schema = int(re.search(r'\.put\("schema",\s*(\d+)\)', request_schema_match.group(1)).group(1))
result_schema_match = re.search(r"data class BridgeResult\((.*?)companion object", models, re.S)
result_schema = int(re.search(r'\.put\("schema",\s*(\d+)\)', result_schema_match.group(1)).group(1))
protocol = int(re.search(r"const val PROTOCOL_VERSION = (\d+)", catalog).group(1))
capability = int(re.search(r"const val CAPABILITY_SCHEMA = (\d+)", catalog).group(1))
skill_revision = re.search(r'const val SKILL_REVISION = "([^"]+)"', catalog).group(1)
relay_branch = re.search(r'const val RELAY_BRANCH = "([^"]+)"', relay).group(1)

snapshot = {
    "schema": 1,
    "protocolVersion": protocol,
    "capabilitySchema": capability,
    "requestSchema": request_schema,
    "resultSchema": result_schema,
    "skillRevision": skill_revision,
    "relayBranch": relay_branch,
    "commandTypes": commands,
    "requestFields": request_fields,
}

errors = []
if relay_branch != "apkbox-relay":
    errors.append(f"relay branch drifted to {relay_branch!r}")
if "BridgeCommandType.values().forEach" not in catalog:
    errors.append("live remoteCommandTypes is no longer generated from BridgeCommandType.values()")
if request_schema != result_schema:
    errors.append(f"request/result schema mismatch: {request_schema} vs {result_schema}")
if protocol < 7 or capability < 7:
    errors.append("protocol/capability schema unexpectedly regressed")
for required in ("id", "type", "jobId", "apkRecordId", "downloadUrl", "expectedApkSha256", "runId", "buildId"):
    if required not in request_fields:
        errors.append(f"BridgeRequest lost required contract field {required}")
for required in ("APK_INSTALL_URL", "JOB_STATUS", "JOB_RESUME", "APK_INSPECT", "APK_PULL", "DEVICE_STATE"):
    if required not in commands:
        errors.append(f"BridgeCommandType lost {required}")

parser = argparse.ArgumentParser()
parser.add_argument("--output")
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
text = json.dumps(snapshot, indent=2) + "\n"
if args.output:
    Path(args.output).write_text(text)
else:
    print(text, end="")
if errors:
    for error in errors:
        print(f"CONTRACT ERROR: {error}")
    raise SystemExit(1)
if args.check:
    print(f"Bridge contract OK: {len(commands)} commands, request/result schema {request_schema}, relay {relay_branch}")
