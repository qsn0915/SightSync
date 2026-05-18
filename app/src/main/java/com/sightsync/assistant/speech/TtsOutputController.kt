package com.sightsync.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsOutputController(context: Context) : SpeechOutput {
    private var textToSpeech: TextToSpeech? = null

    override val isSpeaking: Boolean
        get() = textToSpeech?.isSpeaking == true

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.CHINESE
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-${System.nanoTime()}")
    }

    override fun stop() {
        textToSpeech?.stop()
    }

    fun shutdown() {
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
