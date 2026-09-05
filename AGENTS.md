# APKbox agent instructions

Continuity is the primary controller/memory entrypoint. This repository owns APKbox implementation. Before editing, read `mekromn/Continuity/AGENTS.md`, the active Continuity mission ledger, this file, and `.apkbox/project.json`.

## Mandatory Android testing order

For every APKbox Android code change:

1. **Android 16 / API 36 emulator first.** Build/test/install/launch the exact candidate on API 36 before treating runtime code as validated.
2. **Collect emulator evidence first.** Prefer emulator logcat, dumpsys, package/activity/process state, screenshots/UI hierarchy and reproducible steps before touching the user's physical phone.
3. **Escalate to APKbox Bridge physical device only when needed:** emulator reproduction gaps, real Shizuku/Sui/Wireless ADB behavior, Pixel/OEM-specific behavior, camera/HDR/hardware/radios/biometrics, thermal/sustained performance/battery/storage behavior, or explicit user request.
4. Hardware-specific features still get API 36 install/launch/smoke and meaningful non-hardware tests first.
5. Preserve emulator results as the baseline and identify what differs on-device.

Compile-only is not runtime validation when the changed path can run on the emulator.

## Build once, identify exact bytes

A candidate is identified by SHA-256 + signing identity + commit SHA. Build once, then use that exact APK for emulator validation, physical-device validation, APKbox archival and user delivery. A rebuild is a new candidate even if the source ref looks unchanged.

## Change-aware testing

Use `scripts/ci/classify_changes.py` to choose the cheapest adequate evidence:

- tests-only → unit/static checks;
- app/runtime/UI → build + API 36 runtime evidence;
- bridge/jobs/artifacts/storage/model/installer/manifest → high-risk: stable-main differential + old→new upgrade/migration + restart/fault smoke;
- Shizuku/ADB/Wireless/pairing/device-specific paths → emulator baseline plus physical-device recommendation;
- docs/skills/AGENTS/.apkbox metadata → no Android CI.

Do not run expensive emulator work for metadata-only commits.

## Known-good differential / migration

`main` is the stable/LKG line unless `.apkbox/known-good.json` says otherwise. High-risk validation should build stable `main` with the same signing key, install/launch it first, then install the exact candidate **over it without clearing app data** and revalidate. This is the default migration/update smoke and provides same-environment startup/memory comparison.

Rollback targets exact known-good identity. Never cross a signing/data-loss boundary silently.

## Deterministic API 36 environment

CI pins JDK 17, Gradle 8.13, compile/target SDK 36 and the API 36 Google APIs emulator image. Emulator validation normalizes animations, orientation, font scale, UI mode, size/density and power state before testing.

## Evidence contract

Every runtime validation produces a compact evidence manifest keyed by candidate SHA containing:

- repo/ref/head;
- exact APK SHA and signer;
- LKG ref/commit/APK SHA when used;
- change classes;
- Android API/build fingerprint;
- scenarios executed;
- startup/memory comparison;
- screenshot/UI/logcat/dumpsys evidence paths;
- emulator verdict;
- physical-device recommendation;
- promotion lane and rollback target.

Failure evidence is preserved before recovery. Heavy diagnostics are uploaded mainly on failure; successful artifacts are short-lived to conserve Actions storage.

## Fault/recovery testing

High-risk changes get safe emulator fault injection: process force-stop/relaunch and durable lifecycle recovery checks that do not damage the user's physical phone. Extend fault scenarios when the affected subsystem needs stronger proof. Never use the real phone for a destructive fault that the emulator can prove.

## Performance evidence

Capture startup and PSS under the same API 36 environment. When an LKG baseline exists, classify large same-environment changes as `REGRESSION`, `IMPROVEMENT`, or `UNCHANGED`. Do not fail on tiny emulator noise. Use the Pixel only for performance/thermal metrics that require real hardware.

## Promotion lanes

Use:

`experimental → emulator-green → device-needed/device-green → candidate → stable`

A lane transition requires exact candidate identity plus evidence. Do not call a build stable because it compiled.

## Bridge/security invariants

- Continuity branch-aware runtime traffic uses `apkbox-relay`, never `main`.
- Exact artifact substitution requires SHA-256 proof.
- Mutating/dangerous operations never inherit trusted read-only/debug authority.
- Unexpected foreground package blocks UI mutation.
- Signature conflicts never silently destroy app data.
- Durable jobs do not blindly replay after interruption.
- User input/control wins over automation.
- Prefer structured commands over raw shell.

These are regression-test obligations, not documentation suggestions.

## Protocol/schema drift

`scripts/ci/bridge_contract.py` derives a machine-readable snapshot from Kotlin request/command/catalog models and checks schema/relay invariants. Keep the live capability catalog generated from `BridgeCommandType.values()` and do not publish examples that the real parser rejects.

## CI economics

- Android workflow path filters exclude docs, skills, AGENTS and `.apkbox` metadata.
- One integrated change should produce one useful validation run rather than many tiny CI-triggering commits.
- Concurrency cancels obsolete runs.
- Successful APK/evidence artifacts use short retention; heavy diagnostics are failure-only.
- APKbox is public and is the correct place for Android emulator CI; private Continuity is not a build farm.

## Bridge runtime branch

Continuity `main` = source/docs/skills/controller state. Continuity `apkbox-relay` = mutable APKbox runtime state. Never route current branch-aware runtime traffic to `main` because it is the default branch.

## Stable branch protection

`main` is the stable line. Do **not** merge `feature/screen-agent` into `main` unless the user explicitly authorizes promotion.
