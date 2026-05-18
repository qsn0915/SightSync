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
