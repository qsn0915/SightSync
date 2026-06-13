package com.sightsync.assistant

import com.sightsync.assistant.ai.AiProxyEndpoint
import com.sightsync.assistant.ai.AiProxyErrorType
import com.sightsync.assistant.ai.AiProxyException
import com.sightsync.assistant.ai.AiServiceConnectionConfig
import com.sightsync.assistant.ai.AiProxyHealthClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiServiceConnectionHealthTesterTest {
    @Test
    fun testUsesNormalizedConfigAndReportsSuccess() = runTest {
        var testedConfig: AiServiceConnectionConfig? = null
        val tester = AiServiceConnectionHealthTester { config ->
            testedConfig = config
            AiProxyHealthClient { }
        }

        val result = tester.test(
            AiServiceConnectionConfig(
                proxyBaseUrl = " https://proxy.example.com/ ",
                appToken = " app-token ",
            ),
        )

        assertEquals(AiServiceConnectionTestResult.Success, result)
        assertEquals(
            AiServiceConnectionConfig(
                proxyBaseUrl = "https://proxy.example.com",
                appToken = "app-token",
            ),
            testedConfig,
        )
    }

    @Test
    fun authorizationFailureExplainsTokenProblem() = runTest {
        val tester = failingTester(
            AiProxyException(
                endpoint = AiProxyEndpoint.Health,
                type = AiProxyErrorType.Authorization,
                statusCode = 401,
                message = "unauthorized",
            ),
        )

        val result = tester.test(config())

        assertTrue(result is AiServiceConnectionTestResult.Failed)
        assertTrue((result as AiServiceConnectionTestResult.Failed).reason.contains("App token"))
    }

    @Test
    fun providerUnavailableExplainsBackendProviderProblem() = runTest {
        val tester = failingTester(
            AiProxyException(
                endpoint = AiProxyEndpoint.Health,
                type = AiProxyErrorType.ProviderUnavailable,
                statusCode = 503,
                message = "provider unavailable",
            ),
        )

        val result = tester.test(config())

        assertTrue(result is AiServiceConnectionTestResult.Failed)
        assertTrue((result as AiServiceConnectionTestResult.Failed).reason.contains("云端 AI 配置"))
    }

    @Test
    fun networkFailureExplainsProxyReachabilityProblem() = runTest {
        val tester = failingTester(
            AiProxyException(
                endpoint = AiProxyEndpoint.Health,
                type = AiProxyErrorType.Network,
                message = "connection refused",
            ),
        )

        val result = tester.test(config())

        assertTrue(result is AiServiceConnectionTestResult.Failed)
        assertTrue((result as AiServiceConnectionTestResult.Failed).reason.contains("代理地址"))
    }

    private fun failingTester(error: AiProxyException): AiServiceConnectionHealthTester =
        AiServiceConnectionHealthTester {
            AiProxyHealthClient { throw error }
        }

    private fun config(): AiServiceConnectionConfig =
        AiServiceConnectionConfig(
            proxyBaseUrl = "https://proxy.example.com",
            appToken = "app-token",
        )
}
