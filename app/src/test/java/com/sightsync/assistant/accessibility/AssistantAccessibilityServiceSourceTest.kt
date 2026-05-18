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
}
