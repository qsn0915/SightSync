package com.sightsync.assistant.core

import com.sightsync.assistant.ai.AssistantAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskClassifierTest {
    @Test
    fun clickOnDeleteRequiresConfirmation() {
        val action = AssistantAction(type = "CLICK_NODE", nodeId = "delete")

        assertTrue(RiskClassifier.requiresConfirmation("点击删除", listOf(action)))
    }

    @Test
    fun sendingTextRequiresConfirmation() {
        val action = AssistantAction(type = "CLICK_NODE", nodeId = "send")

        assertTrue(RiskClassifier.requiresConfirmation("发送这条消息", listOf(action)))
    }

    @Test
    fun scrollingDoesNotRequireConfirmation() {
        val action = AssistantAction(type = "SCROLL_FORWARD")

        assertFalse(RiskClassifier.requiresConfirmation("向下滚动", listOf(action)))
    }

    @Test
    fun settingTextAloneDoesNotRequireConfirmation() {
        val action = AssistantAction(type = "SET_TEXT", nodeId = "input", text = "你好，我到了")

        assertFalse(RiskClassifier.requiresConfirmation("输入：你好，我到了", listOf(action)))
    }

    @Test
    fun clickOnHighRiskTargetNodeTextRequiresConfirmation() {
        val action = AssistantAction(type = "CLICK_NODE", nodeId = "node_pay")
        val screen = screenWith(
            ScreenNode(
                nodeId = "node_pay",
                text = "立即支付",
                contentDescription = null,
                role = "Button",
                bounds = NodeBounds(0, 0, 200, 80),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        )

        assertTrue(RiskClassifier.requiresConfirmation("点这个", listOf(action), screen))
    }

    @Test
    fun clickOnSafeTargetNodeTextDoesNotRequireConfirmation() {
        val action = AssistantAction(type = "CLICK_NODE", nodeId = "node_ok")
        val screen = screenWith(
            ScreenNode(
                nodeId = "node_ok",
                text = "确定",
                contentDescription = null,
                role = "Button",
                bounds = NodeBounds(0, 0, 200, 80),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        )

        assertFalse(RiskClassifier.requiresConfirmation("点击确定", listOf(action), screen))
    }

    @Test
    fun openingPaymentRelatedAppRequiresConfirmation() {
        val action = AssistantAction(type = "OPEN_APP", appPackage = "com.example.wallet")

        assertTrue(RiskClassifier.requiresConfirmation("打开支付应用", listOf(action)))
    }

    @Test
    fun openingSafeAppDoesNotRequireConfirmation() {
        val action = AssistantAction(type = "OPEN_APP", appPackage = "com.android.settings")

        assertFalse(RiskClassifier.requiresConfirmation("打开设置", listOf(action)))
    }

    @Test
    fun paymentPasswordPageRejectsActionExecution() {
        val action = AssistantAction(type = "CLICK_NODE", nodeId = "node_ok")
        val screen = screenWith(
            ScreenNode(
                nodeId = "title",
                text = "请输入支付密码",
                contentDescription = null,
                role = "Text",
                bounds = NodeBounds(0, 0, 400, 80),
                clickable = false,
                editable = false,
                scrollable = false,
            ),
            ScreenNode(
                nodeId = "node_ok",
                text = "确定",
                contentDescription = null,
                role = "Button",
                bounds = NodeBounds(0, 100, 200, 180),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        )

        assertTrue(RiskClassifier.shouldRejectActionsInContext(listOf(action), screen))
    }

    @Test
    fun verificationCodePageRejectsTextEntry() {
        val action = AssistantAction(type = "SET_TEXT", nodeId = "code_input", text = "123456")
        val screen = screenWith(
            ScreenNode(
                nodeId = "code_input",
                text = null,
                contentDescription = "验证码输入框",
                role = "EditText",
                bounds = NodeBounds(0, 0, 400, 80),
                clickable = true,
                editable = true,
                scrollable = false,
            ),
        )

        assertTrue(RiskClassifier.shouldRejectActionsInContext(listOf(action), screen))
    }

    @Test
    fun highRiskPageDoesNotRejectReadOnlyResponseWithoutActions() {
        val screen = screenWith(
            ScreenNode(
                nodeId = "title",
                text = "订单付款",
                contentDescription = null,
                role = "Text",
                bounds = NodeBounds(0, 0, 400, 80),
                clickable = false,
                editable = false,
                scrollable = false,
            ),
        )

        assertFalse(RiskClassifier.shouldRejectActionsInContext(emptyList(), screen))
    }

    private fun screenWith(vararg nodes: ScreenNode): ScreenContext =
        ScreenContext(
            packageName = "com.android.settings",
            activityName = null,
            nodes = nodes.toList(),
            screenshotBase64 = null,
        )
}
