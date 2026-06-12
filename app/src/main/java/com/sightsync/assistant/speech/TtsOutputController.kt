package com.sightsync.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class TtsOutputController(
    context: Context,
    private val onUnavailable: () -> Unit = {},
) : SpeechOutput {
    private val pendingUtterances = PendingUtteranceRegistry<CancellableContinuation<Unit>>()
    private var textToSpeech: TextToSpeech? = null
    @Volatile
    private var initializationFailed = false

    override val isAvailable: Boolean
        get() = !initializationFailed

    override val isSpeaking: Boolean
        get() = isAvailable && textToSpeech?.isSpeaking == true

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val activeTts = textToSpeech ?: return@TextToSpeech
                val languageResult = activeTts.setLanguage(Locale.CHINESE)
                if (
                    languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    handleInitializationFailure()
                    return@TextToSpeech
                }
                initializationFailed = false
                activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
            } else {
                handleInitializationFailure()
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        if (!isAvailable) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-${System.nanoTime()}")
    }

    override suspend fun speakAndAwait(text: String) {
        if (text.isBlank()) return
        if (!isAvailable) return
        val activeTts = textToSpeech ?: return
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "assistant-${System.nanoTime()}"
            pendingUtterances.put(utteranceId, continuation)
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
        completePendingUtterances()
    }

    fun shutdown() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun completeUtterance(utteranceId: String?) {
        val continuation = pendingUtterances.remove(utteranceId) ?: return
        if (continuation.isActive) continuation.resume(Unit)
    }

    private fun handleInitializationFailure() {
        initializationFailed = true
        completePendingUtterances()
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        onUnavailable()
    }

    private fun completePendingUtterances() {
        val pending = pendingUtterances.drain()
        pending.forEach { continuation ->
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}
