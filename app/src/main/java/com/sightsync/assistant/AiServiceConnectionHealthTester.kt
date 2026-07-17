package com.sightsync.assistant

import com.sightsync.assistant.ai.AiProxyClient
import com.sightsync.assistant.ai.AiProxyErrorType
import com.sightsync.assistant.ai.AiProxyException
import com.sightsync.assistant.ai.AiProxyHealthClient
import com.sightsync.assistant.ai.AiServiceConnectionConfig
import java.io.IOException
import java.io.InterruptedIOException

class AiServiceConnectionHealthTester(
    private val clientFactory: (AiServiceConnectionConfig) -> AiProxyHealthClient = { config ->
        AiProxyClient(
            baseUrl = config.proxyBaseUrl,
            appToken = config.appToken,
        )
    },
) : AiServiceConnectionTester {
    override suspend fun test(config: AiServiceConnectionConfig): AiServiceConnectionTestResult {
        val normalized = config.normalized()
        if (!normalized.isConfigured) {
            return AiServiceConnectionTestResult.Failed("请填写代理地址和 App token")
        }

        return try {
            clientFactory(normalized).checkHealth()
            AiServiceConnectionTestResult.Success
        } catch (error: AiProxyException) {
            AiServiceConnectionTestResult.Failed(error.toConnectionFailureReason())
        } catch (_: InterruptedIOException) {
            AiServiceConnectionTestResult.Failed("连接测试超时，请检查代理地址和网络")
        } catch (_: IOException) {
            AiServiceConnectionTestResult.Failed("无法连接代理地址，请检查代理地址和网络")
        }
    }

    private fun AiProxyException.toConnectionFailureReason(): String =
        when (type) {
            AiProxyErrorType.ConfigurationMissing -> "请填写代理地址和 App token"
            AiProxyErrorType.Authorization -> "鉴权失败，请检查 App token"
            AiProxyErrorType.ProviderUnavailable -> "代理可达，但云端 AI 配置不可用"
            AiProxyErrorType.RemoteTimeout,
            AiProxyErrorType.ClientTimeout,
            -> "连接测试超时，请检查代理地址和网络"

            AiProxyErrorType.Network -> "无法连接代理地址，请检查代理地址和网络"
            AiProxyErrorType.RateLimited -> "代理请求过于频繁，请稍后重试"
            AiProxyErrorType.EmptyBody,
            AiProxyErrorType.Http,
            -> "代理健康检查响应异常"
        }
}
