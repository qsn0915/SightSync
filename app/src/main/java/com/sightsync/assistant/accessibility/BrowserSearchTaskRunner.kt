package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AgentPlan
import com.sightsync.assistant.ai.AgentPlanStep
import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import kotlinx.coroutines.withTimeoutOrNull

sealed interface BrowserSearchTaskResult {
    data object Completed : BrowserSearchTaskResult
    data class Stopped(val reason: String) : BrowserSearchTaskResult
}

fun interface BrowserSearchTaskExecutor {
    suspend fun execute(browserPackage: String, query: String): BrowserSearchTaskResult
}

class BrowserSearchTaskRunner(
    private val screenContextProvider: ScreenContextProvider,
    private val planExecutor: AgentPlanExecutor,
) : BrowserSearchTaskExecutor {
    override suspend fun execute(
        browserPackage: String,
        query: String,
    ): BrowserSearchTaskResult {
        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MILLIS) {
            executeWithinTimeout(browserPackage, query)
        }
        return result ?: BrowserSearchTaskResult.Stopped("浏览器搜索任务超时，已停止后续操作。")
    }

    private suspend fun executeWithinTimeout(
        browserPackage: String,
        query: String,
    ): BrowserSearchTaskResult {
        val initialScreen = screenContextProvider.collectForValidation()
        if (initialScreen.packageName.isBlank()) {
            return BrowserSearchTaskResult.Stopped("无法确认当前应用，已停止搜索。")
        }
        executePlan(
            actionWaitPlan(
                goal = goal(query),
                actionId = "open_browser",
                action = AssistantAction(type = "OPEN_APP", appPackage = browserPackage),
                actionPrecondition = PageExpectation(packageName = initialScreen.packageName),
                followId = "wait_browser",
                followKind = "WAIT_FOR_UI",
                followPrecondition = PageExpectation(packageName = browserPackage),
                followTimeoutMillis = WAIT_TIMEOUT_MILLIS,
            ),
        )?.let { return it }

        var browserScreen = collectBrowserScreen(browserPackage) ?: return packageChanged()
        var addressNode = when (val lookup = BrowserSearchNodeLocator.findAddressOrSearchField(browserScreen)) {
            is BrowserNodeLookup.Found -> lookup.node
            is BrowserNodeLookup.Missing -> return BrowserSearchTaskResult.Stopped(lookup.reason)
            is BrowserNodeLookup.Ambiguous -> return BrowserSearchTaskResult.Stopped(lookup.reason)
        }

        if (!addressNode.editable) {
            executePlan(
                actionWaitPlan(
                    goal = goal(query),
                    actionId = "focus_address",
                    action = AssistantAction(type = "CLICK_NODE", nodeId = addressNode.nodeId),
                    actionPrecondition = PageExpectation(
                        packageName = browserPackage,
                        requiredNodeIds = listOf(addressNode.nodeId),
                    ),
                    followId = "validate_address",
                    followKind = "VALIDATE_PAGE",
                    followPrecondition = PageExpectation(packageName = browserPackage),
                    followTimeoutMillis = VALIDATE_TIMEOUT_MILLIS,
                ),
            )?.let { return it }
            browserScreen = collectBrowserScreen(browserPackage) ?: return packageChanged()
            addressNode = when (val lookup = BrowserSearchNodeLocator.findAddressOrSearchField(browserScreen)) {
                is BrowserNodeLookup.Found -> lookup.node
                is BrowserNodeLookup.Missing -> return BrowserSearchTaskResult.Stopped(lookup.reason)
                is BrowserNodeLookup.Ambiguous -> return BrowserSearchTaskResult.Stopped(lookup.reason)
            }
            if (!addressNode.editable) {
                return BrowserSearchTaskResult.Stopped("搜索或地址栏未进入可输入状态，已停止搜索。")
            }
        }

        executePlan(
            actionWaitPlan(
                goal = goal(query),
                actionId = "input_query",
                action = AssistantAction(type = "SET_TEXT", nodeId = addressNode.nodeId, text = query),
                actionPrecondition = PageExpectation(
                    packageName = browserPackage,
                    requiredNodeIds = listOf(addressNode.nodeId),
                ),
                followId = "validate_input",
                followKind = "VALIDATE_PAGE",
                followPrecondition = PageExpectation(packageName = browserPackage),
                followTimeoutMillis = VALIDATE_TIMEOUT_MILLIS,
            ),
        )?.let { return it }

        browserScreen = collectBrowserScreen(browserPackage) ?: return packageChanged()
        val submitNode = when (val lookup = BrowserSearchNodeLocator.findSubmitTarget(browserScreen, query)) {
            is BrowserNodeLookup.Found -> lookup.node
            is BrowserNodeLookup.Missing -> return BrowserSearchTaskResult.Stopped(lookup.reason)
            is BrowserNodeLookup.Ambiguous -> return BrowserSearchTaskResult.Stopped(lookup.reason)
        }
        executePlan(
            actionWaitPlan(
                goal = goal(query),
                actionId = "submit_search",
                action = AssistantAction(type = "CLICK_NODE", nodeId = submitNode.nodeId),
                actionPrecondition = PageExpectation(
                    packageName = browserPackage,
                    requiredNodeIds = listOf(submitNode.nodeId),
                ),
                followId = "wait_results",
                followKind = "WAIT_FOR_UI",
                followPrecondition = PageExpectation(packageName = browserPackage),
                followTimeoutMillis = WAIT_TIMEOUT_MILLIS,
            ),
        )?.let { return it }

        return BrowserSearchTaskResult.Completed
    }

    private suspend fun collectBrowserScreen(browserPackage: String): ScreenContext? =
        screenContextProvider.collectForValidation().takeIf { it.packageName == browserPackage }

    private suspend fun executePlan(plan: AgentPlan): BrowserSearchTaskResult.Stopped? =
        when (val result = planExecutor.execute(plan)) {
            is AgentPlanExecutionResult.Completed -> null
            is AgentPlanExecutionResult.Stopped -> BrowserSearchTaskResult.Stopped(result.reason)
            is AgentPlanExecutionResult.Invalid -> BrowserSearchTaskResult.Stopped(
                "浏览器搜索计划无效，已停止执行。${result.reason}",
            )
            is AgentPlanExecutionResult.ConfirmationRequired -> BrowserSearchTaskResult.Stopped(
                "当前搜索步骤需要二次确认，已停止后续操作。",
            )
        }

    private fun actionWaitPlan(
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

    private fun goal(query: String): String = "用浏览器搜索$query"

    private fun packageChanged() =
        BrowserSearchTaskResult.Stopped("当前应用与浏览器搜索计划不一致，已停止后续操作。")

    private companion object {
        const val TOTAL_TIMEOUT_MILLIS = 60_000L
        const val ACTION_TIMEOUT_MILLIS = 8_000L
        const val WAIT_TIMEOUT_MILLIS = 7_000L
        const val VALIDATE_TIMEOUT_MILLIS = 2_000L
    }
}
