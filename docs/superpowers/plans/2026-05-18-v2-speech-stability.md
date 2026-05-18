# V2 Speech Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement SightSync V2 speech stability: explicit always-on continuous listening, no TTS self-capture, adaptive recording instead of fixed 4 seconds, and deterministic cancellation/state behavior.

**Architecture:** Keep the existing SightSync Android app and backend proxy. Add awaitable TTS and adaptive recording inside `speech`, add a small `VoiceTurnCoordinator` for half-duplex voice timing, and keep `AssistantSessionManager` responsible for screen collection, AI planning, risk confirmation, and action execution. Continuous listening remains visible through the existing accessibility foreground service notification and overlay.

**Tech Stack:** Android native Kotlin, AccessibilityService, TextToSpeech, MediaRecorder amplitude polling, Kotlin coroutines, kotlinx-coroutines-test, JUnit.

---

## File Structure

- Modify `app/src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt`: add awaitable TTS contract and keep backward-compatible `speak`.
- Modify `app/src/main/java/com/sightsync/assistant/speech/TtsOutputController.kt`: implement `speakAndAwait` using `TextToSpeech.OnUtteranceProgressListener`.
- Create `app/src/main/java/com/sightsync/assistant/speech/SilenceDetector.kt`: pure Kotlin adaptive recording stop policy.
- Modify `app/src/main/java/com/sightsync/assistant/speech/ShortAudioRecorder.kt`: replace fixed delay with min duration, silence tail, and max duration polling.
- Create `app/src/main/java/com/sightsync/assistant/accessibility/VoiceTurnCoordinator.kt`: half-duplex prompt/listen/speak helper and voice state reporting.
- Modify `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`: route all TTS through awaitable output, remove timing `delay`, add explicit state/cancel semantics.
- Modify `app/src/main/java/com/sightsync/assistant/accessibility/SessionContracts.kt`: add `VoiceInteractionState` shared by the session and coordinator.
- Modify `app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`: preserve visible foreground notification and overlay start/stop entry points for always-on listening.
- Modify `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`: update existing fakes and add V2 timing/cancellation tests.
- Create `app/src/test/java/com/sightsync/assistant/speech/SilenceDetectorTest.kt`: test adaptive stop policy without Android audio APIs.
- Create `app/src/test/java/com/sightsync/assistant/speech/TtsOutputControllerSourceTest.kt`: source-level checks for utterance progress listener and `speakAndAwait` wiring.
- Create `docs/v2-speech-acceptance.md`: manual emulator validation checklist for always-on continuous listening.

## Implementation Branch

- [ ] **Step 1: Create the implementation branch**

Run:

```powershell
git switch -c v2-speech-stability
```

Expected: branch changes from `main` to `v2-speech-stability`.

- [ ] **Step 2: Confirm the starting point is clean**

Run:

```powershell
git status -sb
```

Expected:

```text
## v2-speech-stability
```

---

### Task 1: Awaitable TTS Contract

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/speech/TtsOutputController.kt`
- Create: `app/src/test/java/com/sightsync/assistant/speech/TtsOutputControllerSourceTest.kt`

- [ ] **Step 1: Write failing source tests for awaitable TTS**

Create `app/src/test/java/com/sightsync/assistant/speech/TtsOutputControllerSourceTest.kt`:

```kotlin
package com.sightsync.assistant.speech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsOutputControllerSourceTest {
    @Test
    fun speechOutputExposesAwaitableSpeak() {
        val source = File("src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt").readText()

        assertTrue(source.contains("suspend fun speakAndAwait(text: String)"))
    }

    @Test
    fun ttsControllerWaitsForUtteranceCallbacks() {
        val source = File("src/main/java/com/sightsync/assistant/speech/TtsOutputController.kt").readText()

        assertTrue(source.contains("OnUtteranceProgressListener"))
        assertTrue(source.contains("override suspend fun speakAndAwait"))
        assertTrue(source.contains("onDone"))
        assertTrue(source.contains("onError"))
    }
}
```

- [ ] **Step 2: Run the failing tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.speech.TtsOutputControllerSourceTest"
```

