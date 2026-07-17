package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AgentPlan
import com.sightsync.assistant.ai.AgentPlanStep
import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WeChatDraftTaskResult {
    data object DraftReady : WeChatDraftTaskResult
    data class Stopped(val reason: String) : WeChatDraftTaskResult
}

fun interface WeChatDraftTaskExecutor {
    suspend fun execute(contact: String, message: String): WeChatDraftTaskResult
}

class WeChatDraftTaskRunner(
    private val screenContextProvider: ScreenContextProvider,
    private val planExecutor: AgentPlanExecutor,
) : WeChatDraftTaskExecutor {
    override suspend fun execute(contact: String, message: String): WeChatDraftTaskResult {
        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MILLIS) {
            executeWithinTimeout(contact, message)
        }
        return result ?: WeChatDraftTaskResult.Stopped("微信草稿任务超时，已停止后续操作。")
    }

    private suspend fun executeWithinTimeout(
        contact: String,
        message: String,
    ): WeChatDraftTaskResult {
        val budget = ActionBudget()
        var screen = collectWeChatScreen() ?: return packageChanged()
        var searchTarget = when (val lookup = WeChatDraftNodeLocator.findSearchTarget(screen)) {
            is WeChatNodeLookup.Found -> lookup.node
            is WeChatNodeLookup.Missing -> return WeChatDraftTaskResult.Stopped(lookup.reason)
            is WeChatNodeLookup.Ambiguous -> return WeChatDraftTaskResult.Stopped(lookup.reason)
        }

        if (!searchTarget.editable) {
            executePlan(
                plan = actionFollowPlan(
                    goal = goal(contact),
                    actionId = "open_wechat_search",
                    action = AssistantAction(type = "CLICK_NODE", nodeId = searchTarget.nodeId),
                    actionPrecondition = PageExpectation(
                        packageName = WECHAT_PACKAGE,
                        requiredNodeIds = listOf(searchTarget.nodeId),
                    ),
                    followId = "wait_wechat_search",
                    followKind = "WAIT_FOR_UI",
                    followPrecondition = PageExpectation(packageName = WECHAT_PACKAGE),
                    followTimeoutMillis = WAIT_TIMEOUT_MILLIS,
                ),
                budget = budget,
            )?.let { return it }
            screen = collectWeChatScreen() ?: return packageChanged()
            searchTarget = when (val lookup = WeChatDraftNodeLocator.findSearchTarget(screen)) {
                is WeChatNodeLookup.Found -> lookup.node
                is WeChatNodeLookup.Missing -> return WeChatDraftTaskResult.Stopped(lookup.reason)
                is WeChatNodeLookup.Ambiguous -> return WeChatDraftTaskResult.Stopped(lookup.reason)
            }
            if (!searchTarget.editable) {
                return WeChatDraftTaskResult.Stopped(
                    "微信搜索入口未进入可输入状态，已停止填写草稿。",
                )
            }
        }

        executePlan(
            plan = actionFollowPlan(
                goal = goal(contact),
                actionId = "input_wechat_contact",
                action = AssistantAction(type = "SET_TEXT", nodeId = searchTarget.nodeId, text = contact),
                actionPrecondition = PageExpectation(
                    packageName = WECHAT_PACKAGE,
                    requiredNodeIds = listOf(searchTarget.nodeId),
                ),
                followId = "validate_wechat_contacts",
                followKind = "VALIDATE_PAGE",
                followPrecondition = PageExpectation(packageName = WECHAT_PACKAGE),
                followTimeoutMillis = VALIDATE_TIMEOUT_MILLIS,
            ),
            budget = budget,
        )?.let { return it }

        screen = collectWeChatScreen() ?: return packageChanged()
        val contactTarget = when (val lookup = WeChatDraftNodeLocator.findContact(screen, contact)) {
            is WeChatNodeLookup.Found -> lookup.node
            is WeChatNodeLookup.Missing -> return WeChatDraftTaskResult.Stopped(lookup.reason)
            is WeChatNodeLookup.Ambiguous -> return WeChatDraftTaskResult.Stopped(lookup.reason)
        }
        executePlan(
            plan = actionFollowPlan(
                goal = goal(contact),
                actionId = "open_wechat_contact",
                action = AssistantAction(type = "CLICK_NODE", nodeId = contactTarget.nodeId),
                actionPrecondition = PageExpectation(
                    packageName = WECHAT_PACKAGE,
                    requiredNodeIds = listOf(contactTarget.nodeId),
                ),
                followId = "wait_wechat_chat",
                followKind = "WAIT_FOR_UI",
                followPrecondition = PageExpectation(packageName = WECHAT_PACKAGE),
                followTimeoutMillis = WAIT_TIMEOUT_MILLIS,
            ),
            budget = budget,
        )?.let { return it }

        screen = collectWeChatScreen() ?: return packageChanged()
        val messageInput = when (val lookup = WeChatDraftNodeLocator.findMessageInput(screen)) {
            is WeChatNodeLookup.Found -> lookup.node
            is WeChatNodeLookup.Missing -> return WeChatDraftTaskResult.Stopped(lookup.reason)
            is WeChatNodeLookup.Ambiguous -> return WeChatDraftTaskResult.Stopped(lookup.reason)
        }
        executePlan(
            plan = actionFollowPlan(
                goal = goal(contact),
                actionId = "input_wechat_draft",
                action = AssistantAction(type = "SET_TEXT", nodeId = messageInput.nodeId, text = message),
                actionPrecondition = PageExpectation(
                    packageName = WECHAT_PACKAGE,
                    requiredNodeIds = listOf(messageInput.nodeId),
                ),
                followId = "validate_wechat_draft",
                followKind = "VALIDATE_PAGE",
                followPrecondition = PageExpectation(packageName = WECHAT_PACKAGE),
                followTimeoutMillis = VALIDATE_TIMEOUT_MILLIS,
            ),
            budget = budget,
        )?.let { return it }

        return WeChatDraftTaskResult.DraftReady
    }

    private suspend fun collectWeChatScreen(): ScreenContext? =
        screenContextProvider.collectForValidation().takeIf { it.packageName == WECHAT_PACKAGE }

    private suspend fun executePlan(
        plan: AgentPlan,
        budget: ActionBudget,
    ): WeChatDraftTaskResult.Stopped? {
        if (budget.actionCount >= MAX_ACTIONS) {
            return WeChatDraftTaskResult.Stopped("微信草稿任务超过动作上限，已停止后续操作。")
        }
        budget.actionCount += 1
        return when (val result = planExecutor.execute(plan)) {
            is AgentPlanExecutionResult.Completed -> null
            is AgentPlanExecutionResult.Stopped -> WeChatDraftTaskResult.Stopped(result.reason)
            is AgentPlanExecutionResult.Invalid ->
                WeChatDraftTaskResult.Stopped("微信草稿计划无效，已停止执行。${result.reason}")
            is AgentPlanExecutionResult.ConfirmationRequired ->
                WeChatDraftTaskResult.Stopped("当前步骤需要二次确认，已停止填写草稿。")
        }
    }

    private fun actionFollowPlan(
        goal: String,
        actionId: String,
        action: AssistantAction,
        actionPrecondition: PageExpectation,
        followId: String,
        followKind: String,
        followPrecondition: PageExpectation,
        followTimeoutMillis: Long,
    ): AgentPlan =
        AgentPlan(
            goal = goal,
            maxDurationMillis = ACTION_TIMEOUT_MILLIS + followTimeoutMillis,
            maxConsecutiveFailures = 1,
            steps = listOf(
                AgentPlanStep(
                    id = actionId,
                    kind = "ACTION",
                    action = action,
                    precondition = actionPrecondition,
                    timeoutMillis = ACTION_TIMEOUT_MILLIS,
                    requiresConfirmation = false,
                ),
                AgentPlanStep(
                    id = followId,
                    kind = followKind,
                    action = null,
                    precondition = followPrecondition,
                    timeoutMillis = followTimeoutMillis,
                    requiresConfirmation = false,
                ),
            ),
        )

    private fun goal(contact: String): String = "为${contact}准备微信草稿"

    private fun packageChanged() =
        WeChatDraftTaskResult.Stopped("当前应用不是微信或页面已变化，已停止填写草稿。")

    private class ActionBudget(var actionCount: Int = 0)

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val MAX_ACTIONS = 4
        private const val TOTAL_TIMEOUT_MILLIS = 60_000L
        private const val ACTION_TIMEOUT_MILLIS = 8_000L
        private const val WAIT_TIMEOUT_MILLIS = 7_000L
        private const val VALIDATE_TIMEOUT_MILLIS = 2_000L
    }
}
