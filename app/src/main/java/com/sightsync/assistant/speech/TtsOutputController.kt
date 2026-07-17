package com.sightsync.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class TtsOutputController(
    context: Context,
    private val onUnavailable: () -> Unit = {},
) : SpeechOutput {
    private val pendingUtterances = PendingUtteranceRegistry<CancellableContinuation<Unit>>()
    private val initialization = CompletableDeferred<Boolean>()
    private var textToSpeech: TextToSpeech? = null
    @Volatile
    private var initializationReady = false

    override val isAvailable: Boolean
        get() = initializationReady

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
                initializationReady = true
                activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        completeUtterance(utteranceId)
                    }

                    @Deprecated("Deprecated in Android framework")
                    override fun onError(utteranceId: String?) {
                        failUtterance(utteranceId, "语音播报失败。")
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        cancelUtterance(utteranceId)
                    }
                })
                initialization.complete(true)
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
        if (!initialization.await() || !isAvailable) {
            throw SpeechOutputException("语音输出初始化失败。")
        }
        val activeTts = textToSpeech ?: throw SpeechOutputException("语音输出不可用。")
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "assistant-${System.nanoTime()}"
            pendingUtterances.put(utteranceId, continuation)
            continuation.invokeOnCancellation {
                pendingUtterances.remove(utteranceId)
                activeTts.stop()
            }
            val result = activeTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                failUtterance(utteranceId, "语音播报启动失败。")
            }
        }
    }

    override fun stop() {
        textToSpeech?.stop()
        cancelPendingUtterances()
    }

    fun shutdown() {
        stop()
        initialization.complete(false)
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun completeUtterance(utteranceId: String?) {
        val continuation = pendingUtterances.remove(utteranceId) ?: return
        if (continuation.isActive) continuation.resume(Unit)
    }

    private fun failUtterance(utteranceId: String?, message: String) {
        val continuation = pendingUtterances.remove(utteranceId) ?: return
        if (continuation.isActive) continuation.resumeWithException(SpeechOutputException(message))
    }

    private fun cancelUtterance(utteranceId: String?) {
        val continuation = pendingUtterances.remove(utteranceId) ?: return
        continuation.cancel(SpeechOutputException("语音播报已中断。"))
    }

    private fun handleInitializationFailure() {
        initializationReady = false
        initialization.complete(false)
        failPendingUtterances(SpeechOutputException("语音输出初始化失败。"))
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        onUnavailable()
    }

    private fun failPendingUtterances(error: SpeechOutputException) {
        val pending = pendingUtterances.drain()
        pending.forEach { continuation ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun cancelPendingUtterances() {
        val pending = pendingUtterances.drain()
        pending.forEach { continuation ->
            continuation.cancel(SpeechOutputException("语音播报已中断。"))
        }
    }
}
