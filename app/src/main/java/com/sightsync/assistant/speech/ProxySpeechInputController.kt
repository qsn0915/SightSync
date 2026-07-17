package com.sightsync.assistant.speech

import com.sightsync.assistant.ai.AiProxyErrorType
import com.sightsync.assistant.ai.AiProxyException
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException

class ProxySpeechInputController(
    private val audioRecorder: AudioRecorder,
    private val transcriptionClient: TranscriptionClient,
) : SpeechInput {
    override suspend fun listenOnce(): SpeechInputResult {
        val audio = try {
            audioRecorder.recordOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return SpeechInputResult.Failed(
                message = "麦克风权限未开启，请先在应用首页开启。",
                kind = SpeechInputFailureKind.Permission,
            )
        } catch (_: NoSpeechDetectedException) {
            return SpeechInputResult.Failed(
                message = "我没有听清，请再说一次。",
                kind = SpeechInputFailureKind.NoSpeech,
            )
        } catch (_: IOException) {
            return SpeechInputResult.Failed(
                message = "录音失败，请重试。",
                kind = SpeechInputFailureKind.Recorder,
            )
        } catch (_: RuntimeException) {
            return SpeechInputResult.Failed(
                message = "录音失败，请重试。",
                kind = SpeechInputFailureKind.Recorder,
            )
        }

        return try {
            val text = transcriptionClient.transcribe(audio, locale = "zh-CN").trim()
            if (text.isBlank()) {
                SpeechInputResult.Failed(
                    message = "我没有听清，请再说一次。",
                    kind = SpeechInputFailureKind.NoSpeech,
                )
            } else {
                SpeechInputResult.Recognized(text)
            }
        } catch (proxy: AiProxyException) {
            SpeechInputResult.Failed(
                message = proxy.toTranscriptionVoicePrompt(),
                kind = proxy.toSpeechInputFailureKind(),
                statusCode = proxy.statusCode,
            )
        } catch (_: InterruptedIOException) {
            SpeechInputResult.Failed(
                message = "语音转写请求超时，请稍后重试。",
                kind = SpeechInputFailureKind.Timeout,
            )
        } catch (_: IOException) {
            SpeechInputResult.Failed(
                message = "语音转写网络不可用，请检查网络后重试。",
                kind = SpeechInputFailureKind.Network,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            SpeechInputResult.Failed(
                message = "语音转写响应异常，请稍后重试。",
                kind = SpeechInputFailureKind.ResponseInvalid,
            )
        }
    }

    override fun cancel() {
        audioRecorder.cancel()
    }

    private fun AiProxyException.toTranscriptionVoicePrompt(): String =
        when (type) {
            AiProxyErrorType.ConfigurationMissing -> "语音转写连接未配置，请先在应用首页填写代理地址和 App token。"
            AiProxyErrorType.Authorization -> "语音转写鉴权失败，请检查代理配置。"
            AiProxyErrorType.RateLimited -> "语音转写请求过于频繁，请稍后重试。"
            AiProxyErrorType.ProviderUnavailable -> "语音转写服务暂时不可用，请稍后重试。"
            AiProxyErrorType.RemoteTimeout -> "语音转写服务响应超时，请稍后重试。"
            AiProxyErrorType.ClientTimeout -> "语音转写请求超时，请检查网络后重试。"
            AiProxyErrorType.Network -> "语音转写网络不可用，请检查网络后重试。"
            AiProxyErrorType.EmptyBody,
            AiProxyErrorType.Http,
            -> "语音转写响应异常，请稍后重试。"
        }

    private fun AiProxyException.toSpeechInputFailureKind(): SpeechInputFailureKind =
        when (type) {
            AiProxyErrorType.ConfigurationMissing -> SpeechInputFailureKind.Configuration
            AiProxyErrorType.Authorization -> SpeechInputFailureKind.Authorization
            AiProxyErrorType.RateLimited -> SpeechInputFailureKind.RateLimited
            AiProxyErrorType.ProviderUnavailable -> SpeechInputFailureKind.ProviderUnavailable
            AiProxyErrorType.RemoteTimeout,
            AiProxyErrorType.ClientTimeout,
            -> SpeechInputFailureKind.Timeout

            AiProxyErrorType.Network -> SpeechInputFailureKind.Network
            AiProxyErrorType.EmptyBody,
            AiProxyErrorType.Http,
            -> SpeechInputFailureKind.ResponseInvalid
        }
}