Expected: fail because `speakAndAwait` and `OnUtteranceProgressListener` are not present.

- [ ] **Step 3: Add awaitable output to the contract**

Modify `app/src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt`:

```kotlin
interface SpeechOutput {
    val isSpeaking: Boolean
    fun speak(text: String)
    suspend fun speakAndAwait(text: String) {
        speak(text)
    }
    fun stop()
}
```

This keeps existing tests compiling while allowing real TTS and V2 fakes to wait.

- [ ] **Step 4: Implement awaitable TTS**

Modify `app/src/main/java/com/sightsync/assistant/speech/TtsOutputController.kt` to use this shape:

```kotlin
package com.sightsync.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class TtsOutputController(context: Context) : SpeechOutput {
    private val pendingUtterances = mutableMapOf<String, CancellableContinuation<Unit>>()
    private var textToSpeech: TextToSpeech? = null

    override val isSpeaking: Boolean
        get() = textToSpeech?.isSpeaking == true

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.CHINESE
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        completeUtterance(utteranceId)
                    }

                    @Deprecated("Deprecated in Android framework")
                    override fun onError(utteranceId: String?) {
                        completeUtterance(utteranceId)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        completeUtterance(utteranceId)
                    }
                })
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-${System.nanoTime()}")
    }

    override suspend fun speakAndAwait(text: String) {
        if (text.isBlank()) return
        val activeTts = textToSpeech ?: return
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "assistant-${System.nanoTime()}"
            pendingUtterances[utteranceId] = continuation
            continuation.invokeOnCancellation {
                pendingUtterances.remove(utteranceId)
                activeTts.stop()
            }
            val result = activeTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                completeUtterance(utteranceId)
            }
        }
    }

    override fun stop() {
        textToSpeech?.stop()
        val pending = pendingUtterances.values.toList()
        pendingUtterances.clear()
        pending.forEach { continuation ->
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    fun shutdown() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun completeUtterance(utteranceId: String?) {
        val continuation = utteranceId?.let { pendingUtterances.remove(it) } ?: return
        if (continuation.isActive) continuation.resume(Unit)
    }
}
```

- [ ] **Step 5: Run the targeted tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.speech.TtsOutputControllerSourceTest"
```

Expected: pass.

- [ ] **Step 6: Commit Task 1**

Run:

```powershell
git add app/src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt app/src/main/java/com/sightsync/assistant/speech/TtsOutputController.kt app/src/test/java/com/sightsync/assistant/speech/TtsOutputControllerSourceTest.kt
git commit -m "feat: make speech output awaitable"
```

---

### Task 2: Half-Duplex Voice Timing

**Files:**
- Create: `app/src/main/java/com/sightsync/assistant/accessibility/VoiceTurnCoordinator.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/SessionContracts.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`

- [ ] **Step 1: Write failing tests for TTS-before-recording ordering**

Add this test to `AssistantSessionManagerPhase2Test`:

```kotlin
@Test
fun oneShotPromptFinishesBeforeListeningStarts() = runTest {
    val tts = GateSpeechOutput()
    val speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么"))
    val manager = manager(tts, speech)

    manager.onAssistantRequested()
    advanceUntilIdle()

    assertEquals("请说。", tts.awaitingText)
    assertFalse(speech.listenStarted.isCompleted)

    tts.finishSpeaking()
    advanceUntilIdle()

    assertTrue(speech.listenStarted.isCompleted)
}
```

Add this fake near the existing test fakes:

```kotlin
private class GateSpeechOutput : SpeechOutput {
    val spoken = mutableListOf<String>()
    var awaitingText: String? = null
    private var gate = CompletableDeferred<Unit>()

    override val isSpeaking: Boolean
        get() = awaitingText != null && !gate.isCompleted

    override fun speak(text: String) {
        spoken += text
    }

    override suspend fun speakAndAwait(text: String) {
        spoken += text
        awaitingText = text
        gate.await()
        awaitingText = null
        gate = CompletableDeferred()
    }

