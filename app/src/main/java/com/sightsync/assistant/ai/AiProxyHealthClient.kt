package com.sightsync.assistant.ai

fun interface AiProxyHealthClient {
    suspend fun checkHealth()
}
