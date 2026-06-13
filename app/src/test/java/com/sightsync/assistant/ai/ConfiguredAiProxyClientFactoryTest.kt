package com.sightsync.assistant.ai

import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.speech.RecordedAudio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfiguredAiProxyClientFactoryTest {
    @Test
    fun createsClientOnlyWhenDynamicConnectionConfigIsComplete() {
        val client = ConfiguredAiProxyClientFactory.create(
            AiServiceConnectionConfig(
                proxyBaseUrl = " https://proxy.example.com/ ",
                appToken = " app-token ",
            ),
        )

        assertTrue(client is AiProxyClient)
        assertNull(
            ConfiguredAiProxyClientFactory.create(
                AiServiceConnectionConfig(
                    proxyBaseUrl = "",
                    appToken = "app-token",
                ),
            ),
        )
    }

    @Test
    fun missingConnectionClientFailsAssistWithConfigurationMissingError() = runTest {
        val error = runCatching {
            MissingAiServiceConnectionClient.assist(
                sessionId = "session-1",
                locale = "zh-CN",
                utterance = "这里有什么",
                screenContext = ScreenContext(
                    packageName = "com.android.settings",
                    activityName = null,
                    nodes = emptyList(),
                    screenshotBase64 = null,
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is AiProxyException)
        val proxyError = error as AiProxyException
        assertEquals(AiProxyEndpoint.Assist, proxyError.endpoint)
        assertEquals(AiProxyErrorType.ConfigurationMissing, proxyError.type)
    }

    @Test
    fun missingConnectionClientFailsTranscribeWithConfigurationMissingError() = runTest {
        val error = runCatching {
            MissingAiServiceConnectionClient.transcribe(
                RecordedAudio(
                    bytes = byteArrayOf(1, 2, 3),
                    mimeType = "audio/wav",
                ),
                locale = "zh-CN",
            )
        }.exceptionOrNull()

        assertTrue(error is AiProxyException)
        val proxyError = error as AiProxyException
        assertEquals(AiProxyEndpoint.Transcribe, proxyError.endpoint)
        assertEquals(AiProxyErrorType.ConfigurationMissing, proxyError.type)
    }
}
