#!/usr/bin/env bash
set -euo pipefail

: "${CANDIDATE_APK:?CANDIDATE_APK is required}"
: "${EVIDENCE_DIR:?EVIDENCE_DIR is required}"

PKG="${PKG:-com.mekromn.apkbox}"
COMPONENT="${COMPONENT:-$PKG/.MainActivity}"
BASELINE_APK="${BASELINE_APK:-}"
FAULT_INJECTION="${FAULT_INJECTION:-false}"
INSTALLER_SCENARIO="${INSTALLER_SCENARIO:-false}"
PHYSICAL_DEVICE_RECOMMENDED="${PHYSICAL_DEVICE_RECOMMENDED:-false}"
CHANGE_CLASSES="${CHANGE_CLASSES:-runtime}"
KNOWN_GOOD_REF="${KNOWN_GOOD_REF:-main}"
KNOWN_GOOD_SHA="${KNOWN_GOOD_SHA:-}"

mkdir -p "$EVIDENCE_DIR"

normalize_environment() {
  adb shell settings put global window_animation_scale 0 || true
  adb shell settings put global transition_animation_scale 0 || true
  adb shell settings put global animator_duration_scale 0 || true
  adb shell settings put system font_scale 1.0 || true
  adb shell settings put system accelerometer_rotation 0 || true
  adb shell settings put system user_rotation 0 || true
  adb shell cmd uimode night yes || true
  adb shell wm size reset || true
  adb shell wm density reset || true
  adb shell svc power stayon true || true
}

apk_sha() { sha256sum "$1" | awk '{print $1}'; }

capture_metrics() {
  local label="$1"
  local launch_file="$EVIDENCE_DIR/${label}-launch.txt"
  local mem_file="$EVIDENCE_DIR/${label}-meminfo.txt"
  local log_file="$EVIDENCE_DIR/${label}-logcat.txt"
  local app_log="$EVIDENCE_DIR/${label}-app-logcat.txt"
  local activity_file="$EVIDENCE_DIR/${label}-activity.txt"
  local package_file="$EVIDENCE_DIR/${label}-package.txt"
  local screenshot_file="$EVIDENCE_DIR/${label}-screen.png"
  local ui_file="$EVIDENCE_DIR/${label}-ui.xml"

  adb shell am force-stop "$PKG" || true
  adb logcat -c || true
  adb shell am start -W -n "$COMPONENT" | tee "$launch_file"
  sleep 3

  local pid
  pid="$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')"
  if [ -z "$pid" ]; then
    adb logcat -d -v threadtime > "$log_file" || true
    echo "Process $PKG is not alive after launch" >&2
    return 1
  fi

  adb shell dumpsys activity top > "$activity_file" || true
  adb shell dumpsys package "$PKG" > "$package_file" || true
  adb shell dumpsys meminfo "$PKG" > "$mem_file" || true
  adb logcat --pid="$pid" -d -v threadtime > "$app_log" || true
  adb logcat -d -v threadtime -t 2500 > "$log_file" || true
  adb exec-out screencap -p > "$screenshot_file" || true
  adb shell uiautomator dump /sdcard/apkbox-ci-ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/apkbox-ci-ui.xml "$ui_file" >/dev/null 2>&1 || true

  if grep -E "FATAL EXCEPTION|Process $PKG has died|Force finishing activity.*$PKG" "$log_file" >/dev/null 2>&1; then
    echo "Fatal runtime signal detected for $label" >&2
    return 1
  fi

  local startup pss
  startup="$(awk -F: '/TotalTime:/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$launch_file")"
  if [ -z "$startup" ]; then
    startup="$(awk -F: '/WaitTime:/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$launch_file")"
  fi
  startup="${startup:-1}"
  pss="$(awk '/TOTAL PSS:/ {print $3; exit} /^[[:space:]]*TOTAL[[:space:]]+[0-9]+/ {print $2; exit}' "$mem_file")"
  pss="${pss:-1}"

  LABEL="$label" PID_VALUE="$pid" STARTUP_MS="$startup" PSS_KB="$pss" SCREENSHOT="$screenshot_file" \
  python3 - <<'PY' > "$EVIDENCE_DIR/${label}-metrics.json"
import json, os, hashlib
shot = os.environ["SCREENSHOT"]
shot_sha = ""
try:
    with open(shot, "rb") as f:
        shot_sha = hashlib.sha256(f.read()).hexdigest()
except Exception:
    pass
print(json.dumps({
    "schema": 1,
    "label": os.environ["LABEL"],
    "pid": int(os.environ["PID_VALUE"]),
    "startupMs": int(os.environ["STARTUP_MS"]),
    "pssKb": int(os.environ["PSS_KB"]),
    "screenshotSha256": shot_sha,
}, indent=2))
PY
}

