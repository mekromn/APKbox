---
name: apkbox-remote-bridge
description: >-
  Use when the user asks ChatGPT/Codex to debug, inspect, control, test, automate, or communicate
  with an Android device through APKbox Remote Debug Bridge. Supports structured diagnostics,
  Screen Agent, bounded autonomous plans, Build Runner, Shizuku/Sui, Wireless ADB, always-on-top
  security approvals, and agent-selected toast/heads-up/small-popup/overlay/full-window/picture
  messages. The authoritative operator skill lives in private mekromn/Continuity; always read live
  device capabilities before acting.
---

# APKbox Remote Debug Bridge

**Forwarding-stub revision:** `2026-09-04.1`  
**Bridge protocol:** `4`  
**Capability schema:** `3`

Authoritative private skill:

`mekromn/Continuity: skills/apkbox-remote-bridge/SKILL.md`

When this skill applies and the connected GitHub tool can access `mekromn/Continuity`:

1. Discover the live device under `bridge/devices/*/state.json` first.
2. Read `skillRevision`, `operatorSkill`, `remoteCommandTypes`, `messagePresentation`, `advancedWorkflows`, `privilegedTransport`, `securityContract`, and `knownLimitations` when available.
3. Treat live `remoteCommandTypes` as executable truth. Never invent a remote verb because APKbox has a related local feature.
4. Read the authoritative Continuity skill named by `state.operatorSkill` before issuing requests. If this stub/installed-skill revision differs from live `skillRevision`, the Continuity copy wins.
5. Follow that skill for Shizuku/Sui vs Wireless ADB semantics, Screen Agent actions/selectors, autonomous plans, Build Runner, at-most-once recovery, and approval behavior.
6. For agent-to-user communication, prefer the least intrusive useful structured format advertised live: `TOAST`, `MESSAGE_HEADS_UP`, `MESSAGE_SMALL_POPUP`, `MESSAGE_ALWAYS_ON_TOP`, `MESSAGE_FULL_WINDOW`, or `PICTURE_MESSAGE`. Do not overuse overlays/full-window messages.
7. Security approval presentation is user-controlled on the phone. Agents must never choose or weaken notification-vs-overlay approval policy.
8. Picture messages may reference only a valid private Continuity image path allowed by the authoritative skill; never invent a path or substitute an arbitrary tracking/public URL.
9. Never ask the user to paste the Continuity relay token or build-source token into ChatGPT.
10. Do not fall back to manual LADB/ADB copy-paste instructions merely because the current chat did not previously discuss APKbox Bridge.

“Work freely” means agents should discover and use the full bridge without repeated protocol tutoring while preserving APKbox's on-device approval and safety boundaries.
