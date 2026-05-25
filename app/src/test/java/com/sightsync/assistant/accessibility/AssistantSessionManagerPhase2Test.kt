package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.apps.AppCatalogProvider
import com.sightsync.assistant.apps.InstalledApp
import com.sightsync.assistant.apps.OpenAppCommandResolver
import com.sightsync.assistant.core.ActionResult
import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import com.sightsync.assistant.speech.SpeechInput
import com.sightsync.assistant.speech.SpeechInputResult
import com.sightsync.assistant.speech.SpeechOutput
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSessionManagerPhase2Test {
    @Test
    fun recognizedSpeechSpeaksScreenStatusAndAiResponse() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么"))
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "当前页面有设置和搜索。"))
        val actions = FakeActionRunner()
        val manager = manager(tts, speech, screen, ai, actions)

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(listOf("请说。", "正在查看当前屏幕。", "当前页面有设置和搜索。"), tts.spoken)
        assertEquals(1, screen.collectCount)
        assertEquals("这里有什么", ai.lastUtterance)
    }

    @Test
    fun oneShotPromptFinishesBeforeListeningStarts() = runTest {
        val tts = GateSpeechOutput()
        val speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么"))
        val manager = manager(tts, speech)

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("请说。", tts.awaitingText)
        assertFalse(speech.listenStarted.isCompleted)

        tts.finishSpeaking()
        advanceUntilIdle()

        assertTrue(speech.listenStarted.isCompleted)
    }

    @Test
    fun secondClickCancelsActiveRequestWithoutFailurePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput()
        val manager = manager(tts, speech)

        manager.onAssistantRequested()
        speech.listenStarted.await()

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(speech.cancelCalled)
        assertTrue(tts.stopCalled)
        assertTrue(tts.spoken.contains("已取消。"))
        assertFalse(tts.spoken.any { it.startsWith("操作失败") })
    }

    @Test
    fun continuousListeningProcessesMultipleUtterancesWithoutRepeatedOverlayRequests() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("这里有什么"),
            SpeechInputResult.Recognized("返回"),
            SpeechInputResult.Recognized("停止聆听"),
        )
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "好的。"))
        val manager = manager(tts, speech, screen, ai)

        manager.startContinuousListening()
        advanceUntilIdle()

        assertEquals(listOf("这里有什么", "返回"), ai.utterances)
        assertEquals(2, screen.collectCount)
        assertFalse(manager.isContinuousListening)
        assertTrue(tts.spoken.contains("已停止聆听。"))
    }

    @Test
    fun continuousListeningWaitsForResultSpeechBeforeNextListen() = runTest {
        val tts = GateSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("这里有什么"),
            SpeechInputResult.Recognized("停止聆听"),
        )
        val manager = manager(
            tts = tts,
            speech = speech,
            ai = FakeAssistantClient(response = AssistResponse(spoken = "当前页面有设置。")),
        )

        manager.startContinuousListening()
        advanceUntilIdle()

        assertEquals("连续聆听已开启。", tts.awaitingText)
        assertFalse(speech.listenStarted.isCompleted)
        assertTrue(speech.startedUtterances.isEmpty())

        tts.finishSpeaking() // 连续聆听已开启。
        advanceUntilIdle()

        assertTrue(speech.listenStarted.isCompleted)
        assertEquals(listOf("这里有什么"), speech.startedUtterances)

        tts.finishSpeaking() // 正在查看当前屏幕。
        advanceUntilIdle()
        tts.finishSpeaking() // 当前页面有设置。
        advanceUntilIdle()

        assertEquals(listOf("这里有什么", "停止聆听"), speech.startedUtterances)
    }

    @Test
    fun continuousStopCommandDoesNotCallAi() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(SpeechInputResult.Recognized("暂停助手"))
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val manager = manager(tts, speech, screen, ai)

        manager.startContinuousListening()
        advanceUntilIdle()

        assertTrue(ai.utterances.isEmpty())
        assertEquals(0, screen.collectCount)
        assertFalse(manager.isContinuousListening)
        assertTrue(tts.spoken.contains("已停止聆听。"))
    }

    @Test
    fun plainStopCommandDoesNotCallAiDuringContinuousListening() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(SpeechInputResult.Recognized("停止"))
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val manager = manager(tts, speech, screen, ai)

        manager.startContinuousListening()
        advanceUntilIdle()

        assertTrue(ai.utterances.isEmpty())
        assertEquals(0, screen.collectCount)
        assertFalse(manager.isContinuousListening)
        assertTrue(tts.spoken.contains("已停止聆听。"))
    }

    @Test
    fun continuousNoSpeechResultKeepsListeningWithoutRepeatedFailurePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Failed("我没有听清，请再说一次。"),
            SpeechInputResult.Recognized("停止聆听"),
        )
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val manager = manager(tts, speech, ai = ai)

        manager.startContinuousListening()
        advanceUntilIdle()

        assertFalse(tts.spoken.contains("我没有听清，请再说一次。"))
        assertTrue(ai.utterances.isEmpty())
        assertFalse(manager.isContinuousListening)
    }

    @Test
    fun overlayRequestStopsContinuousListeningWhenActive() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput()
        val manager = manager(tts, speech)

        manager.startContinuousListening()
        speech.listenStarted.await()

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(speech.cancelCalled)
        assertFalse(manager.isContinuousListening)
        assertTrue(tts.spoken.contains("已停止聆听。"))
    }

    @Test
    fun secondClickStopsSpeakingWhenNoRequestIsActive() = runTest {
        val tts = FakeSpeechOutput(isSpeakingValue = true)
        val speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么"))
        val manager = manager(tts, speech)

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.stopCalled)
        assertTrue(speech.listenStarted.isActive)
        assertTrue(tts.spoken.isEmpty())
    }

    @Test
    fun speechRecognitionFailureIsSpokenWithoutCollectingScreen() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(SpeechInputResult.Failed("我没有听清，请再说一次。"))
        val screen = FakeScreenContextProvider()
        val manager = manager(tts, speech, screen)

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("我没有听清，请再说一次。"))
        assertEquals(0, screen.collectCount)
    }

    @Test
    fun networkFailureUsesClearVoicePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = IOException("connection refused")),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("网络连接失败，请检查网络后重试。"))
    }

    @Test
    fun aiTimeoutUsesClearVoicePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = InterruptedIOException("timeout")),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("AI 请求超时，请稍后重试。"))
    }

    @Test
    fun invalidAiPayloadUsesClearVoicePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = SerializationException("bad json")),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("AI 返回内容无法解析，已停止执行。"))
    }

    @Test
    fun readOnlyQuestionDoesNotExecuteActions() = runTest {
        val tts = FakeSpeechOutput()
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(response = AssistResponse(spoken = "当前页面有 WLAN。")),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("当前页面有 WLAN。", tts.spoken.last())
        assertTrue(actions.executions.isEmpty())
    }

    @Test
    fun localOpenAppCommandExecutesWithoutCollectingScreenOrCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("帮我打开设置")),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "设置", packageName = "com.android.settings"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertEquals(
            listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.android.settings")),
            actions.executions.single().actions,
        )
        assertTrue(tts.spoken.contains("我会打开设置。"))
    }

    @Test
    fun ambiguousLocalOpenAppCommandAsksWithoutExecutingOrCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开微信")),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "微信", packageName = "com.tencent.mm"),
                InstalledApp(label = "企业微信", packageName = "com.tencent.wework"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertTrue(actions.executions.isEmpty())
        assertTrue(tts.spoken.any { it.contains("我找到了多个应用") })
    }

    @Test
    fun unmatchedLocalOpenAppCommandFallsBackToExistingAiFlow() = runTest {
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "我没有找到这个应用。"))
        val manager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开不存在的应用")),
            screen = screen,
            ai = ai,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "设置", packageName = "com.android.settings"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(1, screen.collectCount)
        assertEquals(listOf("打开不存在的应用"), ai.utterances)
    }

    @Test
    fun highRiskLocalOpenAppCommandWaitsForConfirmationBeforeExecuting() = runTest {
        val tts = FakeSpeechOutput()
        val actions = FakeActionRunner()
        val responseAction = AssistantAction(type = "OPEN_APP", appPackage = "com.example.pay")
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("打开支付应用"),
                SpeechInputResult.Recognized("确认执行"),
            ),
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "支付应用", packageName = "com.example.pay"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.contains("高风险操作") })
        assertTrue(actions.executions.isEmpty())

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(listOf(responseAction), actions.executions.single().actions)
        assertTrue(actions.executions.single().confirmed)
    }

    @Test
    fun clearClickActionExecutesWithoutConfirmation() = runTest {
        val actions = FakeActionRunner()
        val manager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(SpeechInputResult.Recognized("点击确定")),
            ai = FakeAssistantClient(
                response = AssistResponse(
                    spoken = "我会点击确定。",
                    actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_12")),
                ),
            ),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_12")), actions.executions.single().actions)
        assertFalse(actions.executions.single().confirmed)
    }

    @Test
    fun unknownAiActionIsRejectedBeforeActionRunner() = runTest {
        val tts = FakeSpeechOutput()
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("运行脚本")),
            ai = FakeAssistantClient(
                response = AssistResponse(
                    spoken = "我会执行脚本。",
                    actions = listOf(AssistantAction(type = "RUN_SCRIPT")),
                ),
            ),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.contains("AI 返回了不支持的动作，已拒绝执行") })
        assertTrue(actions.executions.isEmpty())
    }

    @Test
    fun riskyActionWaitsForConfirmationBeforeExecuting() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("发送这条消息"),
            SpeechInputResult.Recognized("确认执行"),
        )
        val actions = FakeActionRunner()
        val response = AssistResponse(
            spoken = "我会发送这条消息。",
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_send")),
        )
        val manager = manager(
            tts = tts,
            speech = speech,
            ai = FakeAssistantClient(response = response),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.contains("高风险操作") })
        assertTrue(actions.executions.isEmpty())

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(response.actions, actions.executions.single().actions)
        assertTrue(actions.executions.single().confirmed)
    }

    @Test
    fun cancelUtteranceClearsPendingRiskyActionWithoutCallingAiAgain() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("发送这条消息"),
            SpeechInputResult.Recognized("取消"),
        )
        val screen = FakeScreenContextProvider()
        val actions = FakeActionRunner()
        val response = AssistResponse(
            spoken = "我会发送这条消息。",
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_send")),
        )
        val manager = manager(
            tts = tts,
            speech = speech,
            screen = screen,
            ai = FakeAssistantClient(response = response),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("已取消高风险操作。"))
        assertEquals(1, screen.collectCount)
        assertTrue(actions.executions.isEmpty())
    }

    @Test
    fun stopCommandWhileConfirmationPendingStopsContinuousListening() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("发送这条消息"),
            SpeechInputResult.Recognized("停止"),
        )
        val screen = FakeScreenContextProvider()
        val actions = FakeActionRunner()
        val response = AssistResponse(
            spoken = "我会发送这条消息。",
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_send")),
        )
        val manager = manager(
            tts = tts,
            speech = speech,
            screen = screen,
            ai = FakeAssistantClient(response = response),
            actions = actions,
        )

        manager.startContinuousListening()
        advanceUntilIdle()

        assertFalse(manager.isContinuousListening)
        assertTrue(tts.spoken.contains("已取消高风险操作。"))
        assertTrue(tts.spoken.contains("已停止聆听。"))
        assertTrue(actions.executions.isEmpty())
    }

    @Test
    fun highRiskTargetNodeTextWaitsForConfirmationBeforeExecuting() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("点这个"),
            SpeechInputResult.Recognized("确认执行"),
        )
        val sourceScreen = screenContext(
            packageName = "com.example.pay",
            nodes = listOf(
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
            ),
        )
        val actions = FakeActionRunner()
        val response = AssistResponse(
            spoken = "我会点击这个按钮。",
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_pay")),
        )
        val manager = manager(
            tts = tts,
            speech = speech,
            screen = FakeScreenContextProvider(sourceScreen),
            ai = FakeAssistantClient(response = response),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.contains("高风险操作") })
        assertTrue(actions.executions.isEmpty())

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(response.actions, actions.executions.single().actions)
        assertTrue(actions.executions.single().confirmed)
        assertEquals(sourceScreen, actions.executions.single().sourceScreen)
    }

    @Test
    fun pageChangedFailureRefreshesScreenAndPromptsRetry() = runTest {
        val tts = FakeSpeechOutput()
        val initialScreen = screenContext(packageName = "com.android.settings")
        val refreshedScreen = screenContext(packageName = "com.example.changed")
        val screen = FakeScreenContextProvider(initialScreen, refreshedScreen)
        val actions = FakeActionRunner(
            results = listOf(
                ActionResult(
                    success = false,
                    message = "页面已变化，我已重新查看当前屏幕，请再说一次。",
                    requiresScreenRefresh = true,
                ),
            ),
        )
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("点击确定")),
            screen = screen,
            ai = FakeAssistantClient(
                response = AssistResponse(
                    spoken = "我会点击确定。",
                    actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_ok")),
                ),
            ),
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(2, screen.collectCount)
        assertTrue(tts.spoken.contains("页面已变化，我已重新查看当前屏幕，请再说一次。"))
    }

    private fun TestScope.manager(
        tts: SpeechOutput,
        speech: FakeSpeechInput,
        screen: FakeScreenContextProvider = FakeScreenContextProvider(),
        ai: FakeAssistantClient = FakeAssistantClient(response = AssistResponse(spoken = "好的。")),
        actions: FakeActionRunner = FakeActionRunner(),
        openAppCommandResolver: OpenAppCommandResolver? = null,
    ): AssistantSessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return AssistantSessionManager(
            scope = scope,
            speechInput = speech,
            speechOutput = tts,
            screenContextProvider = screen,
            assistantClient = ai,
            actionRunner = actions,
            openAppCommandResolver = openAppCommandResolver,
        )
    }
}

