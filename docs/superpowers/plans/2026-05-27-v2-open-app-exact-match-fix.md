# SightSync V2 Open App Exact Match Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix repeated ambiguity when app names contain each other, such as "QQ" and "QQ邮箱内测版", while preserving local-only app selection.

**Architecture:** Keep the existing local-first `OPEN_APP` flow: Android resolves app names locally before screen collection or AI planning, and the installed app list is not uploaded. Improve deterministic local matching with explicit score ordering and clarification candidate scope; do not let AI directly choose packages in this fix.

**Tech Stack:** Android Kotlin, JUnit, Kotlin coroutines test, existing `OpenAppCommandResolver` and `AssistantSessionManager`.

---

## Context

Verified log evidence:

```text
ASR utterance='打开QQ。'
Local open-app ambiguous.

ASR utterance='QQ邮箱内测版。'
Local open-app ambiguous.
```

Root cause: `OpenAppCommandResolver` currently treats every bidirectional substring match as equal. A target like `QQ邮箱内测版` still matches short app name `QQ`, so the resolver keeps returning `Ambiguous`.

Important product rule:

- If the phone has only `QQ邮箱内测版`, saying `打开QQ邮箱` may open `QQ邮箱内测版`.
- If the phone has both `QQ邮箱` and `QQ邮箱内测版`, saying `打开QQ邮箱` must open the exact formal app `QQ邮箱`.
- Saying `QQ邮箱内测版` should open `QQ邮箱内测版`.
- If only multiple variant apps exist, such as `QQ邮箱内测版` and `QQ邮箱测试版`, saying `打开QQ邮箱` should ask for clarification.

AI decision: do not make AI participate in app package selection in this fix. It is not needed for this root cause, would require sending app candidates or app names to the backend, changes the current privacy boundary, adds latency and network failure modes, and still needs Android-side package validation. AI-assisted alias normalization can be designed later as a separate feature, but Android must remain the final package resolver.

## Task 1: Add Failing Resolver Tests

**Files:**
- Modify: `app/src/test/java/com/sightsync/assistant/apps/OpenAppCommandResolverTest.kt`

- [ ] Add a test proving exact target `QQ` wins over longer `QQ邮箱内测版`.
- [ ] Add a test proving exact formal app `QQ邮箱` wins over `QQ邮箱内测版`.
- [ ] Add a test proving `打开QQ邮箱` resolves to `QQ邮箱内测版` when no exact formal app exists.
- [ ] Add a test proving multiple variants remain ambiguous.
- [ ] Add a test proving `Ambiguous` exposes candidate package names.
- [ ] Run:

```powershell
$env:ANDROID_HOME='D:\AndroidDev\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.apps.OpenAppCommandResolverTest
```

Expected: FAIL because current resolver still treats substring matches equally and `Ambiguous` does not expose candidate packages.

## Task 2: Implement Scored Local App Matching

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/apps/OpenAppCommandResolver.kt`

- [ ] Change `resolve()` signature to accept optional `candidatePackages`:

```kotlin
fun resolve(
    utterance: String,
    allowBareTarget: Boolean = false,
    candidatePackages: Set<String>? = null,
): OpenAppCommandResult
```

- [ ] Filter installed apps by `candidatePackages` when non-null.
- [ ] Preserve browser alias behavior, but only inside the filtered app list.
- [ ] Replace flat substring matching with scored matching:
  - `100`: normalized app name exactly equals target.
  - `90`: normalized app name starts with target.
  - `70`: target starts with normalized app name.
  - `50`: any other contains match.
- [ ] For each app, keep only its highest score across normalized names.
- [ ] Return only the highest-score app group: one winner means `Resolved`, multiple winners means `Ambiguous`, none means `NoMatch`.
- [ ] Add `candidatePackages` to `OpenAppCommandResult.Ambiguous`:

```kotlin
data class Ambiguous(
    val response: AssistResponse,
    val candidatePackages: Set<String>,
) : OpenAppCommandResult
```

- [ ] Run the resolver test command from Task 1.

Expected: PASS.

## Task 3: Carry Clarification Candidate Scope Through Session

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`

- [ ] Replace Boolean clarification state:

```kotlin
private var pendingOpenAppClarification = false
```

with:

```kotlin
private var pendingOpenAppCandidatePackages: Set<String> = emptySet()
```

- [ ] Call resolver with:

```kotlin
allowBareTarget = pendingOpenAppCandidatePackages.isNotEmpty(),
candidatePackages = pendingOpenAppCandidatePackages.takeIf { it.isNotEmpty() },
```

- [ ] On `Ambiguous`, save `result.candidatePackages`.
- [ ] On `Resolved`, `NoMatch`, cancellation, stop command, and confirmed high-risk action, clear the set.
- [ ] Add session tests:
  - `打开邮箱` with `QQ邮箱内测版` and `网易邮箱` asks without AI or actions.
  - Next turn `QQ邮箱内测版` resolves inside the saved candidate set and executes `OPEN_APP`.
  - Cancellation clears the saved set.
- [ ] Run:

```powershell
$env:ANDROID_HOME='D:\AndroidDev\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest --tests com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test
```

Expected: PASS.

## Task 4: Full Verification

- [ ] Run all Android unit tests:

```powershell
$env:ANDROID_HOME='D:\AndroidDev\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest
```

- [ ] Build debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

- [ ] Run emulator verification required by project instructions:
  - Install debug APK into the local Android emulator.
  - Start `com.sightsync.assistant/.MainActivity`.
  - Confirm app opens and `AssistantAccessibilityService` has no crash in logcat.

- [ ] If real-device speech verification is performed, start backend and run:

```powershell
D:\platform-tools\adb.exe reverse tcp:8787 tcp:8787
```

Then say `打开QQ` and `打开QQ邮箱`; confirm logcat shows `Local open-app resolved`.
