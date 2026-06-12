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

    @Test
    fun ttsControllerExposesUnavailableStateAndDoesNotAwaitFailedInitialization() {
        val contract = File("src/main/java/com/sightsync/assistant/speech/SpeechContracts.kt").readText()
        val source = File("src/main/java/com/sightsync/assistant/speech/TtsOutputController.kt").readText()

        assertTrue(contract.contains("val isAvailable: Boolean"))
        assertTrue(source.contains("override val isAvailable"))
        assertTrue(source.contains("onUnavailable: () -> Unit"))
        assertTrue(source.contains("handleInitializationFailure()"))
        assertTrue(source.contains("pendingUtterances.drain()"))
        assertTrue(source.contains("if (!isAvailable) return"))
    }
}
