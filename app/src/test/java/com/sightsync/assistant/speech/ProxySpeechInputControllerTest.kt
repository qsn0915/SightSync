package com.sightsync.assistant.speech

import com.sightsync.assistant.ai.AiProxyEndpoint
import com.sightsync.assistant.ai.AiProxyErrorType
import com.sightsync.assistant.ai.AiProxyException
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

        assertEquals(
            SpeechInputResult.Failed(
                message = "我没有听清，请再说一次。",
                kind = SpeechInputFailureKind.NoSpeech,
            ),
            result,
        )
    }

    @Test
    fun noDetectedSpeechDoesNotCallTranscription() = runTest {
        val client = FakeTranscriptionClient("不应调用")
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(error = NoSpeechDetectedException()),
            client,
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "我没有听清，请再说一次。",
                kind = SpeechInputFailureKind.NoSpeech,
            ),
            result,
        )
        assertEquals(null, client.lastAudio)
    }

    @Test
    fun networkFailureUsesClearVoicePrompt() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient(error = IOException("connection refused")),
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "语音转写网络不可用，请检查网络后重试。",
                kind = SpeechInputFailureKind.Network,
            ),
            result,
        )
    }

    @Test
    fun transcriptionServiceUnavailableUsesClearVoicePrompt() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient(error = aiProxyError(AiProxyErrorType.ProviderUnavailable, statusCode = 503)),
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "语音转写服务暂时不可用，请稍后重试。",
                kind = SpeechInputFailureKind.ProviderUnavailable,
                statusCode = 503,
            ),
            result,
        )
    }

    @Test
    fun transcriptionRemoteTimeoutUsesSpecificVoicePrompt() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient(error = aiProxyError(AiProxyErrorType.RemoteTimeout, statusCode = 504)),
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "语音转写服务响应超时，请稍后重试。",
                kind = SpeechInputFailureKind.Timeout,
                statusCode = 504,
            ),
            result,
        )
    }

    @Test
    fun transcriptionAuthorizationFailureUsesSpecificVoicePrompt() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(RecordedAudio(byteArrayOf(1), "audio/mp4")),
            FakeTranscriptionClient(error = aiProxyError(AiProxyErrorType.Authorization, statusCode = 401)),
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "语音转写鉴权失败，请检查代理配置。",
                kind = SpeechInputFailureKind.Authorization,
                statusCode = 401,
            ),
            result,
        )
    }

    @Test
    fun recorderIoFailureIsNotReportedAsTranscriptionNetworkFailure() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(error = IOException("audio recorder buffer is unavailable")),
            FakeTranscriptionClient("不应调用"),
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "录音失败，请重试。",
                kind = SpeechInputFailureKind.Recorder,
            ),
            result,
        )
    }

    @Test
    fun microphonePermissionFailureIsTyped() = runTest {
        val speechInput = ProxySpeechInputController(
            FakeAudioRecorder(error = SecurityException("permission denied")),
            FakeTranscriptionClient("不应调用"),
        )

        val result = speechInput.listenOnce()

        assertEquals(
            SpeechInputResult.Failed(
                message = "麦克风权限未开启，请先在应用首页开启。",
                kind = SpeechInputFailureKind.Permission,
            ),
            result,
        )
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
    private val audio: RecordedAudio? = null,
    private val error: Throwable? = null,
) : AudioRecorder {
    var cancelled = false

    override suspend fun recordOnce(): RecordedAudio {
        error?.let { throw it }
        return requireNotNull(audio)
    }

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

private fun aiProxyError(
    type: AiProxyErrorType,
    statusCode: Int? = null,
): AiProxyException =
    AiProxyException(
        endpoint = AiProxyEndpoint.Transcribe,
        type = type,
        statusCode = statusCode,
        message = "typed test error",
    )
