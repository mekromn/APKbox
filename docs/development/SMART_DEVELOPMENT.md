# Smart Development Workflow

APKbox development is evidence-driven and change-aware. Continuity is the controller entrypoint; this repository owns code and local validation policy.

## Default loop

1. Read Continuity mission state, `AGENTS.md`, `.apkbox/project.json`, and `.apkbox/known-good.json`.
2. Make one focused change.
3. `scripts/ci/classify_changes.py` determines the minimum useful validation class.
4. Unit/schema/security checks run first.
5. Runtime changes build one exact signed APK and record SHA-256 + signer.
6. Android 16/API 36 emulator validates that exact APK.
7. High-risk changes build stable `main`, install/launch it, then update in place to the candidate without clearing data. This is the default migration + differential baseline.
8. Safe process-stop/relaunch fault smoke runs for high-risk changes.
9. Evidence is written to `evidence.json`; successful candidates are promoted to `emulator-green` or `device-needed`.
10. Physical APKbox Bridge validation happens only when the evidence explicitly requires real hardware/OEM/Shizuku/WADB behavior.
11. Update the Continuity mission ledger with candidate SHA, evidence and next safe action.

## Local commands

Unit/build:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Bridge contract snapshot:

```bash
python3 scripts/ci/bridge_contract.py --check --output /tmp/apkbox-bridge-contract.json
```

Change classification:

```bash
GITHUB_EVENT_NAME=workflow_dispatch RUNNER_TEMP=/tmp python3 scripts/ci/classify_changes.py
```

The emulator validation script expects an already-running API 36 emulator:

```bash
CANDIDATE_APK=app/build/outputs/apk/debug/app-debug.apk \
EVIDENCE_DIR=/tmp/apkbox-evidence \
bash scripts/ci/emulator_validate.sh
```

## CI cost model

The Android workflow runs only for app source/tests, build configuration, CI tooling or the workflow itself. README/docs/skills/AGENTS/project metadata do not trigger Android CI.

- tests-only: unit/static work; no emulator image or APK artifact.
- runtime code: one candidate build + API 36 emulator.
- high-risk runtime: same runner additionally builds stable `main` for migration/differential comparison.
- heavy diagnostics: failure-only, 3-day retention.
- successful candidate artifact: exact APK + compact evidence only, 3-day retention.
- concurrency cancels obsolete runs.

APKbox is public, so expensive Android emulator validation lives here rather than in private Continuity. Continuity runtime relay traffic is isolated on `apkbox-relay` and does not run extension CI.

## Verdict interpretation

`performance-verdict.json` uses conservative same-emulator thresholds:

- `REGRESSION`: a large startup or PSS regression crossed both an absolute and relative threshold.
- `IMPROVEMENT`: a large clear improvement crossed both thresholds.
- `UNCHANGED`: no large change crossed the thresholds.
- `INCONCLUSIVE`: no stable-main baseline was required.

Performance verdicts are evidence, not a substitute for functional correctness.

## Promotion

- `experimental`: active development.
- `emulator-green`: required API 36/static/unit evidence passed.
- `device-needed`: emulator passed, but real-device evidence is still required.
- `device-green`: required physical validation passed.
- `candidate`: acceptance criteria satisfied.
- `stable`: explicit promotion/merge to the stable line.

Do not merge `feature/screen-agent` into `main` without explicit user authorization.
