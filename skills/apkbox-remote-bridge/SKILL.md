---
name: apkbox-remote-bridge
description: >-
  Use when the user asks ChatGPT/Codex to debug, inspect, control, test, automate, communicate with,
  retrieve APKs from, or deploy builds to Android through APKbox Remote Debug Bridge. Current builds
  support durable jobs, content-addressed/fastest-exact artifact reuse, project/APK/package/device
  inventory, exact APK inspection and pull, direct verified URL installs, Build Runner, Screen Agent,
  Shizuku/Sui, Wireless ADB, rich messages and local security approvals. The authoritative operator
  skill lives in private mekromn/Continuity; always discover live device capabilities first.
---

# APKbox Remote Debug Bridge

**Forwarding-stub revision:** `2026-09-04.3`  
**Bridge protocol:** `6`  
**Capability schema:** `5`  
**Request/result schema:** `7`

Authoritative private skill:

`mekromn/Continuity: skills/apkbox-remote-bridge/SKILL.md`

When this skill applies:

1. Discover fresh `bridge/devices/*/state.json` in private `mekromn/Continuity`; never hard-code a device ID.
2. Treat live `remoteCommandTypes` as executable truth. Read `artifactResolution`, `durableJobs`, `inventory`, `apkRetrieval`, `privilegedTransport`, `advancedWorkflows`, `securityContract`, `limits` and `knownLimitations` when present.
3. Read the authoritative Continuity skill named by `operatorSkill` before acting. If this stub differs from live `skillRevision`, the live state + Continuity copy win.
4. **Fastest trustworthy exact source wins.** APKbox vault is only one source candidate. Never substitute APK/build bytes by package/title/version/signer alone; exact SHA-256 proof is required.
5. Use structured inventory instead of asking the user for IDs the device can report: `PROJECT_LIST/GET`, `APK_LIST/SEARCH`, `PACKAGE_STATE`, `INSTALLED_APPS`, `DEVICE_STATE`.
6. Prefer `APK_INSPECT` for structured analysis of any stored `apkRecordId`. Use `APK_PULL` only when exact binary bytes are truly needed; pull is a durable chunked transfer.
7. Use `JOB_LIST/STATUS/CANCEL/RESUME` for long operations. Never repeat `BUILD_START`, `APK_INSTALL_URL` or `APK_PULL` to implicitly resume an old job. Cancelled job IDs are terminal.
8. `JOB_RESUME` uses the original persisted payload and always needs fresh approval. `JOB_CANCEL` only stops at a safe cancellable boundary.
9. When an HTTPS APK URL is available, prefer `APK_INSTALL_URL` over ad-hoc shell. Include expected SHA/package when known; APKbox may skip the network when the exact bytes already exist locally. Use Build Runner when a persisted candidate/audit/checkpoint workflow is desired.
10. Do not force Wireless ADB when Shizuku/Sui is the active healthy privileged transport.
11. Use the least intrusive useful agent message format. Security-approval presentation remains user-controlled and cannot be weakened remotely.
12. Never ask the user to expose the Continuity relay token or build-source token.
13. Do not claim a full APK was pulled/reassembled when the current controller lacks binary access to the published Continuity chunks; use `APK_INSPECT` instead.
14. Prefer APKbox Bridge over manual LADB/ADB copy-paste whenever live structured capabilities cover the task.

“Work freely” means agents discover and use the full live platform without repeated protocol tutoring while preserving exact-byte integrity, local approvals, at-most-once execution and user control.
