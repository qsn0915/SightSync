package com.sightsync.assistant.ai

import com.sightsync.assistant.core.ScreenContext
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiProxyClientTest {
    @Test
    fun assistRetriesTransientHttpFailureOnceThenReturnsResponse() = runTest {
        val interceptor = QueueInterceptor(
            QueuedResult.Http(503, """{"error":"provider unavailable"}"""),
            QueuedResult.Http(200, """{"spoken":"当前页面有设置。"}"""),
        )
        val client = client(interceptor)

        val response = client.assist(
            sessionId = "session-1",
            locale = "zh-CN",
            utterance = "这里有什么",
            screenContext = emptyScreenContext(),
        )

        assertEquals("当前页面有设置。", response.spoken)
        assertEquals(2, interceptor.requests.size)
        assertEquals(listOf("/v1/assist", "/v1/assist"), interceptor.requests.map { it.encodedPath })
    }

    @Test
    fun assistStopsAfterOneRetryForTransientHttpFailure() = runTest {
        val interceptor = QueueInterceptor(
            QueuedResult.Http(503, """{"error":"provider unavailable"}"""),
            QueuedResult.Http(503, """{"error":"provider unavailable"}"""),
        )
        val client = client(interceptor)

        val error = runCatching {
            client.assist(
                sessionId = "session-1",
                locale = "zh-CN",
                utterance = "这里有什么",
                screenContext = emptyScreenContext(),
            )
        }.exceptionOrNull()

        assertTrue(error is AiProxyException)
        val proxyError = error as AiProxyException
        assertEquals(AiProxyEndpoint.Assist, proxyError.endpoint)
        assertEquals(AiProxyErrorType.ProviderUnavailable, proxyError.type)
        assertEquals(503, proxyError.statusCode)
        assertEquals(2, interceptor.requests.size)
    }

    @Test
    fun assistAuthorizationFailureIsTypedAndNotRetried() = runTest {
        val interceptor = QueueInterceptor(
            QueuedResult.Http(401, """{"error":"unauthorized"}"""),
        )
        val client = client(interceptor)

        val error = runCatching {
            client.assist(
                sessionId = "session-1",
                locale = "zh-CN",
                utterance = "这里有什么",
                screenContext = emptyScreenContext(),
            )
        }.exceptionOrNull()

        assertTrue(error is AiProxyException)
        val proxyError = error as AiProxyException
        assertEquals(AiProxyEndpoint.Assist, proxyError.endpoint)
        assertEquals(AiProxyErrorType.Authorization, proxyError.type)
        assertEquals(401, proxyError.statusCode)
        assertEquals(1, interceptor.requests.size)
    }

    @Test
    fun assistTransportFailureIsTypedAfterOneRetry() = runTest {
        val interceptor = QueueInterceptor(
            QueuedResult.Failure(IOException("connection reset")),
            QueuedResult.Failure(IOException("connection reset")),
        )
        val client = client(interceptor)

        val error = runCatching {
            client.assist(
                sessionId = "session-1",
                locale = "zh-CN",
                utterance = "这里有什么",
                screenContext = emptyScreenContext(),
            )
        }.exceptionOrNull()

        assertTrue(error is AiProxyException)
        val proxyError = error as AiProxyException
        assertEquals(AiProxyEndpoint.Assist, proxyError.endpoint)
        assertEquals(AiProxyErrorType.Network, proxyError.type)
        assertEquals(2, interceptor.requests.size)
    }

    private fun client(interceptor: QueueInterceptor): AiProxyClient =
        AiProxyClient(
            baseUrl = "http://proxy.test/",
            appToken = "test-token",
            httpClient = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build(),
        )

    private fun emptyScreenContext(): ScreenContext =
        ScreenContext(
            packageName = "com.android.settings",
            activityName = null,
            nodes = emptyList(),
            screenshotBase64 = null,
        )
}

private class QueueInterceptor(
    vararg results: QueuedResult,
) : Interceptor {
    private val pending = ArrayDeque(results.toList())
    val requests = mutableListOf<okhttp3.HttpUrl>()

    override fun intercept(chain: Interceptor.Chain): Response {
        requests += chain.request().url
        return when (val result = pending.removeFirst()) {
            is QueuedResult.Http -> Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(result.code)
                .message("HTTP ${result.code}")
                .body(result.body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()

            is QueuedResult.Failure -> throw result.error
        }
    }
}

private sealed class QueuedResult {
    data class Http(val code: Int, val body: String) : QueuedResult()
    data class Failure(val error: IOException) : QueuedResult()
}
