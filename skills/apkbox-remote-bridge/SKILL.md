---
name: apkbox-remote-bridge
description: >-
  Use when ChatGPT/Codex needs to debug, inspect, control, test, automate, communicate with,
  retrieve APKs from, or deploy builds to Android through APKbox Remote Debug Bridge. Runtime
  relay traffic uses the dedicated private Continuity branch `apkbox-relay`; operator code/docs
  and the authoritative skill stay on `main`.
---

# APKbox Remote Debug Bridge

**Forwarding-stub revision:** `2026-09-05.1`  
**Bridge protocol:** `7`  
**Capability schema:** `6`  
**Request/result schema:** `7`

Authoritative private skill on Continuity `main`:

`mekromn/Continuity: skills/apkbox-remote-bridge/SKILL.md`

## Hard branch rule

- Current runtime branch: **`apkbox-relay`**.
- Operator code/docs/skills branch: **`main`**.
- All branch-aware `bridge/devices/**` reads/writes/deletes must use `apkbox-relay`; never rely on the repository default branch.
- During migration only, if no fresh branch-aware state exists, a fresh legacy state on `main` may be used temporarily. Once a device advertises `relayBranch: "apkbox-relay"`, ignore legacy runtime files on `main` for that device permanently.
- Never split one request across branches.

When this skill applies:

1. Discover fresh runtime `state.json` using the branch migration rule; never hard-code a device ID.
2. Treat live `remoteCommandTypes` as executable truth. Read `relay`, `artifactResolution`, `durableJobs`, `inventory`, `apkRetrieval`, `privilegedTransport`, `advancedWorkflows`, `securityContract`, `limits`, and `knownLimitations`.
3. Read the authoritative Continuity skill from `main` before acting. If revisions differ, live state + the Continuity skill win.
4. **Fastest trustworthy exact source wins.** Never substitute APK/build bytes by package/title/version/signer alone; exact SHA-256 proof is required.
5. Use structured inventory instead of asking the user for IDs APKbox can report: `PROJECT_LIST/GET`, `APK_LIST/SEARCH`, `PACKAGE_STATE`, `INSTALLED_APPS`, `DEVICE_STATE`.
6. Prefer `APK_INSPECT`; use `APK_PULL` only when exact binary bytes are materially needed.
7. Use `JOB_LIST/STATUS/CANCEL/RESUME` for long operations. Never repeat a start to implicitly resume an old job. Cancelled job IDs are terminal.
8. `JOB_RESUME` uses the original persisted payload and always needs fresh approval. `JOB_CANCEL` stops only at a safe cancellable boundary.
9. Prefer structured `APK_INSTALL_URL` over ad-hoc shell when a direct HTTPS APK URL exists. Include expected SHA/package when known.
10. Do not force Wireless ADB when Shizuku/Sui is healthy.
11. Use the least intrusive useful agent message format. Security approval presentation remains user-controlled.
12. Never expose Continuity relay/build-source tokens.
13. Never claim a full APK was pulled/reassembled when the current controller cannot retrieve the exact published chunks.
14. Prefer APKbox Bridge over manual ADB/LADB copy-paste whenever live structured capabilities cover the task.

“Work freely” means agents discover and use the live platform without repeated protocol tutoring while preserving exact-byte integrity, local approvals, at-most-once execution, branch isolation and user control.
