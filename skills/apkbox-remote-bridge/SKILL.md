---
name: apkbox-remote-bridge
description: >-
  Use when the user asks ChatGPT/Codex to debug, inspect, control, test, or automate an Android
  device through APKbox Remote Debug Bridge. APKbox supports structured diagnostics, Screen Agent,
  bounded autonomous plans, Build Runner, Shizuku/Sui, and Wireless ADB. The authoritative operator
  skill is stored privately in the mekromn/Continuity relay repository; always read live device
  capabilities before acting.
---

# APKbox Remote Debug Bridge

**Forwarding-stub revision:** `2026-09-03.2`

The authoritative operating skill is private because the live relay is private:

`mekromn/Continuity: skills/apkbox-remote-bridge/SKILL.md`

When this skill applies and the GitHub connector can access `mekromn/Continuity`:

1. Discover the live APKbox device under `bridge/devices/*/state.json` first.
2. Read `skillRevision`, `operatorSkill`, `remoteCommandTypes`, `advancedWorkflows`, `privilegedTransport`, `securityContract`, and `knownLimitations` from the live state when available.
3. Treat live `remoteCommandTypes` as executable truth. Never invent a remote verb merely because APKbox has a related local feature.
4. Read the authoritative Continuity skill named by `state.operatorSkill` before issuing requests. If this stub/installed skill revision differs from live `skillRevision`, the Continuity copy wins.
5. Follow the authoritative skill for Shizuku/Sui vs Wireless ADB transport semantics, Screen Agent selectors/actions, autonomous-plan and Build Runner schemas, approval behavior, durable at-most-once execution, interruption recovery, result polling, and security rules.
6. Never ask the user to paste the Continuity relay token or build-source token into ChatGPT.
7. Do not fall back to manual LADB/ADB copy-paste instructions merely because the current chat did not previously discuss APKbox Bridge.

Current advanced bridge verbs are intentionally discoverable live. Plan/build starts retain APKbox's on-device approval boundary; “work freely” means agents should use the available bridge without repeated manual protocol tutoring, not bypass safety approvals.
