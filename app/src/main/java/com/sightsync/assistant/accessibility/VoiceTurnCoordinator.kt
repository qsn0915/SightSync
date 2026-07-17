package com.sightsync.assistant.accessibility

import com.sightsync.assistant.speech.SpeechInput
import com.sightsync.assistant.speech.SpeechInputResult
import com.sightsync.assistant.speech.SpeechOutput
import com.sightsync.assistant.speech.SpeechOutputException

class VoiceTurnCoordinator(
    private val speechInput: SpeechInput,
    private val speechOutput: SpeechOutput,
) {
    suspend fun listenForTurn(prompt: String?): SpeechInputResult {
        if (!prompt.isNullOrBlank()) {
            speakBestEffort(prompt)
        }
        return speechInput.listenOnce()
    }

    suspend fun speakResult(text: String) {
        if (text.isBlank()) return
        speakBestEffort(text)
    }

    fun cancelVoice() {
        speechInput.cancel()
        speechOutput.stop()
    }

    private suspend fun speakBestEffort(text: String) {
        try {
            speechOutput.speakAndAwait(text)
        } catch (_: SpeechOutputException) {
            // The service exposes a visual fallback; unavailable TTS must not stop listening.
        }
    }
}
