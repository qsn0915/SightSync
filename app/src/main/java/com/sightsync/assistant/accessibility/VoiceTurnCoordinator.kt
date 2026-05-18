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
