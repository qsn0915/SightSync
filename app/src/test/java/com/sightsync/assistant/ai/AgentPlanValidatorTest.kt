package com.sightsync.assistant.ai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanValidatorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun parsesAndAcceptsBoundedPlan() {
        val response = json.decodeFromString<AssistResponse>(VALID_PLAN_JSON)

        assertEquals("打开浏览器并等待页面稳定", response.plan?.goal)
        assertEquals(2, response.plan?.steps?.size)
        assertTrue(AiProtocolValidator.validate(response).isValid)
    }

    @Test
    fun rejectsPlanLimitsOutsideHardBounds() {
        val plan = validPlan()
        val cases = listOf(
            plan.copy(steps = listOf(plan.steps.first())) to "plan 只允许 2 到 8 个步骤",
            plan.copy(steps = List(9) { index -> plan.steps.first().copy(id = "step_$index") }) to
                "plan 只允许 2 到 8 个步骤",
            plan.copy(maxDurationMillis = 60_001) to
                "plan maxDurationMillis 必须在 1000 到 60000 之间",
            plan.copy(maxConsecutiveFailures = 3) to
                "plan maxConsecutiveFailures 必须在 1 到 2 之间",
            plan.copy(maxDurationMillis = 9_000) to
                "plan 所有步骤超时之和不能超过 maxDurationMillis",
        )

        cases.forEach { (candidate, expectedReason) ->
            val result = AiProtocolValidator.validate(validResponse(candidate))
            assertFalse(result.isValid)
            assertEquals(expectedReason, result.reason)
        }
    }

    @Test
    fun rejectsInvalidStepIdentityKindAndTimeout() {
        val plan = validPlan()
        val cases = listOf(
            plan.copy(steps = plan.steps.map { it.copy(id = "duplicate") }) to
                "plan step id 必须唯一",
            plan.copy(steps = plan.steps.mapIndexed { index, step ->
                if (index == 1) step.copy(kind = "RUN_SCRIPT") else step
            }) to "不支持的计划步骤类型：RUN_SCRIPT",
            plan.copy(steps = plan.steps.mapIndexed { index, step ->
                if (index == 0) step.copy(timeoutMillis = 20_000) else step
            }) to "plan step open_browser timeoutMillis 必须在 250 到 15000 之间",
        )

        cases.forEach { (candidate, expectedReason) ->
            assertEquals(expectedReason, AiProtocolValidator.validate(validResponse(candidate)).reason)
        }
    }

    @Test
    fun rejectsMissingPagePreconditionAndUnknownAction() {
        val plan = validPlan()
        val emptyPrecondition = plan.copy(steps = plan.steps.mapIndexed { index, step ->
            if (index == 0) step.copy(precondition = PageExpectation()) else step
        })
        val unknownAction = plan.copy(steps = plan.steps.mapIndexed { index, step ->
            if (index == 0) step.copy(action = AssistantAction(type = "RUN_SCRIPT")) else step
        })

        assertEquals(
            "plan step open_browser 缺少页面前置条件",
            AiProtocolValidator.validate(validResponse(emptyPrecondition)).reason,
        )
        assertEquals(
            "不支持的动作类型：RUN_SCRIPT",
            AiProtocolValidator.validate(validResponse(unknownAction)).reason,
        )
    }

    @Test
    fun rejectsActionsAndConfirmationOnInternalSteps() {
        val plan = validPlan()
        val actionOnWait = plan.copy(steps = plan.steps.mapIndexed { index, step ->
            if (index == 1) step.copy(action = AssistantAction(type = "GLOBAL_BACK")) else step
        })
        val confirmationOnWait = plan.copy(steps = plan.steps.mapIndexed { index, step ->
            if (index == 1) step.copy(requiresConfirmation = true) else step
        })

        assertEquals(
            "WAIT_FOR_UI step wait_browser 不能包含 action",
            AiProtocolValidator.validate(validResponse(actionOnWait)).reason,
        )
        assertEquals(
            "WAIT_FOR_UI step wait_browser 不能要求二次确认",
            AiProtocolValidator.validate(validResponse(confirmationOnWait)).reason,
        )
    }

    private fun validResponse(plan: AgentPlan = validPlan()) = AssistResponse(
        spoken = "我会分步完成。",
        actions = emptyList(),
        plan = plan,
    )

    private fun validPlan() = AgentPlan(
        goal = "打开浏览器并等待页面稳定",
        maxDurationMillis = 10_000,
        maxConsecutiveFailures = 2,
        steps = listOf(
            AgentPlanStep(
                id = "open_browser",
                kind = "ACTION",
                action = AssistantAction(type = "OPEN_APP", appPackage = "com.android.chrome"),
                precondition = PageExpectation(packageName = "com.android.launcher"),
                timeoutMillis = 5_000,
                requiresConfirmation = false,
            ),
            AgentPlanStep(
                id = "wait_browser",
                kind = "WAIT_FOR_UI",
                precondition = PageExpectation(packageName = "com.android.chrome"),
                timeoutMillis = 5_000,
                requiresConfirmation = false,
            ),
        ),
    )

    private companion object {
        val VALID_PLAN_JSON =
            """
            {
              "spoken": "我会分步完成。",
              "requiresConfirmation": false,
              "actions": [],
              "plan": {
                "goal": "打开浏览器并等待页面稳定",
                "maxDurationMillis": 10000,
                "maxConsecutiveFailures": 2,
                "steps": [
                  {
                    "id": "open_browser",
                    "kind": "ACTION",
                    "action": { "type": "OPEN_APP", "appPackage": "com.android.chrome" },
                    "precondition": { "packageName": "com.android.launcher" },
                    "timeoutMillis": 5000,
                    "requiresConfirmation": false
                  },
                  {
                    "id": "wait_browser",
                    "kind": "WAIT_FOR_UI",
                    "precondition": { "packageName": "com.android.chrome" },
                    "timeoutMillis": 5000,
                    "requiresConfirmation": false
                  }
                ]
              }
            }
            """.trimIndent()
    }
}
