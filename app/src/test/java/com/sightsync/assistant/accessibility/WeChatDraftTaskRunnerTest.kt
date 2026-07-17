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
class WeChatDraftTaskRunnerTest {
    @Test
    fun fillsDraftWithThreeActionsWhenSearchIsEditable() = runTest {
        val scenario = WeChatScenario(searchInitiallyEditable = true)

        val result = scenario.runner.execute(CONTACT, MESSAGE)

        assertEquals(WeChatDraftTaskResult.DraftReady, result)
        assertEquals(
            listOf(
                AssistantAction(type = "SET_TEXT", nodeId = "search_input", text = CONTACT),
                AssistantAction(type = "CLICK_NODE", nodeId = "contact"),
                AssistantAction(type = "SET_TEXT", nodeId = "message_input", text = MESSAGE),
            ),
            scenario.actionRunner.executed,
        )
        assertTrue(scenario.provider.validationCalls > scenario.actionRunner.executed.size)
    }

    @Test
    fun clicksSearchEntryWhenNecessaryAndNeverClicksSend() = runTest {
        val scenario = WeChatScenario(searchInitiallyEditable = false)

        val result = scenario.runner.execute(CONTACT, MESSAGE)

        assertEquals(WeChatDraftTaskResult.DraftReady, result)
        assertEquals(4, scenario.actionRunner.executed.size)
        assertEquals(
            listOf("search_entry", "search_input", "contact", "message_input"),
            scenario.actionRunner.executed.mapNotNull(AssistantAction::nodeId),
        )
        assertTrue(scenario.actionRunner.executed.none { it.nodeId == "send" })
    }

    @Test
    fun stopsBeforeAnyActionWhenWeChatIsNotForeground() = runTest {
        val scenario = WeChatScenario(initialPackage = "com.other")

        val result = scenario.runner.execute(CONTACT, MESSAGE)

        assertTrue(result is WeChatDraftTaskResult.Stopped)
        assertTrue(scenario.actionRunner.executed.isEmpty())
    }

    @Test
    fun stopsForMissingOrAmbiguousContact() = runTest {
        val missing = WeChatScenario(contacts = weChatScreen(node("other", text = "李四", clickable = true)))
        val ambiguous = WeChatScenario(
            contacts = weChatScreen(
                node("contact_one", text = CONTACT, clickable = true),
                node("contact_two", description = CONTACT, clickable = true),
            ),
        )

        val missingResult = missing.runner.execute(CONTACT, MESSAGE)
        val ambiguousResult = ambiguous.runner.execute(CONTACT, MESSAGE)

        assertTrue(missingResult is WeChatDraftTaskResult.Stopped)
        assertEquals(listOf("SET_TEXT"), missing.actionRunner.executed.map(AssistantAction::type))
        assertTrue(ambiguousResult is WeChatDraftTaskResult.Stopped)
        assertEquals(listOf("SET_TEXT"), ambiguous.actionRunner.executed.map(AssistantAction::type))
    }

    @Test
    fun stopsWhenMessageInputIsMissing() = runTest {
        val scenario = WeChatScenario(chat = weChatScreen(node("title", text = CONTACT)))

        val result = scenario.runner.execute(CONTACT, MESSAGE)

        assertTrue(result is WeChatDraftTaskResult.Stopped)
        assertEquals(listOf("SET_TEXT", "CLICK_NODE"), scenario.actionRunner.executed.map(AssistantAction::type))
    }

    @Test
    fun stopsOnPackageChangeOrActionFailure() = runTest {
        val changed = WeChatScenario(packageChangesAfterContactInput = true)
        val failed = WeChatScenario(failActionNodeId = "contact")

        val changedResult = changed.runner.execute(CONTACT, MESSAGE)
        val failedResult = failed.runner.execute(CONTACT, MESSAGE)

        assertTrue(changedResult is WeChatDraftTaskResult.Stopped)
        assertEquals(listOf("SET_TEXT"), changed.actionRunner.executed.map(AssistantAction::type))
        assertEquals(WeChatDraftTaskResult.Stopped("测试动作失败。"), failedResult)
        assertEquals(listOf("SET_TEXT", "CLICK_NODE"), failed.actionRunner.executed.map(AssistantAction::type))
    }

