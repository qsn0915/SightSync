package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.core.ActionResult
import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSearchTaskRunnerTest {
    @Test
    fun executesOpenAddressInputAndSubmitInOrder() = runTest {
        val scenario = BrowserScenario(query = "无障碍新闻", addressInitiallyEditable = false)

        val result = scenario.runner.execute(BROWSER_PACKAGE, scenario.query)

        assertEquals(BrowserSearchTaskResult.Completed, result)
        assertEquals(
            listOf(
                AssistantAction(type = "OPEN_APP", appPackage = BROWSER_PACKAGE),
                AssistantAction(type = "CLICK_NODE", nodeId = "node_address"),
                AssistantAction(type = "SET_TEXT", nodeId = "node_input", text = scenario.query),
                AssistantAction(type = "CLICK_NODE", nodeId = "node_suggestion"),
            ),
            scenario.actionRunner.executed,
        )
        assertTrue(scenario.provider.validationCalls > 4)
    }

    @Test
    fun skipsAddressClickWhenBrowserExposesEditableField() = runTest {
        val scenario = BrowserScenario(query = "天气", addressInitiallyEditable = true)

        val result = scenario.runner.execute(BROWSER_PACKAGE, scenario.query)

        assertEquals(BrowserSearchTaskResult.Completed, result)
        assertEquals(
            listOf("OPEN_APP", "SET_TEXT", "CLICK_NODE"),
            scenario.actionRunner.executed.map { it.type },
        )
    }

    @Test
    fun stopsWhenAddressFieldIsMissingOrAmbiguous() = runTest {
        val missing = BrowserScenario(query = "天气", home = browserScreen(node("node_other", text = "主页")))
        val ambiguous = BrowserScenario(
            query = "天气",
            home = browserScreen(
                node("node_a", description = "Search address", editable = true),
                node("node_b", description = "Search web", editable = true),
            ),
        )

        val missingResult = missing.runner.execute(BROWSER_PACKAGE, missing.query)
        val ambiguousResult = ambiguous.runner.execute(BROWSER_PACKAGE, ambiguous.query)

        assertTrue(missingResult is BrowserSearchTaskResult.Stopped)
        assertTrue((missingResult as BrowserSearchTaskResult.Stopped).reason.contains("找不到"))
        assertEquals(listOf("OPEN_APP"), missing.actionRunner.executed.map { it.type })
        assertTrue(ambiguousResult is BrowserSearchTaskResult.Stopped)
        assertTrue((ambiguousResult as BrowserSearchTaskResult.Stopped).reason.contains("多个"))
        assertEquals(listOf("OPEN_APP"), ambiguous.actionRunner.executed.map { it.type })
    }

    @Test
    fun stopsWhenSubmitTargetIsMissingOrAmbiguous() = runTest {
        val missing = BrowserScenario(
            query = "天气",
            suggestion = browserScreen(node("node_result", text = "没有建议")),
        )
        val ambiguous = BrowserScenario(
            query = "天气",
            suggestion = browserScreen(
                node("node_suggestion_a", text = "天气", clickable = true),
                node("node_suggestion_b", description = "天气", clickable = true),
            ),
        )

        val missingResult = missing.runner.execute(BROWSER_PACKAGE, missing.query)
        val ambiguousResult = ambiguous.runner.execute(BROWSER_PACKAGE, ambiguous.query)

        assertTrue(missingResult is BrowserSearchTaskResult.Stopped)
        assertTrue((missingResult as BrowserSearchTaskResult.Stopped).reason.contains("找不到"))
        assertEquals(listOf("OPEN_APP", "CLICK_NODE", "SET_TEXT"), missing.actionRunner.executed.map { it.type })
        assertTrue(ambiguousResult is BrowserSearchTaskResult.Stopped)
        assertTrue((ambiguousResult as BrowserSearchTaskResult.Stopped).reason.contains("多个"))
        assertEquals(listOf("OPEN_APP", "CLICK_NODE", "SET_TEXT"), ambiguous.actionRunner.executed.map { it.type })
    }

    @Test
    fun stopsOnActionFailureOrUnexpectedPackageChange() = runTest {
        val failed = BrowserScenario(query = "天气", failActionType = "SET_TEXT")
        val changed = BrowserScenario(query = "天气", packageChangesAfterAddressClick = true)

        val failedResult = failed.runner.execute(BROWSER_PACKAGE, failed.query)
        val changedResult = changed.runner.execute(BROWSER_PACKAGE, changed.query)

        assertEquals(BrowserSearchTaskResult.Stopped("测试动作失败。"), failedResult)
        assertTrue(changedResult is BrowserSearchTaskResult.Stopped)
        assertTrue((changedResult as BrowserSearchTaskResult.Stopped).reason.contains("当前应用"))
        assertEquals(listOf("OPEN_APP", "CLICK_NODE"), changed.actionRunner.executed.map { it.type })
    }

    @Test
    fun stopsWhenLocalRiskClassifierRequiresConfirmation() = runTest {
        val scenario = BrowserScenario(query = "删除账号")

        val result = scenario.runner.execute(BROWSER_PACKAGE, scenario.query)

        assertTrue(result is BrowserSearchTaskResult.Stopped)
        assertTrue((result as BrowserSearchTaskResult.Stopped).reason.contains("二次确认"))
        assertTrue(scenario.actionRunner.executed.isEmpty())
    }

    @Test
    fun totalTimeoutStopsTaskWithoutRepeatingActions() = runTest {
        val provider = AlternatingScreenProvider(
            screen(packageName = "com.sightsync.assistant"),
            screen(packageName = "com.sightsync.assistant", activityName = "Other"),
        )
        val runner = RecordingActionRunner()
        val task = BrowserSearchTaskRunner(provider, AgentPlanExecutor(provider, runner))

        val result = task.execute(BROWSER_PACKAGE, "天气")

        assertTrue(result is BrowserSearchTaskResult.Stopped)
        assertTrue((result as BrowserSearchTaskResult.Stopped).reason.contains("超时"))
        assertEquals(1, runner.executed.size)
    }

    @Test
    fun externalCancellationPropagatesAndPreventsActions() = runTest {
        val provider = SuspendingScreenProvider()
        val runner = RecordingActionRunner()
        val task = BrowserSearchTaskRunner(provider, AgentPlanExecutor(provider, runner))
        val deferred = async { task.execute(BROWSER_PACKAGE, "天气") }
        runCurrent()

        deferred.cancel()

        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            Unit
        }
        assertTrue(runner.executed.isEmpty())
    }

    private class BrowserScenario(
        val query: String,
        addressInitiallyEditable: Boolean = false,
        home: ScreenContext? = null,
        private val suggestion: ScreenContext? = null,
        private val failActionType: String? = null,
        private val packageChangesAfterAddressClick: Boolean = false,
    ) {
        private val initial = screen(packageName = "com.sightsync.assistant")
        private val homeScreen = home ?: if (addressInitiallyEditable) {
            browserScreen(node("node_input", description = "Search or type web address", editable = true))
        } else {
            browserScreen(node("node_address", text = "搜索或输入网址", clickable = true))
        }
        private val editScreen = browserScreen(
            node("node_input", description = "Search or type web address", editable = true),
        )
        private val suggestionScreen = suggestion ?: browserScreen(
            node("node_suggestion", clickable = true),
            node("node_suggestion_label", text = query, parentId = "node_suggestion"),
        )
        private val resultScreen = browserScreen(node("node_result", text = "搜索结果"))
        val provider = MutableScreenProvider(initial)
        val actionRunner = RecordingActionRunner { action ->
            if (action.type == failActionType) return@RecordingActionRunner ActionResult(false, "测试动作失败。")
            provider.current = when {
                action.type == "OPEN_APP" -> homeScreen
                action.type == "CLICK_NODE" && action.nodeId == "node_address" -> {
                    if (packageChangesAfterAddressClick) screen(packageName = "com.other") else editScreen
                }
                action.type == "SET_TEXT" -> suggestionScreen
                action.type == "CLICK_NODE" -> resultScreen
                else -> provider.current
            }
            ActionResult(true, "成功。")
        }
        val runner = BrowserSearchTaskRunner(
            provider,
            AgentPlanExecutor(provider, actionRunner),
        )
    }

    private class MutableScreenProvider(var current: ScreenContext) : ScreenContextProvider {
        var validationCalls = 0

        override suspend fun collect(): ScreenContext = current

        override suspend fun collectForValidation(): ScreenContext {
            validationCalls += 1
            return current
        }
    }

    private class AlternatingScreenProvider(
        private val first: ScreenContext,
        private val second: ScreenContext,
    ) : ScreenContextProvider {
        private var calls = 0

        override suspend fun collect(): ScreenContext = collectForValidation()

        override suspend fun collectForValidation(): ScreenContext {
            val value = if (calls % 2 == 0) first else second
            calls += 1
            return value
        }
    }

    private class SuspendingScreenProvider : ScreenContextProvider {
        override suspend fun collect(): ScreenContext = awaitCancellation()

        override suspend fun collectForValidation(): ScreenContext = awaitCancellation()
    }

    private class RecordingActionRunner(
        private val onAction: (AssistantAction) -> ActionResult = { ActionResult(true, "成功。") },
    ) : ActionRunner {
        val executed = mutableListOf<AssistantAction>()

        override fun execute(
            actions: List<AssistantAction>,
            confirmed: Boolean,
            sourceScreen: ScreenContext,
        ): List<ActionResult> = actions.map { action ->
            executed += action
            onAction(action)
        }
    }

    companion object {
        private const val BROWSER_PACKAGE = "com.android.chrome"

        private fun browserScreen(vararg nodes: ScreenNode): ScreenContext =
            screen(packageName = BROWSER_PACKAGE, nodes = nodes.toList())

        private fun screen(
            packageName: String,
            activityName: String = "Main",
            nodes: List<ScreenNode> = emptyList(),
        ): ScreenContext =
            ScreenContext(packageName, activityName, nodes, screenshotBase64 = null)

        private fun node(
            id: String,
            text: String? = null,
            description: String? = null,
            clickable: Boolean = false,
            editable: Boolean = false,
            parentId: String? = null,
        ): ScreenNode =
            ScreenNode(
                nodeId = id,
                text = text,
                contentDescription = description,
                role = if (editable) "EditText" else "View",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = clickable,
                editable = editable,
                scrollable = false,
                parentNodeId = parentId,
            )
    }
}
