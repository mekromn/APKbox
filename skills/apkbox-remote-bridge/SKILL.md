---
name: apkbox-remote-bridge
description: >-
  Use when the user asks ChatGPT/Codex to debug an Android device through APKbox Remote Debug
  Bridge, capture logcat/dumpsys, launch an app, run an approved ADB command, or send a phone
  notification/toast/popup. The authoritative operational skill is stored privately in the
  mekromn/Continuity relay repository.
---

# APKbox Remote Debug Bridge

The authoritative skill is private because the live relay is private:

`mekromn/Continuity: skills/apkbox-remote-bridge/SKILL.md`

When this skill applies and the GitHub connector can access `mekromn/Continuity`:

1. Read `skills/apkbox-remote-bridge/SKILL.md` from `mekromn/Continuity`.
2. Follow that file as the authoritative device-discovery, request, approval, polling, result, and security procedure.
3. Never ask the user to paste the relay GitHub token into ChatGPT.
4. Do not fall back to manual LADB instructions merely because the current chat did not previously discuss the bridge.

The private skill is intentionally the single source of truth so bridge protocol changes do not drift across repositories.
