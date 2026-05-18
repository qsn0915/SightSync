# Continuous Listening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a visible continuous listening mode so SightSync can accept repeated voice commands without tapping the floating window before each command.

**Architecture:** Keep one recognized utterance as the atomic unit. `AssistantSessionManager` gains a continuous loop that repeatedly calls the existing single-utterance processing path. `AssistantAccessibilityService` starts/stops the loop and owns the foreground notification needed for visible microphone use.

**Tech Stack:** Android native Kotlin, AccessibilityService, foreground service notification, Kotlin coroutines, JUnit, kotlinx-coroutines-test.

---

### Task 1: Session State Machine

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`

- [ ] **Step 1: Write failing tests**

Add tests that call `startContinuousListening()`, verify two utterances are processed without two overlay taps, and verify stop commands end the loop before AI is called.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test"`

Expected: compilation fails because `startContinuousListening`, `stopContinuousListening`, or `isContinuousListening` do not exist.

- [ ] **Step 3: Implement minimal state machine**

Add continuous listening state to `AssistantSessionManager`, extract single-utterance handling into a suspend function, and loop while active. Treat "停止聆听", "停止监听", "暂停助手", "取消", and "退出" as stop commands.

- [ ] **Step 4: Run tests to verify they pass**

Run the same targeted test command and expect the new tests to pass.

### Task 2: Accessibility Service Integration

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/OverlayController.kt`

- [ ] **Step 1: Write failing source test**

Assert that `AssistantAccessibilityService` calls `startContinuousListening`, `stopContinuousListening`, `startForeground`, and creates a notification channel.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantAccessibilityServiceSourceTest"`

Expected: failure because the service still only calls `onAssistantRequested()`.

- [ ] **Step 3: Implement service wiring**

Start foreground visibility in `onServiceConnected()`, start continuous listening after controllers are initialized, and make the overlay click stop or resume continuous mode.

- [ ] **Step 4: Run test to verify it passes**

Run the same targeted source test command and expect it to pass.

### Task 3: Android Manifest and Permission Text

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/AndroidManifestPhase1Test.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/MainActivityPhase1SourceTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/sightsync/assistant/MainActivity.kt`

- [ ] **Step 1: Write failing tests**

Assert that the manifest declares foreground service microphone permissions and the accessibility service has `android:foregroundServiceType="microphone"`. Assert the permission screen and privacy text no longer say voice is only collected after each floating-window click.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.AndroidManifestPhase1Test" --tests "com.sightsync.assistant.MainActivityPhase1SourceTest"`

Expected: failures on missing permissions/service type and old text.

- [ ] **Step 3: Update manifest and copy**

Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, and `POST_NOTIFICATIONS`; set the accessibility service foreground service type to `microphone`; update UI and privacy copy to describe visible continuous listening.

- [ ] **Step 4: Run tests to verify they pass**

Run the same targeted test command and expect it to pass.

### Task 4: Full Verification

**Files:**
- No production edits unless verification exposes defects.

- [ ] **Step 1: Run all unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: all debug unit tests pass.

- [ ] **Step 2: Build debug APK**

Run: `.\gradlew.bat :app:assembleDebug`

Expected: debug build succeeds.

- [ ] **Step 3: Run emulator verification**

Use the local Android emulator to install and launch the debug app, enable permissions, start the accessibility service, and verify continuous voice commands can be issued without tapping before each command.
