# SightSync V2 Local Agent Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local safe Agent execution layer for voice-driven app launching and tighten V2 speech state, cancellation, and recording stability.

**Architecture:** Keep SightSync inside the existing Android app and backend proxy. Resolve app-launch commands locally before screen collection or AI calls, execute only existing whitelist protocol actions, and keep voice output/input serialized through `VoiceTurnCoordinator`. Replace the recorder implementation with local PCM energy detection while preserving the existing `AudioRecorder` interface.

**Tech Stack:** Android Kotlin, AccessibilityService, PackageManager, AudioRecord, WAV encoding, Kotlin coroutines, JUnit, Node.js proxy tests.

---

## Tasks

- [ ] Add design and implementation docs under `docs/superpowers/`.
- [ ] Add local app catalog models/provider/resolver with test-first coverage for normalization, browser preference, unique match, ambiguity, and no-match fallback.
- [ ] Wire the resolver into `AssistantSessionManager` before screen collection and AI calls; preserve high-risk confirmation and continuous listening stop semantics.
- [ ] Replace `ShortAudioRecorder` internals with `AudioRecord` PCM capture and WAV output; test WAV encoding and frame-energy silence policy.
- [ ] Add manifest launcher package visibility queries and privacy text for local app-name lookup.
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest`, backend `npm test`, `.\gradlew.bat :app:assembleDebug`, then install and validate on a local emulator if available.

## Verification

- Android unit tests must cover local open-app resolution, session integration, stop/cancel semantics, and recorder helpers.
- Backend tests must remain green; the backend protocol should not receive or require installed app lists.
- Emulator acceptance must verify opening browser/settings by voice, stopping continuous listening, high-risk confirmation cancellation, short/long command timing, and TTS non-capture where audio I/O is available.
