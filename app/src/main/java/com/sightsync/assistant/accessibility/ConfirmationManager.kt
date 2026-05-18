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
        utterance.contains("取消") ||
            utterance.contains("不用") ||
            utterance.contains("停止")

    private fun isConfirmation(utterance: String): Boolean =
        utterance.contains("确认") ||
            utterance.contains("继续执行")
}
