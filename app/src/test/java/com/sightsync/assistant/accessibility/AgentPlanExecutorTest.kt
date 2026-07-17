package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AgentPlan
import com.sightsync.assistant.ai.AgentPlanStep
import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.ActionResult
import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentPlanExecutorTest {
    @Test
    fun executesActionThenWaitsForStablePageBeforeValidation() = runTest {
        val source = screenContext(packageName = "com.start", text = "起始页")
        val stable = screenContext(packageName = "com.done", text = "完成页")
        val provider = ExecutorScreenProvider(source, stable, stable, stable)
        val runner = RecordingActionRunner()
        val executor = executor(provider, runner)

        val result = executor.execute(
            plan(
                actionStep(
                    id = "back",
                    action = AssistantAction(type = "GLOBAL_BACK"),
                    packageName = "com.start",
                ),
                validateStep(id = "validate_done", packageName = "com.done"),
            ),
        )

        assertEquals(AgentPlanExecutionResult.Completed(listOf("back", "validate_done")), result)
        assertEquals(1, runner.executions.size)
        assertEquals(source, runner.executions.single().sourceScreen)
        assertEquals("GLOBAL_BACK", runner.executions.single().action.type)
    }

    @Test
    fun stopsBeforeActionWhenPagePreconditionChanges() = runTest {
        val provider = ExecutorScreenProvider(screenContext(packageName = "com.actual", text = "页面"))
        val runner = RecordingActionRunner()

        val result = executor(provider, runner).execute(
            plan(
                actionStep(
                    id = "back",
                    action = AssistantAction(type = "GLOBAL_BACK"),
                    packageName = "com.expected",
                ),
                validateStep(id = "never", packageName = "com.expected"),
            ),
        )

        assertEquals(
            AgentPlanExecutionResult.Stopped(
                completedStepIds = emptyList(),
                failedStepId = "back",
                reason = "当前应用与计划不一致。",
            ),
            result,
        )
        assertTrue(runner.executions.isEmpty())
    }

    @Test
    fun waitStepCompletesOnlyAfterExpectedPageStabilizes() = runTest {
        val loading = screenContext(packageName = "com.browser", text = "加载中")
        val ready = screenContext(packageName = "com.browser", text = "搜索框已就绪")
        val provider = ExecutorScreenProvider(loading, ready, ready, ready)

        val result = executor(provider, RecordingActionRunner()).execute(
            plan(
                waitStep(
                    id = "wait_ready",
                    expectation = PageExpectation(
                        packageName = "com.browser",
                        requiredTexts = listOf("搜索框"),
                    ),
                ),
                validateStep(id = "validate_ready", packageName = "com.browser"),
            ),
        )

        assertEquals(AgentPlanExecutionResult.Completed(listOf("wait_ready", "validate_ready")), result)
    }

    @Test
    fun stopsOnWaitTimeoutAndActionFailure() = runTest {
        val first = screenContext(packageName = "com.browser", text = "第一帧")
        val second = screenContext(packageName = "com.browser", text = "第二帧")
        val alternating = ExecutorAlternatingScreenProvider(first, second)
        val timeoutResult = executor(alternating, RecordingActionRunner()).execute(
            plan(
                waitStep(
                    id = "wait",
                    expectation = PageExpectation(packageName = "com.browser"),
                    timeoutMillis = 300,
                ),
                validateStep(id = "never", packageName = "com.browser", timeoutMillis = 300),
                maxDurationMillis = 1_000,
            ),
        )
        assertEquals(
            AgentPlanExecutionResult.Stopped(
                completedStepIds = emptyList(),
                failedStepId = "wait",
                reason = "等待页面稳定超时，已停止后续操作。",
            ),
            timeoutResult,
        )

        val source = screenContext(packageName = "com.start", text = "页面")
        val failureRunner = RecordingActionRunner(
            result = ActionResult(success = false, message = "执行失败。"),
        )
        val failureResult = executor(ExecutorScreenProvider(source), failureRunner).execute(
            plan(
                actionStep("back", AssistantAction(type = "GLOBAL_BACK"), "com.start"),
                validateStep("never", "com.start"),
            ),
        )
        assertEquals(
            AgentPlanExecutionResult.Stopped(emptyList(), "back", "执行失败。"),
            failureResult,
        )
    }

    @Test
    fun blocksHighRiskContextAndReturnsConfirmationBeforeAction() = runTest {
        val paymentScreen = screenContext(packageName = "com.wallet", text = "支付密码")
        val riskRunner = RecordingActionRunner()
        val riskResult = executor(ExecutorScreenProvider(paymentScreen), riskRunner).execute(
            plan(
                actionStep("back", AssistantAction(type = "GLOBAL_BACK"), "com.wallet"),
                validateStep("never", "com.wallet"),
            ),
        )
        assertEquals(
            AgentPlanExecutionResult.Stopped(
                emptyList(),
                "back",
                "当前页面包含支付、密码或验证码等高风险内容，已停止后续操作。",
            ),
            riskResult,
        )
        assertTrue(riskRunner.executions.isEmpty())

        val sendScreen = screenContext(
            packageName = "com.chat",
            text = "发送",
            nodeId = "node_send",
            clickable = true,
        )
        val confirmRunner = RecordingActionRunner()
        val confirmStep = actionStep(
            id = "send",
            action = AssistantAction(type = "CLICK_NODE", nodeId = "node_send"),
            packageName = "com.chat",
        )
        val confirmResult = executor(ExecutorScreenProvider(sendScreen), confirmRunner).execute(
            plan(
                confirmStep,
                validateStep("never", "com.chat"),
                goal = "发送消息",
            ),
        )
        assertEquals(
            AgentPlanExecutionResult.ConfirmationRequired(emptyList(), confirmStep, sendScreen),
            confirmResult,
        )
        assertTrue(confirmRunner.executions.isEmpty())
    }

    @Test
    fun externalCancellationStopsWaitingAndSkipsLaterAction() = runTest {
        val first = screenContext(packageName = "com.browser", text = "第一帧")
        val second = screenContext(packageName = "com.browser", text = "第二帧")
        val provider = ExecutorAlternatingScreenProvider(first, second)
        val runner = RecordingActionRunner()
        val execution = async {
            executor(provider, runner).execute(
                plan(
                    waitStep(
                        id = "wait",
                        expectation = PageExpectation(packageName = "com.browser"),
                        timeoutMillis = 5_000,
                    ),
                    actionStep(
                        id = "later",
                        action = AssistantAction(type = "GLOBAL_BACK"),
                        packageName = "com.browser",
                        timeoutMillis = 5_000,
                    ),
                    maxDurationMillis = 10_000,
                ),
            )
        }
        runCurrent()

        execution.cancel()

        try {
            execution.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            assertTrue(runner.executions.isEmpty())
        }
    }

    private fun executor(
        provider: ScreenContextProvider,
        runner: RecordingActionRunner,
    ) = AgentPlanExecutor(
        screenContextProvider = provider,
        actionRunner = runner,
        stabilityWaiter = PageStabilityWaiter(provider, pollIntervalMillis = 100),
    )

    private fun plan(
        vararg steps: AgentPlanStep,
        goal: String = "完成低风险任务",
        maxDurationMillis: Long = steps.sumOf { it.timeoutMillis },
    ) = AgentPlan(
        goal = goal,
        maxDurationMillis = maxDurationMillis,
        maxConsecutiveFailures = 1,
        steps = steps.toList(),
    )

    private fun actionStep(
        id: String,
        action: AssistantAction,
        packageName: String,
        timeoutMillis: Long = 1_000,
        requiresConfirmation: Boolean = false,
    ) = AgentPlanStep(
        id = id,
        kind = "ACTION",
        action = action,
        precondition = PageExpectation(packageName = packageName),
        timeoutMillis = timeoutMillis,
        requiresConfirmation = requiresConfirmation,
    )

    private fun waitStep(
        id: String,
        expectation: PageExpectation,
        timeoutMillis: Long = 1_000,
    ) = AgentPlanStep(
        id = id,
        kind = "WAIT_FOR_UI",
        precondition = expectation,
        timeoutMillis = timeoutMillis,
        requiresConfirmation = false,
    )

    private fun validateStep(
        id: String,
        packageName: String,
        timeoutMillis: Long = 1_000,
    ) = AgentPlanStep(
        id = id,
        kind = "VALIDATE_PAGE",
        precondition = PageExpectation(packageName = packageName),
        timeoutMillis = timeoutMillis,
        requiresConfirmation = false,
    )

    private fun screenContext(
        packageName: String,
        text: String,
        nodeId: String = "node_1",
        clickable: Boolean = false,
    ) = ScreenContext(
        packageName = packageName,
        activityName = "Activity",
        nodes = listOf(
            ScreenNode(
                nodeId = nodeId,
                text = text,
                contentDescription = null,
                role = "View",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = clickable,
                editable = false,
                scrollable = false,
            ),
        ),
        screenshotBase64 = null,
    )
}