private class GateSpeechOutput : SpeechOutput {
    val spoken = mutableListOf<String>()
    var awaitingText: String? = null
    private var gate = CompletableDeferred<Unit>()

    override val isSpeaking: Boolean
        get() = awaitingText != null && !gate.isCompleted

    override fun speak(text: String) {
        spoken += text
    }

    override suspend fun speakAndAwait(text: String) {
        spoken += text
        awaitingText = text
        gate.await()
        awaitingText = null
        gate = CompletableDeferred()
    }

    override fun stop() {
        finishSpeaking()
    }

    fun finishSpeaking() {
        if (!gate.isCompleted) gate.complete(Unit)
    }
}

private class FakeSpeechInput(
    vararg results: SpeechInputResult,
) : SpeechInput {
    private val pendingResults = ArrayDeque(results.toList())
    val listenStarted = CompletableDeferred<Unit>()
    val startedUtterances = mutableListOf<String>()
    var cancelCalled = false

    override suspend fun listenOnce(): SpeechInputResult {
        listenStarted.complete(Unit)
        val result = pendingResults.removeFirstOrNull() ?: awaitCancellation()
        if (result is SpeechInputResult.Recognized) startedUtterances += result.text
        return result
    }

    override fun cancel() {
        cancelCalled = true
    }
}

