# APKbox agent instructions

## Mandatory Android testing order

For every APKbox Android code change, use this order:

1. **Android 16 / API 36 emulator first.** Build/test/install/launch the changed APK on an API 36 emulator before treating the change as validated.
2. **Collect debugging evidence from the emulator first.** Prefer emulator logcat, dumpsys, package/activity/process state, screenshots/UI hierarchy, and reproducible test steps before touching the user's physical phone.
3. **Escalate to the APKbox Bridge physical device only when needed.** Use the real device after the emulator baseline when:
   - the emulator cannot reproduce or expose the problem;
   - physical hardware is required (camera sensors/lenses, HDR/display behavior, hardware codecs, radios, biometrics, etc.);
   - Pixel/OEM-specific Android behavior matters;
   - real Shizuku/Sui/Wireless ADB behavior must be validated;
   - thermal, sustained-performance, battery, storage, or other physical-device behavior matters;
   - the user explicitly asks for real-device confirmation.
4. **For hardware-specific features, emulator-first still applies.** Run the API 36 install/launch/smoke and any meaningful non-hardware tests first, then perform the hardware validation on the bridge device.
5. **Compare rather than replace evidence.** When the physical device is needed, retain the emulator result as the baseline and identify what differs on-device.

A compile-only result is not sufficient runtime validation when the change can be exercised on the Android 16 emulator.

## APKbox CI expectation

The repository's Android workflow is expected to boot an API 36 emulator, install the exact signed debug APK, launch `com.mekromn.apkbox/.MainActivity`, verify the process/activity remains alive, and preserve emulator logcat/dumpsys evidence.

## Bridge runtime branch

Continuity runtime relay traffic for branch-aware APKbox builds belongs on `mekromn/Continuity` branch `apkbox-relay`. Continuity `main` is for operator code/docs/skills. Never route current bridge runtime traffic to `main` just because it is the repository default branch.
