package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.core.ScreenContext

data class PendingActionRequest(
    val response: AssistResponse,
    val sourceScreen: ScreenContext,
)

class ConfirmationManager(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var pendingRequest: PendingActionRequest? = null
    private var storedAtMillis: Long = 0L

    val hasPending: Boolean
        get() {
            expireIfNeeded()
            return pendingRequest != null
        }

    fun store(response: AssistResponse, sourceScreen: ScreenContext) {
        pendingRequest = PendingActionRequest(response, sourceScreen)
        storedAtMillis = nowMillis()
    }

    fun consumeIfConfirmed(utterance: String): PendingActionRequest? {
        expireIfNeeded()
        val normalized = normalize(utterance)
        if (isCancellationNormalized(normalized) || normalized !in confirmationPhrases) return null
        val request = pendingRequest ?: return null
        pendingRequest = null
        return request
    }

    fun clear() {
        pendingRequest = null
        storedAtMillis = 0L
    }

    fun isCancellation(utterance: String): Boolean =
        isCancellationNormalized(normalize(utterance))

    private fun isCancellationNormalized(utterance: String): Boolean =
        cancellationPhrases.any(utterance::contains)

    private fun normalize(utterance: String): String =
        utterance.filterNot { character ->
            character.isWhitespace() || character in punctuation
        }

    private fun expireIfNeeded() {
        if (pendingRequest == null) return
        if (nowMillis() - storedAtMillis >= CONFIRMATION_TTL_MILLIS) clear()
    }

    private companion object {
        const val CONFIRMATION_TTL_MILLIS = 90_000L

        val confirmationPhrases = setOf("确认执行", "继续执行")

        val cancellationPhrases = listOf(
            "取消",
            "不用",
            "停止",
            "算了",
            "不要了",
            "不做了",
            "别弄了",
            "不了",
        )

        const val punctuation = "，。！？；：、,.!?;:"
    }
}
