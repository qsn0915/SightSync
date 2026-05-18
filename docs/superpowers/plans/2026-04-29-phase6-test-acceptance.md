# Phase 6 Test Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Phase 6 unit, local integration, and manual acceptance coverage for the existing SightSync Android App.

**Architecture:** Keep production behavior scoped to existing SightSync modules. Add a small testable screen-context assembler behind `ScreenContextCollector`, extend session error handling for disabled accessibility permission, and add tests/docs around the existing AI protocol, risk, matcher, and session flow.

**Tech Stack:** Kotlin, Android Gradle Plugin, JUnit 4, kotlinx-coroutines-test, kotlinx-serialization-json, Node.js built-in test runner.

---

### Task 1: Screen Context Collector Unit Coverage

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/core/ScreenContextCollector.kt`
- Create: `app/src/test/java/com/sightsync/assistant/core/ScreenContextCollectorTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests for rich node trees skipping screenshots, sparse node trees attaching screenshots, and null roots preserving activity metadata.

- [ ] **Step 2: Run red test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.core.ScreenContextCollectorTest"`

Expected: compilation fails because `ScreenContextAssembler` does not exist.

- [ ] **Step 3: Implement minimal production support**

Add an internal `ScreenContextAssembler` that accepts package name, activity title, and optional `ScreenNodeSource`, then delegates to `ScreenNodeTreeExtractor` and `ScreenContextPolicy`.

- [ ] **Step 4: Run green test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.core.ScreenContextCollectorTest"`

Expected: all tests in this class pass.

### Task 2: Protocol, Risk, And Matcher Coverage

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/ai/AiProtocolValidatorTest.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/core/RiskClassifierTest.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/core/NodeMatcherTest.kt`

- [ ] **Step 1: Add tests**

Add tests for JSON parsing, illegal parsed actions, missing `OPEN_APP.appPackage`, content-description matching, punctuation-insensitive matching, empty targets, and `OPEN_APP` high-risk utterances.

- [ ] **Step 2: Run targeted tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.ai.AiProtocolValidatorTest" --tests "com.sightsync.assistant.core.RiskClassifierTest" --tests "com.sightsync.assistant.core.NodeMatcherTest"`

Expected: tests pass unless they reveal a real existing bug.

### Task 3: Local Assistant Chain Integration Coverage

**Files:**
- Create: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase6IntegrationTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`

- [ ] **Step 1: Write failing permission test**

Add a test where screen collection throws `SecurityException` and expect the spoken message `无障碍权限已关闭，请重新开启后再试。`.

- [ ] **Step 2: Run red test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase6IntegrationTest"`

Expected: failure because `AssistantSessionManager` currently falls back to generic error text.

- [ ] **Step 3: Implement minimal error handling**

Catch `SecurityException` before the generic `Throwable` catch and speak the explicit accessibility permission message.

- [ ] **Step 4: Add complete chain test**

Add a test for recognized speech, screen collection, mock AI response, and action execution with the original `ScreenContext`.

- [ ] **Step 5: Run green test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase6IntegrationTest"`

Expected: all Phase 6 integration tests pass.

### Task 4: Manual Acceptance Checklist

**Files:**
- Create: `docs/phase6-acceptance.md`
- Modify: `README.md`

- [ ] **Step 1: Add checklist**

Document prerequisites, emulator install/start commands, backend startup, six manual scenarios from `纲领.md`, and a pass/fail record table.

- [ ] **Step 2: Link checklist**

Add the Phase 6 checklist link to `README.md`.

### Task 5: Full Verification

**Files:**
- No source edits.

- [ ] **Step 1: Run Android unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: exit code 0.

- [ ] **Step 2: Run backend tests**

Run: `npm test` from `backend`.

Expected: exit code 0.

- [ ] **Step 3: Run emulator validation**

Run install/start commands against the local Android emulator.

Expected: APK installs and `MainActivity` starts. If the emulator is unavailable, report the exact blocker instead of claiming emulator validation passed.
