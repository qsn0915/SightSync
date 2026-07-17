package com.sightsync.assistant.ai

import com.sightsync.assistant.accessibility.AssistantClient
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.speech.RecordedAudio
import com.sightsync.assistant.speech.TranscriptionClient

interface AiServiceClient : AssistantClient, TranscriptionClient

object ConfiguredAiProxyClientFactory {
    fun create(config: AiServiceConnectionConfig): AiServiceClient? {
        val normalized = config.normalized()
        if (!normalized.isConfigured) return null

        return AiProxyClient(
            baseUrl = normalized.proxyBaseUrl,
            appToken = normalized.appToken,
        )
    }
}

object MissingAiServiceConnectionClient : AiServiceClient {
    override suspend fun assist(
        sessionId: String,
        locale: String,
        utterance: String,
        screenContext: ScreenContext,
    ): AssistResponse = throw missingConfigError(AiProxyEndpoint.Assist)

    override suspend fun transcribe(audio: RecordedAudio, locale: String): String =
        throw missingConfigError(AiProxyEndpoint.Transcribe)

    private fun missingConfigError(endpoint: AiProxyEndpoint): AiProxyException =
        AiProxyException(
            endpoint = endpoint,
            type = AiProxyErrorType.ConfigurationMissing,
            message = "${endpoint.label}连接未配置",
        )

    private val AiProxyEndpoint.label: String
        get() = when (this) {
            AiProxyEndpoint.Assist -> "AI 服务"
            AiProxyEndpoint.Transcribe -> "语音转写"
            AiProxyEndpoint.Health -> "AI 代理健康检查"
        }
}