private data class RecordedExecution(
    val action: AssistantAction,
    val sourceScreen: ScreenContext,
)

private class RecordingActionRunner(
    private val result: ActionResult = ActionResult(success = true, message = "已执行。"),
) : ActionRunner {
    val executions = mutableListOf<RecordedExecution>()

    override fun execute(
        actions: List<AssistantAction>,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): List<ActionResult> {
        actions.forEach { action -> executions += RecordedExecution(action, sourceScreen) }
        return actions.map { result }
    }
}

private class ExecutorScreenProvider(
    vararg screens: ScreenContext,
) : ScreenContextProvider {
    private val pending = ArrayDeque(screens.toList())
    private val fallback = screens.last()

    override suspend fun collect(): ScreenContext = error("Plan executor must use validation collection")

    override suspend fun collectForValidation(): ScreenContext =
        pending.removeFirstOrNull() ?: fallback
}

private class ExecutorAlternatingScreenProvider(
    private val first: ScreenContext,
    private val second: ScreenContext,
) : ScreenContextProvider {
    private var calls = 0

    override suspend fun collect(): ScreenContext = error("Plan executor must use validation collection")

    override suspend fun collectForValidation(): ScreenContext {
        val result = if (calls % 2 == 0) first else second
        calls += 1
        return result
    }
}
