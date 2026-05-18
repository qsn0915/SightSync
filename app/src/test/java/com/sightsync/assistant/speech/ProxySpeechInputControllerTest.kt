package com.sightsync.assistant.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProxySpeechInputControllerTest {
    @Test
    fun returnsRecognizedTextFromBackendTranscription() = runTest {
        val recorder = FakeAudioRecorder(RecordedAudio(byteArrayOf(1, 2, 3), "audio/mp4"))
        val client = FakeTranscriptionClient("点击确定")
        val speechInput = ProxySpeechInputController(recorder, client)

        val result = speechInput.listenOnce()

        assertEquals(SpeechInputResult.Recognized("点击确定"), result)
        assertEquals("zh-CN", client.lastLocale)
        assertEquals("audio/mp4", client.lastAudio?.mimeType)
    }

    @Test
    fun blankTranscriptionAsksUserToTryAgain() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient("   "),
        )

        val result = speechInput.listenOnce()

        assertEquals(SpeechInputResult.Failed("我没有听清，请再说一次。"), result)
    }

    @Test
    fun networkFailureUsesClearVoicePrompt() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient(error = IOException("connection refused")),
        )

        val result = speechInput.listenOnce()

        assertEquals(SpeechInputResult.Failed("语音转写网络不可用，请检查网络后重试。"), result)
    }

    @Test
    fun transcriptionServiceUnavailableUsesClearVoicePrompt() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient(error = IOException("AI 代理转写返回 504")),
        )

        val result = speechInput.listenOnce()

        assertEquals(SpeechInputResult.Failed("语音转写服务暂时不可用，请稍后重试。"), result)
    }

    @Test
    fun cancelStopsActiveRecording() {
        val recorder = FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4"))
        val speechInput = ProxySpeechInputController(recorder, FakeTranscriptionClient("返回"))

        speechInput.cancel()

        assertTrue(recorder.cancelled)
    }
}

private class FakeAudioRecorder(
    private val audio: RecordedAudio,
) : AudioRecorder {
    var cancelled = false

    override suspend fun recordOnce(): RecordedAudio = audio

    override fun cancel() {
        cancelled = true
    }
}

private class FakeTranscriptionClient(
    private val text: String? = null,
    private val error: Throwable? = null,
) : TranscriptionClient {
    var lastAudio: RecordedAudio? = null
    var lastLocale: String? = null

    override suspend fun transcribe(audio: RecordedAudio, locale: String): String {
        lastAudio = audio
        lastLocale = locale
        error?.let { throw it }
        return text.orEmpty()
    }
}
