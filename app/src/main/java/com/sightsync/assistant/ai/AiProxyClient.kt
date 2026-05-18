package com.sightsync.assistant.ai

import com.sightsync.assistant.accessibility.AssistantClient
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.speech.RecordedAudio
import com.sightsync.assistant.speech.TranscriptionClient
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiProxyClient(
    baseUrl: String,
    private val appToken: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
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

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 代理返回 ${response.code}")
            }
            val responseBody = response.body?.string() ?: throw IOException("AI 代理响应为空")
            json.decodeFromString<AssistResponse>(responseBody)
        }
    }

    override suspend fun transcribe(audio: RecordedAudio, locale: String): String = withContext(Dispatchers.IO) {
        val requestPayload = TranscribeRequest(
            locale = locale,
            mimeType = audio.mimeType,
            audioBase64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP),
        )
        val body = json.encodeToString(requestPayload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(transcribeEndpoint)
            .header("Authorization", "Bearer $appToken")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 代理转写返回 ${response.code}")
            }
            val responseBody = response.body?.string() ?: throw IOException("AI 代理转写响应为空")
            json.decodeFromString<TranscribeResponse>(responseBody).text
        }
    }
}
