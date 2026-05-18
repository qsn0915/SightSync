package com.sightsync.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class SpeechInputController(private val context: Context) : SpeechInput {
    private var recognizer: SpeechRecognizer? = null

    override suspend fun listenOnce(): SpeechInputResult = suspendCancellableCoroutine { continuation ->
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speechRecognizer

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                cleanup()
                if (continuation.isActive) continuation.resume(error.toSpeechInputResult())
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                cleanup()
                val result = if (text.isBlank()) {
                    SpeechInputResult.Failed("我没有听清，请再说一次。")
                } else {
                    SpeechInputResult.Recognized(text)
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的指令")
        }

        continuation.invokeOnCancellation { cancel() }
        speechRecognizer.startListening(intent)
    }

    override fun cancel() {
        recognizer?.cancel()
        cleanup()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun cleanup() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Int.toSpeechInputResult(): SpeechInputResult = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechInputResult.Failed("我没有听清，请再说一次。")

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechInputResult.Failed("语音识别网络不可用，请检查网络后重试。")

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechInputResult.Failed("麦克风权限未开启，请先在应用首页开启。")

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechInputResult.Failed("语音识别暂时忙，请稍后再试。")

        SpeechRecognizer.ERROR_CLIENT -> SpeechInputResult.Cancelled

        else -> SpeechInputResult.Failed("语音识别失败，请重试。")
    }
}
