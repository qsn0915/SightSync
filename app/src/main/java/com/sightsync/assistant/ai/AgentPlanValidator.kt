package com.sightsync.assistant.ai

object AgentPlanValidator {
    private const val MIN_PLAN_STEPS = 2
    private const val MAX_PLAN_STEPS = 8
    private const val MIN_PLAN_DURATION_MILLIS = 1_000L
    private const val MAX_PLAN_DURATION_MILLIS = 60_000L
    private const val MIN_STEP_TIMEOUT_MILLIS = 250L
    private const val MAX_STEP_TIMEOUT_MILLIS = 15_000L
    private const val MAX_CONSECUTIVE_FAILURES = 2
    private val allowedStepKinds = setOf("ACTION", "WAIT_FOR_UI", "VALIDATE_PAGE")

    fun validate(plan: AgentPlan): ProtocolValidationResult {
        if (plan.goal.isBlank()) {
            return invalid("plan goal 不能为空")
        }
        if (plan.steps.size !in MIN_PLAN_STEPS..MAX_PLAN_STEPS) {
            return invalid("plan 只允许 2 到 8 个步骤")
        }
        if (plan.maxDurationMillis !in MIN_PLAN_DURATION_MILLIS..MAX_PLAN_DURATION_MILLIS) {
            return invalid("plan maxDurationMillis 必须在 1000 到 60000 之间")
        }
        if (plan.maxConsecutiveFailures !in 1..MAX_CONSECUTIVE_FAILURES) {
            return invalid("plan maxConsecutiveFailures 必须在 1 到 2 之间")
        }

        val stepIds = mutableSetOf<String>()
        var totalStepTimeoutMillis = 0L
        plan.steps.forEach { step ->
            if (step.id.isBlank()) {
                return invalid("plan step id 不能为空")
            }
            if (!stepIds.add(step.id)) {
                return invalid("plan step id 必须唯一")
            }
            if (step.kind !in allowedStepKinds) {
                return invalid("不支持的计划步骤类型：${step.kind}")
            }
            if (step.timeoutMillis !in MIN_STEP_TIMEOUT_MILLIS..MAX_STEP_TIMEOUT_MILLIS) {
                return invalid("plan step ${step.id} timeoutMillis 必须在 250 到 15000 之间")
            }
            if (!step.precondition.hasCondition()) {
                return invalid("plan step ${step.id} 缺少页面前置条件")
            }

            if (step.kind == "ACTION") {
                val action = step.action ?: return invalid("ACTION step ${step.id} 缺少 action")
                val actionValidation = AiProtocolValidator.validateAction(action)
                if (!actionValidation.isValid) return actionValidation
            } else {
                if (step.action != null) {
                    return invalid("${step.kind} step ${step.id} 不能包含 action")
                }
                if (step.requiresConfirmation) {
                    return invalid("${step.kind} step ${step.id} 不能要求二次确认")
                }
            }

            totalStepTimeoutMillis += step.timeoutMillis
        }

        if (totalStepTimeoutMillis > plan.maxDurationMillis) {
            return invalid("plan 所有步骤超时之和不能超过 maxDurationMillis")
        }
        return ProtocolValidationResult(true)
    }

    private fun PageExpectation.hasCondition(): Boolean =
        !packageName.isNullOrBlank() ||
            !activityName.isNullOrBlank() ||
            requiredNodeIds.any { it.isNotBlank() } ||
            requiredTexts.any { it.isNotBlank() }

    private fun invalid(reason: String) = ProtocolValidationResult(false, reason)
}