private class FakeSpeechOutput(
    private var isSpeakingValue: Boolean = false,
) : SpeechOutput {
    val spoken = mutableListOf<String>()
    var stopCalled = false

    override val isSpeaking: Boolean
        get() = isSpeakingValue

    override fun speak(text: String) {
        spoken += text
    }

    override fun stop() {
        stopCalled = true
        isSpeakingValue = false
    }
}

private class FakeScreenContextProvider(
    vararg contexts: ScreenContext,
) : ScreenContextProvider {
    private val pendingContexts = ArrayDeque(contexts.toList())
    var collectCount = 0

    override suspend fun collect(): ScreenContext {
        collectCount += 1
        return pendingContexts.removeFirstOrNull() ?: screenContext()
    }
}

private class FakeAssistantClient(
    private val response: AssistResponse? = null,
    private val error: Throwable? = null,
) : AssistantClient {
    var lastUtterance: String? = null
    val utterances = mutableListOf<String>()

    override suspend fun assist(
        sessionId: String,
        locale: String,
        utterance: String,
        screenContext: ScreenContext,
    ): AssistResponse {
        lastUtterance = utterance
        utterances += utterance
        error?.let { throw it }
        return response ?: AssistResponse(spoken = "好的。")
    }
}

private class FakeActionRunner(
    private val results: List<ActionResult>? = null,
) : ActionRunner {
    val executions = mutableListOf<ActionExecution>()

    override fun execute(
        actions: List<AssistantAction>,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): List<ActionResult> {
        executions += ActionExecution(actions, confirmed, sourceScreen)
        return results ?: actions.map { ActionResult(true, "已执行。") }
    }
}

private data class ActionExecution(
    val actions: List<AssistantAction>,
    val confirmed: Boolean,
    val sourceScreen: ScreenContext,
)

private fun screenContext(
    packageName: String = "com.android.settings",
    nodes: List<ScreenNode> = emptyList(),
): ScreenContext =
    ScreenContext(
        packageName = packageName,
        activityName = null,
        nodes = nodes,
        screenshotBase64 = null,
    )

private fun openAppResolver(
    vararg apps: InstalledApp,
    defaultBrowserPackage: String? = null,
): OpenAppCommandResolver =
    OpenAppCommandResolver(FakeAppCatalogProvider(apps.toList(), defaultBrowserPackage))

private class FakeAppCatalogProvider(
    private val apps: List<InstalledApp>,
    private val defaultBrowserPackage: String?,
) : AppCatalogProvider {
    override fun installedApps(): List<InstalledApp> = apps

    override fun defaultBrowserPackage(): String? = defaultBrowserPackage
}
