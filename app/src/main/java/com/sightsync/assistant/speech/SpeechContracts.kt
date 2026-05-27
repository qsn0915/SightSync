package com.sightsync.assistant.speech

sealed class SpeechInputResult {
    data class Recognized(val text: String) : SpeechInputResult()
    data class Failed(val message: String) : SpeechInputResult()
    data object Cancelled : SpeechInputResult()
}

interface SpeechInput {
    suspend fun listenOnce(): SpeechInputResult
    fun cancel()
}

interface SpeechOutput {
    val isSpeaking: Boolean
    fun speak(text: String)
    suspend fun speakAndAwait(text: String) {
        speak(text)
    }
    fun stop()
}

data class RecordedAudio(
    val bytes: ByteArray,
    val mimeType: String,
)

class NoSpeechDetectedException : java.io.IOException("no valid speech detected")

interface AudioRecorder {
    suspend fun recordOnce(): RecordedAudio
    fun cancel()
}

interface TranscriptionClient {
    suspend fun transcribe(audio: RecordedAudio, locale: String): String
}
