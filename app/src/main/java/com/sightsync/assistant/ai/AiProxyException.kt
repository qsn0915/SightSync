package com.sightsync.assistant.ai

import java.io.IOException

enum class AiProxyEndpoint {
    Assist,
    Transcribe,
}

enum class AiProxyErrorType {
    Authorization,
    RateLimited,
    ProviderUnavailable,
    RemoteTimeout,
    ClientTimeout,
    Network,
    EmptyBody,
    Http,
}

class AiProxyException(
    val endpoint: AiProxyEndpoint,
    val type: AiProxyErrorType,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
