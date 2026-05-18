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
