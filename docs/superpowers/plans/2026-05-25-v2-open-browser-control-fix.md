# SightSync V2 Open Browser Control Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make explicit voice commands such as "帮我打开谷歌浏览器" reliably execute the local `OPEN_APP` action instead of falling back to screen reading.

**Architecture:** Keep the existing SightSync Android app and V2 local Agent execution layer. Tighten the local app command resolver, add one-turn open-app clarification state in `AssistantSessionManager`, and keep all execution inside the existing whitelist, risk confirmation, and `ActionRunner` path.

**Tech Stack:** Android Kotlin, AccessibilityService, PackageManager, Kotlin coroutines, JUnit, Gradle, Node.js proxy tests, Android emulator/ADB.

---

## Tasks

- [x] Add regression tests for Chrome/browser aliases, explicit no-match behavior, one-turn clarification, and open-app launch failures.
- [x] Extend `OpenAppCommandResolver` so Chinese and English Chrome/browser variants resolve locally, while explicit no-match commands return a spoken local failure instead of AI fallback.
- [x] Add `AssistantSessionManager` pending open-app clarification state so an ambiguous command can be followed by a bare target such as "谷歌浏览器".
- [x] Add local debug logging and clearer `ActionExecutor.openApp()` launch failure messages without uploading installed app lists.
- [x] Run Android unit tests, backend tests, debug APK build, then install and verify on emulator/real device where available.

## Verification

- Targeted Android tests must prove "帮我打开谷歌浏览器" and "打开浏览器" resolve locally, "打开不存在的应用" does not collect screen context or call AI, and "这里有什么" still uses the existing screen/AI path.
- Session tests must prove ambiguous app commands ask for clarification, the next bare target is accepted once, and cancellation/stop clears that state.
- Execution tests must prove missing launch intents or `startActivity` failures return spoken failure results rather than crashing.
- Emulator verification must reinstall the latest debug APK, refresh `AssistantAccessibilityService`, inspect SightSync logcat output, and confirm browser/settings launch behavior or record the environment blocker.

## Execution Notes

- Implemented on branch `codex/v2-open-browser-control-fix`.
- Emulator validation was limited by host audio/ADB instability, so final smoke acceptance used an iQOO Neo6 SE real device with `adb reverse tcp:8787 tcp:8787`.
- Real-device acceptance was treated as successful for the local app-control path after installing the latest debug APK with `adb install -r`; no uninstall, app-data clearing, or system accessibility-setting shell edits were used.
- Residual validation notes: the device did not have Google Chrome installed, so Chrome-specific voice acceptance should be tested on a device that has Chrome; USB reconnect clears `adb reverse`, which can make speech transcription temporarily unavailable until the reverse tunnel is restored.
