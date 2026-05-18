package com.sightsync.assistant.core

import com.sightsync.assistant.ai.AssistantAction

object RiskClassifier {
    private val highRiskKeywords = listOf(
        "支付",
        "付款",
        "转账",
        "购买",
        "删除",
        "清空",
        "发送",
        "提交",
        "确认订单",
        "注销",
        "退出登录",
    )

    fun requiresConfirmation(
        utterance: String,
        actions: List<AssistantAction>,
        screenContext: ScreenContext? = null,
    ): Boolean {
        val hasCommitAction = actions.any { action ->
            action.type == "CLICK_NODE" || action.type == "OPEN_APP"
        }
        if (!hasCommitAction) return false

        val actionText = actions.joinToString(" ") { action ->
            val node = action.nodeId?.let { nodeId ->
                screenContext?.nodes?.let { NodeMatcher.findById(it, nodeId) }
            }
            listOfNotNull(
                action.text,
                action.nodeId,
                action.appPackage,
                node?.text,
                node?.contentDescription,
            ).joinToString(" ")
        }
        val content = "$utterance $actionText"
        return highRiskKeywords.any(content::contains)
    }
}
