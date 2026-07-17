package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AgentPlan
import com.sightsync.assistant.ai.AgentPlanStep
import com.sightsync.assistant.ai.AgentPlanValidator
import com.sightsync.assistant.core.RiskClassifier
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AgentPlanExecutionResult {
    data class Completed(val completedStepIds: List<String>) : AgentPlanExecutionResult

    data class Stopped(
        val completedStepIds: List<String>,
        val failedStepId: String?,
        val reason: String,
    ) : AgentPlanExecutionResult

    data class ConfirmationRequired(
        val completedStepIds: List<String>,
        val step: AgentPlanStep,
        val sourceScreen: ScreenContext,
    ) : AgentPlanExecutionResult

    data class Invalid(val reason: String) : AgentPlanExecutionResult
}

class AgentPlanExecutor(
    private val screenContextProvider: ScreenContextProvider,
    private val actionRunner: ActionRunner,
    private val stabilityWaiter: PageStabilityWaiter = PageStabilityWaiter(screenContextProvider),
) {
    suspend fun execute(plan: AgentPlan): AgentPlanExecutionResult {
        val validation = AgentPlanValidator.validate(plan)
        if (!validation.isValid) {
            return AgentPlanExecutionResult.Invalid(validation.reason ?: "计划无效。")
        }

        val completedStepIds = mutableListOf<String>()
        var activeStepId: String? = null
        val result = withTimeoutOrNull(plan.maxDurationMillis) {
            executeSteps(
                plan = plan,
                completedStepIds = completedStepIds,
                onStepStarted = { activeStepId = it },
            )
        }
        return result ?: AgentPlanExecutionResult.Stopped(
            completedStepIds = completedStepIds.toList(),
            failedStepId = activeStepId,
            reason = "任务执行超时，已停止后续操作。",
        )
    }

    private suspend fun executeSteps(
        plan: AgentPlan,
        completedStepIds: MutableList<String>,
        onStepStarted: (String) -> Unit,
    ): AgentPlanExecutionResult {
        plan.steps.forEach { step ->
            onStepStarted(step.id)
            val stepResult = withTimeoutOrNull(step.timeoutMillis) {
                executeStep(plan, step)
            } ?: StepResult.Stopped(step.timeoutReason())

            when (stepResult) {
                StepResult.Completed -> completedStepIds += step.id
                is StepResult.ConfirmationRequired -> {
                    return AgentPlanExecutionResult.ConfirmationRequired(
                        completedStepIds = completedStepIds.toList(),
                        step = step,
                        sourceScreen = stepResult.sourceScreen,
                    )
                }
                is StepResult.Stopped -> {
                    return AgentPlanExecutionResult.Stopped(
                        completedStepIds = completedStepIds.toList(),
                        failedStepId = step.id,
                        reason = stepResult.reason,
                    )
                }
            }
        }
        return AgentPlanExecutionResult.Completed(completedStepIds.toList())
    }

    private suspend fun executeStep(plan: AgentPlan, step: AgentPlanStep): StepResult =
        when (step.kind) {
            "ACTION" -> executeAction(plan, step)
            "WAIT_FOR_UI" -> when (stabilityWaiter.awaitStable(step.precondition, step.timeoutMillis)) {
                is PageStabilityResult.Stable -> StepResult.Completed
                PageStabilityResult.TimedOut -> StepResult.Stopped(WAIT_TIMEOUT_MESSAGE)
            }
            "VALIDATE_PAGE" -> {
                val screen = screenContextProvider.collectForValidation()
                when (val match = PageExpectationMatcher.match(step.precondition, screen)) {
                    PageExpectationMatch.Matched -> StepResult.Completed
                    is PageExpectationMatch.Mismatch -> StepResult.Stopped(match.reason)
                }
            }
            else -> StepResult.Stopped("不支持的计划步骤。")
        }

    private suspend fun executeAction(plan: AgentPlan, step: AgentPlanStep): StepResult {
        val sourceScreen = screenContextProvider.collectForValidation()
        when (val match = PageExpectationMatcher.match(step.precondition, sourceScreen)) {
            PageExpectationMatch.Matched -> Unit
            is PageExpectationMatch.Mismatch -> return StepResult.Stopped(match.reason)
        }

        val action = checkNotNull(step.action)
        if (RiskClassifier.shouldRejectActionsInContext(listOf(action), sourceScreen)) {
            return StepResult.Stopped(HIGH_RISK_CONTEXT_MESSAGE)
        }
        if (
            step.requiresConfirmation ||
            RiskClassifier.requiresConfirmation(plan.goal, listOf(action), sourceScreen)
        ) {
            return StepResult.ConfirmationRequired(sourceScreen)
        }

        val failed = actionRunner.execute(
            actions = listOf(action),
            confirmed = false,
            sourceScreen = sourceScreen,
        ).firstOrNull { !it.success }
        if (failed != null) return StepResult.Stopped(failed.message)

        return when (stabilityWaiter.awaitStable(expectation = null, timeoutMillis = step.timeoutMillis)) {
            is PageStabilityResult.Stable -> StepResult.Completed
            PageStabilityResult.TimedOut -> StepResult.Stopped(WAIT_TIMEOUT_MESSAGE)
        }
    }

    private fun AgentPlanStep.timeoutReason(): String =
        if (kind == "WAIT_FOR_UI") WAIT_TIMEOUT_MESSAGE else "步骤执行超时，已停止后续操作。"

    private sealed interface StepResult {
        data object Completed : StepResult
        data class Stopped(val reason: String) : StepResult
        data class ConfirmationRequired(val sourceScreen: ScreenContext) : StepResult
    }

    private companion object {
        const val WAIT_TIMEOUT_MESSAGE = "等待页面稳定超时，已停止后续操作。"
        const val HIGH_RISK_CONTEXT_MESSAGE =
            "当前页面包含支付、密码或验证码等高风险内容，已停止后续操作。"
    }
}
