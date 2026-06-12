package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.core.ScreenContext

data class PendingActionRequest(
    val response: AssistResponse,
    val sourceScreen: ScreenContext,
)

class ConfirmationManager {
    private var pendingRequest: PendingActionRequest? = null

    val hasPending: Boolean
        get() = pendingRequest != null

    fun store(response: AssistResponse, sourceScreen: ScreenContext) {
        pendingRequest = PendingActionRequest(response, sourceScreen)
    }

    fun consumeIfConfirmed(utterance: String): PendingActionRequest? {
        if (!isConfirmation(utterance)) return null
        val request = pendingRequest ?: return null
        pendingRequest = null
        return request
    }

    fun clear() {
        pendingRequest = null
    }

    fun isCancellation(utterance: String): Boolean =
        cancellationPhrases.any(utterance::contains)

    private fun isConfirmation(utterance: String): Boolean =
        confirmationPhrases.any(utterance::contains)

    private companion object {
        val confirmationPhrases = listOf(
            "确认",
            "继续执行",
            "好的",
            "行",
            "可以",
            "没问题",
            "对",
            "嗯",
            "执行吧",
            "弄吧",
        )

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
    }
}