    override fun stop() {
        finishSpeaking()
    }

    fun finishSpeaking() {
        if (!gate.isCompleted) gate.complete(Unit)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test.oneShotPromptFinishesBeforeListeningStarts"
```

Expected: fail because `AssistantSessionManager` still calls `speak()` and fixed `delay(700)`.

- [ ] **Step 3: Add voice states and coordinator**

Modify `SessionContracts.kt`:

```kotlin
enum class VoiceInteractionState {
    Idle,
    SpeakingPrompt,
    Listening,
    Thinking,
    SpeakingResult,
    Acting,
}
```

Create `VoiceTurnCoordinator.kt`:

```kotlin
package com.sightsync.assistant.accessibility

import com.sightsync.assistant.speech.SpeechInput
import com.sightsync.assistant.speech.SpeechInputResult
import com.sightsync.assistant.speech.SpeechOutput

class VoiceTurnCoordinator(
    private val speechInput: SpeechInput,
    private val speechOutput: SpeechOutput,
    private val onStateChanged: (VoiceInteractionState) -> Unit = {},
) {
    suspend fun listenForTurn(prompt: String?): SpeechInputResult {
        if (!prompt.isNullOrBlank()) {
            onStateChanged(VoiceInteractionState.SpeakingPrompt)
            speechOutput.speakAndAwait(prompt)
        }
        onStateChanged(VoiceInteractionState.Listening)
        return speechInput.listenOnce()
    }

    suspend fun speakResult(text: String) {
        if (text.isBlank()) return
        onStateChanged(VoiceInteractionState.SpeakingResult)
        speechOutput.speakAndAwait(text)
    }

    fun cancelVoice() {
        speechInput.cancel()
        speechOutput.stop()
    }
}
```

- [ ] **Step 4: Wire coordinator into the session manager**

In `AssistantSessionManager`, add:

```kotlin
private val voiceTurnCoordinator = VoiceTurnCoordinator(
    speechInput = speechInput,
    speechOutput = speechOutput,
    onStateChanged = { state -> voiceState = state },
)
private var voiceState: VoiceInteractionState = VoiceInteractionState.Idle
```

Replace the prompt/listen block in `runAssistantTurn`:

```kotlin
val speechResult = voiceTurnCoordinator.listenForTurn(
    prompt = if (promptBeforeListening) "请说。" else null,
)
```

Remove the `delay(700)` import and calls.

- [ ] **Step 5: Route status and result speech through awaitable output**

Replace direct `speechOutput.speak(...)` calls inside session turn handling with:

```kotlin
voiceTurnCoordinator.speakResult("正在查看当前屏幕。")
```

For error paths and action feedback, use:

```kotlin
voiceTurnCoordinator.speakResult("网络连接失败，请检查网络后重试。")
```

Inside `executeResponse`, speak the response and failure messages with `voiceTurnCoordinator.speakResult(...)`.

- [ ] **Step 6: Run the timing test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test.oneShotPromptFinishesBeforeListeningStarts"
```

Expected: pass.

- [ ] **Step 7: Run all session tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test"
```

Expected: pass. If existing tests assert exact speech order, keep the same spoken strings and only change timing.

- [ ] **Step 8: Commit Task 2**

Run:

```powershell
git add app/src/main/java/com/sightsync/assistant/accessibility/SessionContracts.kt app/src/main/java/com/sightsync/assistant/accessibility/VoiceTurnCoordinator.kt app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt
git commit -m "feat: coordinate half-duplex voice turns"
```

---

### Task 3: Adaptive Recording Stop Policy

**Files:**
- Create: `app/src/main/java/com/sightsync/assistant/speech/SilenceDetector.kt`
- Create: `app/src/test/java/com/sightsync/assistant/speech/SilenceDetectorTest.kt`
- Modify: `app/src/main/java/com/sightsync/assistant/speech/ShortAudioRecorder.kt`

- [ ] **Step 1: Write failing tests for adaptive silence detection**

Create `SilenceDetectorTest.kt`:

```kotlin
package com.sightsync.assistant.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    @Test
    fun doesNotStopBeforeMinimumDuration() {
        val detector = SilenceDetector()

        assertFalse(detector.shouldStop(amplitude = 0, elapsedMillis = 300))
    }

    @Test
    fun stopsAfterSpeechThenTrailingSilence() {
        val detector = SilenceDetector(
            minDurationMillis = 800,
            trailingSilenceMillis = 900,
            maxDurationMillis = 8_000,
            speechAmplitudeThreshold = 1_200,
        )

        assertFalse(detector.shouldStop(amplitude = 2_000, elapsedMillis = 900))
        assertFalse(detector.shouldStop(amplitude = 100, elapsedMillis = 1_200))
        assertTrue(detector.shouldStop(amplitude = 100, elapsedMillis = 2_100))
    }

    @Test
    fun stopsAtMaximumDurationEvenWithoutSpeech() {
        val detector = SilenceDetector(maxDurationMillis = 8_000)

        assertTrue(detector.shouldStop(amplitude = 0, elapsedMillis = 8_000))
    }

    @Test
    fun reportsWhetherSpeechWasHeard() {
        val detector = SilenceDetector(speechAmplitudeThreshold = 1_200)

        detector.shouldStop(amplitude = 1_500, elapsedMillis = 900)

        assertTrue(detector.heardSpeech)
    }
}
```

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.speech.SilenceDetectorTest"
```

Expected: compile failure because `SilenceDetector` does not exist.

- [ ] **Step 3: Implement silence detector**

Create `SilenceDetector.kt`:

```kotlin
package com.sightsync.assistant.speech

class SilenceDetector(
    private val minDurationMillis: Long = 800L,
    private val trailingSilenceMillis: Long = 900L,
    private val maxDurationMillis: Long = 8_000L,
    private val speechAmplitudeThreshold: Int = 1_200,
) {
    var heardSpeech: Boolean = false
        private set

    private var silenceStartedAtMillis: Long? = null

    fun shouldStop(amplitude: Int, elapsedMillis: Long): Boolean {
        if (elapsedMillis >= maxDurationMillis) return true
        if (elapsedMillis < minDurationMillis) return false

        if (amplitude >= speechAmplitudeThreshold) {
            heardSpeech = true
            silenceStartedAtMillis = null
            return false
        }

        if (!heardSpeech) return false

        val silenceStart = silenceStartedAtMillis ?: elapsedMillis.also {
            silenceStartedAtMillis = it
        }
        return elapsedMillis - silenceStart >= trailingSilenceMillis
    }
}
```

- [ ] **Step 4: Run detector tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.speech.SilenceDetectorTest"
```

Expected: pass.

- [ ] **Step 5: Replace fixed recorder delay with adaptive polling**

Modify `ShortAudioRecorder.kt` constructor:

```kotlin
class ShortAudioRecorder(
    private val context: Context,
    private val minDurationMillis: Long = 800L,
    private val trailingSilenceMillis: Long = 900L,
    private val maxDurationMillis: Long = 8_000L,
    private val pollIntervalMillis: Long = 100L,
) : AudioRecorder {
```

Replace `delay(durationMillis)` with:

```kotlin
val detector = SilenceDetector(
    minDurationMillis = minDurationMillis,
    trailingSilenceMillis = trailingSilenceMillis,
    maxDurationMillis = maxDurationMillis,
)
val startedAt = System.currentTimeMillis()
do {
    delay(pollIntervalMillis)
    val elapsed = System.currentTimeMillis() - startedAt
    val amplitude = runCatching { activeRecorder.maxAmplitude }.getOrDefault(0)
} while (!detector.shouldStop(amplitude, elapsed))
```

Keep the existing file read and empty-byte checks. Do not reject audio just because `heardSpeech` is false yet; `ProxySpeechInputController` already maps blank transcription to “我没有听清，请再说一次。”.

- [ ] **Step 6: Run speech input tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.speech.*"
```

Expected: pass.

- [ ] **Step 7: Commit Task 3**

Run:

```powershell
git add app/src/main/java/com/sightsync/assistant/speech/SilenceDetector.kt app/src/test/java/com/sightsync/assistant/speech/SilenceDetectorTest.kt app/src/main/java/com/sightsync/assistant/speech/ShortAudioRecorder.kt
git commit -m "feat: adapt recording duration to speech activity"
```

---

### Task 4: Continuous Listening Recovery and Stop Semantics

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt`

- [ ] **Step 1: Write failing test for result TTS before next listen**

Add this test:

```kotlin
@Test
fun continuousListeningWaitsForResultSpeechBeforeNextListen() = runTest {
    val tts = GateSpeechOutput()
    val speech = FakeSpeechInput(
        SpeechInputResult.Recognized("这里有什么"),
        SpeechInputResult.Recognized("停止聆听"),
    )
    val manager = manager(
        tts = tts,
        speech = speech,
        ai = FakeAssistantClient(response = AssistResponse(spoken = "当前页面有设置。")),
    )

    manager.startContinuousListening()
    advanceUntilIdle()
    tts.finishSpeaking() // 连续聆听已开启。
    advanceUntilIdle()

    assertTrue(speech.listenStarted.isCompleted)
    assertEquals(listOf("这里有什么"), speech.startedUtterances)

    tts.finishSpeaking() // 正在查看当前屏幕。
    advanceUntilIdle()
    tts.finishSpeaking() // 当前页面有设置。
    advanceUntilIdle()

    assertEquals(listOf("这里有什么", "停止聆听"), speech.startedUtterances)
}
```

Extend `FakeSpeechInput` with:

```kotlin
val startedUtterances = mutableListOf<String>()
```

and inside `listenOnce()`:

```kotlin
val result = pendingResults.removeFirstOrNull() ?: awaitCancellation()
if (result is SpeechInputResult.Recognized) startedUtterances += result.text
return result
```

- [ ] **Step 2: Run failing test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test.continuousListeningWaitsForResultSpeechBeforeNextListen"
```

Expected: fail until all result speech is awaited.

- [ ] **Step 3: Make continuous listening await startup and stop prompts**

In `startContinuousListening()`, replace:

```kotlin
speechOutput.speak("连续聆听已开启。")
delay(700)
```

with:

```kotlin
voiceTurnCoordinator.speakResult("连续聆听已开启。")
```

When a stop command ends the loop, replace:

```kotlin
speechOutput.speak("已停止聆听。")
```

with:

```kotlin
voiceTurnCoordinator.speakResult("已停止聆听。")
```

- [ ] **Step 4: Make cancellation use the coordinator**

Replace cancel paths:

```kotlin
speechInput.cancel()
speechOutput.stop()
```

with:

```kotlin
voiceTurnCoordinator.cancelVoice()
```

Keep the spoken cancellation messages, but speak them through `voiceTurnCoordinator.speakResult(...)` from coroutine contexts. If a public non-suspend method needs to speak after cancel, launch a small `scope.launch { voiceTurnCoordinator.speakResult("已取消。") }`.

- [ ] **Step 5: Run continuous listening tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantSessionManagerPhase2Test"
```

Expected: pass.

- [ ] **Step 6: Commit Task 4**

Run:

```powershell
git add app/src/main/java/com/sightsync/assistant/accessibility/AssistantSessionManager.kt app/src/test/java/com/sightsync/assistant/accessibility/AssistantSessionManagerPhase2Test.kt
git commit -m "fix: keep continuous listening state ordered"
```

---

### Task 5: Long-Running Always-On Acceptance Hooks

**Files:**
- Modify: `app/src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt`
- Modify: `app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt`
- Create: `docs/v2-speech-acceptance.md`

- [ ] **Step 1: Add source test for explicit always-on boundary**

Extend `AssistantAccessibilityServiceSourceTest`:

```kotlin
@Test
fun visibleAlwaysOnListeningKeepsStopEntryPoints() {
    val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

    assertTrue(source.contains("setOngoing(true)"))
    assertTrue(source.contains("停止聆听"))
    assertTrue(source.contains("ACTION_STOP_LISTENING"))
    assertTrue(source.contains("startVisibleListening()"))
    assertTrue(source.contains("stopVisibleListening()"))
}
```

- [ ] **Step 2: Run source test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.sightsync.assistant.accessibility.AssistantAccessibilityServiceSourceTest"
```

Expected: pass because the current service already has both visible stop paths. If it fails, update only the notification action or overlay path; do not change provider/model behavior.

- [ ] **Step 3: Write V2 manual acceptance checklist**

Create `docs/v2-speech-acceptance.md`:

````markdown
# V2 语音稳定性验收清单

## 自动化测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
cd backend
npm test
```

## 本机虚拟机验收

1. 安装 debug APK 并启动 SightSync。
2. 开启无障碍、麦克风、通知、悬浮窗权限。
3. 确认通知显示 SightSync 正在连续聆听，悬浮窗可见。
4. 等助手朗读结果时保持安静，确认下一轮不会把助手自己的朗读识别成用户命令。
5. 慢速说“点击设置里的 WLAN”，确认不会在 4 秒处截断。
6. 快速说“返回”，确认不需要等待固定 4 秒。
7. 让连续聆听保持开启一段较长时间，确认仍能响应语音。
8. 通过通知动作停止聆听，确认麦克风停止。
9. 通过悬浮窗再次开启并停止聆听，确认状态一致。
````

- [ ] **Step 4: Commit Task 5**

Run:

```powershell
git add app/src/test/java/com/sightsync/assistant/accessibility/AssistantAccessibilityServiceSourceTest.kt docs/v2-speech-acceptance.md
git commit -m "docs: add V2 speech acceptance checklist"
```

---

### Task 6: Full Verification

**Files:**
- No production edits unless verification exposes defects.

- [ ] **Step 1: Run all Android unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 2: Run backend tests**

Run:

```powershell
cd backend
npm test
cd ..
```

Expected: all backend protocol, Qwen, ASR, and server tests pass.

- [ ] **Step 3: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `app\build\outputs\apk\debug\app-debug.apk` exists.

- [ ] **Step 4: Install on the local emulator**

Run:

```powershell
$env:ANDROID_HOME='D:\AndroidDev\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
adb devices
.\gradlew.bat :app:installDebug
adb shell am start -n com.sightsync.assistant/.MainActivity
```

Expected: app starts on the emulator. Enable permissions manually if Android settings require it.

- [ ] **Step 5: Execute manual V2 speech checklist**

Follow `docs/v2-speech-acceptance.md`.

Expected: no TTS self-capture, short commands finish before 4 seconds, slow commands are not cut off at 4 seconds, visible continuous listening can be stopped from notification and overlay.

- [ ] **Step 6: Record verification results**

Append a dated section to `docs/v2-speech-acceptance.md`:

```markdown
## 验收记录

- 日期：
- 虚拟机镜像/Android 版本：
- App 提交：
- 后端配置：
- 自动化测试结果：
- 人工验收结果：
- 未通过场景：
```

- [ ] **Step 7: Commit verification record**

Run:

```powershell
git add docs/v2-speech-acceptance.md
git commit -m "test: record V2 speech verification"
```

---

## Plan Self-Review

- Spec coverage: Tasks cover awaitable TTS, half-duplex ordering, adaptive recording, explicit always-on visible listening, cancellation semantics, test coverage, and emulator acceptance.
- Scope control: The plan does not expand phone-control actions, change AI provider/model, add hidden hotword detection, add streaming ASR, or create a new Android app.
- Type consistency: `SpeechOutput.speakAndAwait`, `VoiceTurnCoordinator`, `VoiceInteractionState`, and `SilenceDetector` are introduced before later tasks use them.
- Verification: Each implementation task has targeted tests, commit instructions, and a final full verification pass including emulator/manual validation.
