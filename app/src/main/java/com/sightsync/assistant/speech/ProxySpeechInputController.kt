package com.sightsync.assistant.speech

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException

class ProxySpeechInputController(
    private val audioRecorder: AudioRecorder,
    private val transcriptionClient: TranscriptionClient,
) : SpeechInput {
    override suspend fun listenOnce(): SpeechInputResult {
        return try {
            val audio = audioRecorder.recordOnce()
            val text = transcriptionClient.transcribe(audio, locale = "zh-CN").trim()
            if (text.isBlank()) {
                SpeechInputResult.Failed("我没有听清，请再说一次。")
            } else {
                SpeechInputResult.Recognized(text)
            }
        } catch (_: SecurityException) {
            SpeechInputResult.Failed("麦克风权限未开启，请先在应用首页开启。")
        } catch (_: InterruptedIOException) {
            SpeechInputResult.Failed("语音转写请求超时，请稍后重试。")
        } catch (_: NoSpeechDetectedException) {
            SpeechInputResult.Failed("我没有听清，请再说一次。")
        } catch (error: IOException) {
            if (error.isTranscriptionServiceUnavailable()) {
                SpeechInputResult.Failed("语音转写服务暂时不可用，请稍后重试。")
            } else {
                SpeechInputResult.Failed("语音转写网络不可用，请检查网络后重试。")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            SpeechInputResult.Failed("录音失败，请重试。")
        }
    }

    override fun cancel() {
        audioRecorder.cancel()
    }

    private fun IOException.isTranscriptionServiceUnavailable(): Boolean {
        val message = message.orEmpty()
        return message.contains("AI 代理转写返回 503") ||
            message.contains("AI 代理转写返回 504")
    }
}
