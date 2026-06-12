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
    private val contextRejectionKeywords = listOf(
        "支付",
        "付款",
        "转账",
        "支付密码",
        "密码",
        "验证码",
        "银行卡",
        "bank",
        "wallet",
        "pay",
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

    fun shouldRejectActionsInContext(
        actions: List<AssistantAction>,
        screenContext: ScreenContext,
    ): Boolean {
        if (actions.isEmpty()) return false

        val screenText = buildString {
            append(screenContext.packageName)
            append(' ')
            append(screenContext.activityName.orEmpty())
            screenContext.nodes.forEach { node ->
                append(' ')
                append(node.text.orEmpty())
                append(' ')
                append(node.contentDescription.orEmpty())
                append(' ')
                append(node.role)
            }
        }
        val normalized = screenText.lowercase()
        return contextRejectionKeywords.any { keyword ->
            normalized.contains(keyword.lowercase())
        }
    }
}
