package com.sightsync.assistant.ai

import com.sightsync.assistant.core.ScreenContext
import kotlinx.serialization.Serializable

@Serializable
data class AssistRequest(
    val sessionId: String,
    val locale: String,
    val utterance: String,
    val screen: ScreenContext,
)

@Serializable
data class AssistResponse(
    val spoken: String,
    val requiresConfirmation: Boolean = false,
    val actions: List<AssistantAction> = emptyList(),
)

@Serializable
data class TranscribeRequest(
    val locale: String,
    val mimeType: String,
    val audioBase64: String,
)

@Serializable
data class TranscribeResponse(
    val text: String,
)

@Serializable
data class AssistantAction(
    val type: String,
    val nodeId: String? = null,
    val text: String? = null,
    val appPackage: String? = null,
)

data class ProtocolValidationResult(
    val isValid: Boolean,
    val reason: String? = null,
)

object AiProtocolValidator {
    private val allowedActions = setOf(
        "SPEAK",
        "CLICK_NODE",
        "SET_TEXT",
        "SCROLL_FORWARD",
        "SCROLL_BACKWARD",
        "GLOBAL_BACK",
        "GLOBAL_HOME",
        "OPEN_APP",
    )

    fun validate(response: AssistResponse): ProtocolValidationResult {
        if (response.spoken.isBlank()) {
            return ProtocolValidationResult(false, "spoken 不能为空")
        }

        response.actions.forEach { action ->
            if (action.type !in allowedActions) {
                return ProtocolValidationResult(false, "不支持的动作类型：${action.type}")
            }

            when (action.type) {
                "CLICK_NODE" -> if (action.nodeId.isNullOrBlank()) {
                    return ProtocolValidationResult(false, "CLICK_NODE 缺少 nodeId")
                }

                "SET_TEXT" -> {
                    if (action.nodeId.isNullOrBlank()) {
                        return ProtocolValidationResult(false, "SET_TEXT 缺少 nodeId")
                    }
                    if (action.text == null) {
                        return ProtocolValidationResult(false, "SET_TEXT 缺少 text")
                    }
                }

                "OPEN_APP" -> if (action.appPackage.isNullOrBlank()) {
                    return ProtocolValidationResult(false, "OPEN_APP 缺少 appPackage")
                }
            }
        }

        return ProtocolValidationResult(true)
    }
}
