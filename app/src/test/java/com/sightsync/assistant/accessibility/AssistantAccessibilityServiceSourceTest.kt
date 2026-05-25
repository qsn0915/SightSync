package com.sightsync.assistant.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantAccessibilityServiceSourceTest {
    @Test
    fun usesProxySpeechInputInsteadOfAndroidSpeechRecognizer() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("ProxySpeechInputController"))
        assertFalse(source.contains("import com.sightsync.assistant.speech.SpeechInputController"))
    }

    @Test
    fun startsVisibleContinuousListeningWithForegroundNotification() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("startContinuousListening()"))
        assertTrue(source.contains("stopContinuousListening()"))
        assertTrue(source.contains("NotificationChannel"))
        assertTrue(source.contains("startForeground("))
        assertTrue(source.contains("FOREGROUND_SERVICE_TYPE_MICROPHONE"))
    }

    @Test
    fun visibleAlwaysOnListeningKeepsStopEntryPoints() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("setOngoing(true)"))
        assertTrue(source.contains("停止聆听"))
        assertTrue(source.contains("ACTION_STOP_LISTENING"))
        assertTrue(source.contains("startVisibleListening()"))
        assertTrue(source.contains("stopVisibleListening()"))
    }

    @Test
    fun wiresLocalOpenAppResolverIntoSessionManager() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("PackageManagerAppCatalogProvider"))
        assertTrue(source.contains("OpenAppCommandResolver"))
        assertTrue(source.contains("openAppCommandResolver ="))
    }

    @Test
    fun v2SpeechAcceptanceChecklistDocumentsLongRunningManualChecks() {
        val source = File("../docs/v2-speech-acceptance.md")

        assertTrue(source.exists())

        val text = source.readText()
        assertTrue(text.contains("V2 语音稳定性验收清单"))
        assertTrue(text.contains("通知动作停止聆听"))
        assertTrue(text.contains("悬浮窗再次开启并停止聆听"))
    }
}