    @Test
    fun stopsInsteadOfCrossingConfirmationBoundary() = runTest {
        val scenario = WeChatScenario(contact = "删除账号")

        val result = scenario.runner.execute("删除账号", MESSAGE)

        assertTrue(result is WeChatDraftTaskResult.Stopped)
        assertTrue((result as WeChatDraftTaskResult.Stopped).reason.contains("确认"))
        assertEquals(listOf("SET_TEXT"), scenario.actionRunner.executed.map(AssistantAction::type))
    }

    @Test
    fun totalTimeoutStopsWithoutActions() = runTest {
        val provider = SuspendingScreenProvider()
        val actionRunner = RecordingActionRunner()
        val runner = WeChatDraftTaskRunner(provider, AgentPlanExecutor(provider, actionRunner))

        val result = runner.execute(CONTACT, MESSAGE)

        assertTrue(result is WeChatDraftTaskResult.Stopped)
        assertTrue((result as WeChatDraftTaskResult.Stopped).reason.contains("超时"))
        assertTrue(actionRunner.executed.isEmpty())
    }

    @Test
    fun externalCancellationPropagatesWithoutActions() = runTest {
        val provider = SuspendingScreenProvider()
        val actionRunner = RecordingActionRunner()
        val runner = WeChatDraftTaskRunner(provider, AgentPlanExecutor(provider, actionRunner))
        val deferred = async { runner.execute(CONTACT, MESSAGE) }
        runCurrent()

        deferred.cancel()

        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            Unit
        }
        assertTrue(actionRunner.executed.isEmpty())
    }

    private class WeChatScenario(
        private val contact: String = CONTACT,
        searchInitiallyEditable: Boolean = true,
        initialPackage: String = WECHAT_PACKAGE,
        private val contacts: ScreenContext? = null,
        private val chat: ScreenContext? = null,
        private val packageChangesAfterContactInput: Boolean = false,
        private val failActionNodeId: String? = null,
    ) {
        private val initial = if (initialPackage == WECHAT_PACKAGE) {
            if (searchInitiallyEditable) {
                weChatScreen(node("search_input", description = "搜索", editable = true))
            } else {
                weChatScreen(node("search_entry", description = "搜索", clickable = true))
            }
        } else {
            screen(initialPackage)
        }
        private val search = weChatScreen(node("search_input", description = "搜索", editable = true))
        private val contactResults = contacts ?: weChatScreen(
            node("contact", clickable = true),
            node("contact_label", text = contact, parentId = "contact"),
        )
        private val chatScreen = chat ?: weChatScreen(
            node("message_input", description = "输入消息", editable = true),
            node("send", text = "发送", clickable = true),
        )
        private val draftScreen = weChatScreen(
            node("message_input", text = MESSAGE, description = "输入消息", editable = true),
            node("send", text = "发送", clickable = true),
        )
        val provider = MutableScreenProvider(initial)
        val actionRunner = RecordingActionRunner { action ->
            if (action.nodeId == failActionNodeId) return@RecordingActionRunner ActionResult(false, "测试动作失败。")
            provider.current = when (action.nodeId) {
                "search_entry" -> search
                "search_input" -> if (packageChangesAfterContactInput) screen("com.other") else contactResults
                "contact", "contact_one", "contact_two" -> chatScreen
                "message_input" -> draftScreen
                else -> provider.current
            }
            ActionResult(true, "成功。")
        }
        val runner = WeChatDraftTaskRunner(provider, AgentPlanExecutor(provider, actionRunner))
    }

    private class MutableScreenProvider(var current: ScreenContext) : ScreenContextProvider {
        var validationCalls = 0

        override suspend fun collect(): ScreenContext = current

        override suspend fun collectForValidation(): ScreenContext {
            validationCalls += 1
            return current
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
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val CONTACT = "张三"
        private const val MESSAGE = "明天上午十点见"

        private fun weChatScreen(vararg nodes: ScreenNode): ScreenContext =
            screen(WECHAT_PACKAGE, nodes.toList())

        private fun screen(packageName: String, nodes: List<ScreenNode> = emptyList()): ScreenContext =
            ScreenContext(packageName, "LauncherUI", nodes, screenshotBase64 = null)

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