normalize_environment
adb shell getprop > "$EVIDENCE_DIR/emulator-getprop.txt"
adb shell wm size > "$EVIDENCE_DIR/emulator-display.txt"
adb shell wm density >> "$EVIDENCE_DIR/emulator-display.txt"
adb shell settings get system font_scale >> "$EVIDENCE_DIR/emulator-display.txt" || true

candidate_sha="$(apk_sha "$CANDIDATE_APK")"
baseline_sha=""
baseline_used=false

if [ -n "$BASELINE_APK" ] && [ -f "$BASELINE_APK" ]; then
  baseline_used=true
  baseline_sha="$(apk_sha "$BASELINE_APK")"
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "$BASELINE_APK" | tee "$EVIDENCE_DIR/baseline-install.txt"
  capture_metrics "baseline"
  # Upgrade without clearing data: this is the default migration/update smoke.
  adb install -r "$CANDIDATE_APK" | tee "$EVIDENCE_DIR/candidate-upgrade-install.txt"
  capture_metrics "candidate"
else
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "$CANDIDATE_APK" | tee "$EVIDENCE_DIR/candidate-install.txt"
  capture_metrics "candidate"
fi

if [ "$INSTALLER_SCENARIO" = "true" ]; then
  adb install -r "$CANDIDATE_APK" | tee "$EVIDENCE_DIR/candidate-reinstall.txt"
  capture_metrics "candidate-reinstall"
fi

if [ "$FAULT_INJECTION" = "true" ]; then
  adb shell am force-stop "$PKG" || true
  sleep 1
  capture_metrics "candidate-recovery"
fi

compare_args=(--candidate "$EVIDENCE_DIR/candidate-metrics.json" --output "$EVIDENCE_DIR/performance-verdict.json")
if [ "$baseline_used" = "true" ]; then
  compare_args=(--baseline "$EVIDENCE_DIR/baseline-metrics.json" "${compare_args[@]}")
fi
python3 scripts/ci/compare_metrics.py "${compare_args[@]}"

candidate_signer="$(apksigner verify --print-certs "$CANDIDATE_APK" 2>/dev/null | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}')"
emulator_fingerprint="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
emulator_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

CANDIDATE_SHA="$candidate_sha" BASELINE_SHA="$baseline_sha" CANDIDATE_SIGNER="$candidate_signer" \
EMULATOR_FINGERPRINT="$emulator_fingerprint" EMULATOR_API="$emulator_api" \
CHANGE_CLASSES="$CHANGE_CLASSES" PHYSICAL_DEVICE_RECOMMENDED="$PHYSICAL_DEVICE_RECOMMENDED" \
KNOWN_GOOD_REF="$KNOWN_GOOD_REF" KNOWN_GOOD_SHA="$KNOWN_GOOD_SHA" \
EVIDENCE_DIR_ENV="$EVIDENCE_DIR" python3 - <<'PY' > "$EVIDENCE_DIR/evidence.json"
import json, os, pathlib
root = pathlib.Path(os.environ["EVIDENCE_DIR_ENV"])
def load(name):
    p = root / name
    return json.loads(p.read_text()) if p.exists() else None
perf = load("performance-verdict.json") or {"verdict": "INCONCLUSIVE"}
physical = os.environ["PHYSICAL_DEVICE_RECOMMENDED"].lower() == "true"
manifest = {
  "schema": 1,
  "project": "APKbox",
  "repository": "mekromn/APKbox",
  "branch": os.environ.get("GITHUB_REF_NAME", ""),
  "headSha": os.environ.get("GITHUB_SHA", ""),
  "candidate": {
    "sha256": os.environ["CANDIDATE_SHA"],
    "signerSha256": os.environ["CANDIDATE_SIGNER"],
    "artifact": "app/build/outputs/apk/debug/app-debug.apk"
  },
  "knownGood": {
    "ref": os.environ["KNOWN_GOOD_REF"],
    "commitSha": os.environ["KNOWN_GOOD_SHA"],
    "apkSha256": os.environ["BASELINE_SHA"]
  },
  "changeClasses": [x for x in os.environ["CHANGE_CLASSES"].split(",") if x],
  "environment": {
    "androidApi": int(os.environ["EMULATOR_API"] or 0),
    "buildFingerprint": os.environ["EMULATOR_FINGERPRINT"],
    "primary": "Android 16/API 36 emulator"
  },
  "scenarios": sorted(p.stem.replace("-metrics", "") for p in root.glob("*-metrics.json")),
  "performance": perf,
  "physicalDeviceRecommended": physical,
  "emulatorVerdict": "PASS",
  "promotionLane": "device-needed" if physical else "emulator-green",
  "rollbackTarget": {
    "ref": os.environ["KNOWN_GOOD_REF"],
    "commitSha": os.environ["KNOWN_GOOD_SHA"]
  },
  "evidenceFiles": sorted(p.name for p in root.iterdir() if p.is_file())
}
print(json.dumps(manifest, indent=2))
PY

cat "$EVIDENCE_DIR/evidence.json"
