package com.sightsync.assistant.ai

import com.sightsync.assistant.accessibility.AssistantClient
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.speech.RecordedAudio
import com.sightsync.assistant.speech.TranscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class AiProxyClient(
    baseUrl: String,
    private val appToken: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) : AssistantClient, TranscriptionClient {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val assistEndpoint = "$normalizedBaseUrl/v1/assist"
    private val transcribeEndpoint = "$normalizedBaseUrl/v1/transcribe"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun assist(
        sessionId: String,
        locale: String,
        utterance: String,
        screenContext: ScreenContext,
    ): AssistResponse = withContext(Dispatchers.IO) {
        val requestPayload = AssistRequest(sessionId, locale, utterance, screenContext)
        val body = json.encodeToString(requestPayload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(assistEndpoint)
            .header("Authorization", "Bearer $appToken")
            .post(body)
            .build()

        executeWithSingleRetry(AiProxyEndpoint.Assist, request) { responseBody ->
            json.decodeFromString<AssistResponse>(responseBody)
        }
    }

    override suspend fun transcribe(audio: RecordedAudio, locale: String): String = withContext(Dispatchers.IO) {
        val requestPayload = TranscribeRequest(
            locale = locale,
            mimeType = audio.mimeType,
            audioBase64 = Base64.getEncoder().encodeToString(audio.bytes),
        )
        val body = json.encodeToString(requestPayload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(transcribeEndpoint)
            .header("Authorization", "Bearer $appToken")
            .post(body)
            .build()

        executeWithSingleRetry(AiProxyEndpoint.Transcribe, request) { responseBody ->
            json.decodeFromString<TranscribeResponse>(responseBody).text
        }
    }

    private fun <T> executeWithSingleRetry(
        endpoint: AiProxyEndpoint,
        request: Request,
        parse: (String) -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw response.toProxyException(endpoint)
                    val responseBody = response.body?.string()
                        ?: throw AiProxyException(
                            endpoint = endpoint,
                            type = AiProxyErrorType.EmptyBody,
                            message = "${endpoint.label}响应为空",
                        )
                    return parse(responseBody)
                }
            } catch (error: AiProxyException) {
                if (attempt < MAX_RETRIES && error.retryable) {
                    attempt += 1
                    continue
                }
                throw error
            } catch (timeout: InterruptedIOException) {
                val error = AiProxyException(
                    endpoint = endpoint,
                    type = AiProxyErrorType.ClientTimeout,
                    retryable = true,
                    message = "${endpoint.label}本地请求超时",
                    cause = timeout,
                )
                if (attempt < MAX_RETRIES) {
                    attempt += 1
                    continue
                }
                throw error
            } catch (network: IOException) {
                val error = AiProxyException(
                    endpoint = endpoint,
                    type = AiProxyErrorType.Network,
                    retryable = true,
                    message = "${endpoint.label}网络连接失败",
                    cause = network,
                )
                if (attempt < MAX_RETRIES) {
                    attempt += 1
                    continue
                }
                throw error
            }
        }
    }

    private fun Response.toProxyException(endpoint: AiProxyEndpoint): AiProxyException {
        val errorType = when (code) {
            401, 403 -> AiProxyErrorType.Authorization
            408, 504 -> AiProxyErrorType.RemoteTimeout
            429 -> AiProxyErrorType.RateLimited
            500, 502, 503 -> AiProxyErrorType.ProviderUnavailable
            in 500..599 -> AiProxyErrorType.ProviderUnavailable
            else -> AiProxyErrorType.Http
        }
        return AiProxyException(
            endpoint = endpoint,
            type = errorType,
            statusCode = code,
            retryable = errorType in RETRYABLE_HTTP_TYPES,
            message = "${endpoint.label}返回 HTTP $code",
        )
    }

    private val AiProxyEndpoint.label: String
        get() = when (this) {
            AiProxyEndpoint.Assist -> "AI 代理"
            AiProxyEndpoint.Transcribe -> "AI 代理转写"
        }

    private companion object {
        const val MAX_RETRIES = 1
        val RETRYABLE_HTTP_TYPES = setOf(
            AiProxyErrorType.RateLimited,
            AiProxyErrorType.ProviderUnavailable,
            AiProxyErrorType.RemoteTimeout,
        )
    }
}
