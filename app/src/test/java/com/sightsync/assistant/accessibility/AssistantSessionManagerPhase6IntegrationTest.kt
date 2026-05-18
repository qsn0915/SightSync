package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.core.ActionResult
import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import com.sightsync.assistant.speech.SpeechInput
import com.sightsync.assistant.speech.SpeechInputResult
import com.sightsync.assistant.speech.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSessionManagerPhase6IntegrationTest {
    @Test
    fun permissionClosedDuringScreenCollectionUsesClearVoicePrompt() = runTest {
        val speechOutput = Phase6SpeechOutput()
        val actionRunner = Phase6ActionRunner()
        val manager = manager(
            speechOutput = speechOutput,
            speechInput = Phase6SpeechInput(SpeechInputResult.Recognized("这里有什么")),
            screenProvider = Phase6FailingScreenProvider(SecurityException("permission revoked")),
            assistantClient = Phase6AssistantClient(AssistResponse(spoken = "不会被调用。")),
            actionRunner = actionRunner,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(speechOutput.spoken.contains("无障碍权限已关闭，请重新开启后再试。"))
        assertTrue(actionRunner.executions.isEmpty())
    }

    @Test
    fun voiceCommandCollectsScreenCallsMockAiAndExecutesReturnedAction() = runTest {
        val sourceScreen = ScreenContext(
            packageName = "com.android.settings",
            activityName = "Settings",
            nodes = listOf(
                ScreenNode(
                    nodeId = "node_1",
                    text = "WLAN",
                    contentDescription = null,
                    role = "Button",
                    bounds = NodeBounds(0, 100, 300, 180),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                ),
            ),
            screenshotBase64 = null,
        )
        val aiResponse = AssistResponse(
            spoken = "我会点击 WLAN。",
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_1")),
        )
        val speechOutput = Phase6SpeechOutput()
        val assistantClient = Phase6AssistantClient(aiResponse)
        val actionRunner = Phase6ActionRunner()
        val manager = manager(
            speechOutput = speechOutput,
            speechInput = Phase6SpeechInput(SpeechInputResult.Recognized("点击 WLAN")),
            screenProvider = Phase6ScreenProvider(sourceScreen),
            assistantClient = assistantClient,
            actionRunner = actionRunner,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("点击 WLAN", assistantClient.lastUtterance)
        assertEquals(sourceScreen, assistantClient.lastScreenContext)
        assertEquals(aiResponse.actions, actionRunner.executions.single().actions)
        assertEquals(sourceScreen, actionRunner.executions.single().sourceScreen)
        assertEquals(
            listOf("请说。", "正在查看当前屏幕。", "我会点击 WLAN。"),
            speechOutput.spoken,
        )
    }

    private fun TestScope.manager(
        speechOutput: Phase6SpeechOutput,
        speechInput: Phase6SpeechInput,
        screenProvider: ScreenContextProvider,
        assistantClient: AssistantClient,
        actionRunner: ActionRunner,
    ): AssistantSessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return AssistantSessionManager(
            scope = scope,
            speechInput = speechInput,
            speechOutput = speechOutput,
            screenContextProvider = screenProvider,
            assistantClient = assistantClient,
            actionRunner = actionRunner,
        )
    }
}

private class Phase6SpeechInput(
    private val result: SpeechInputResult,
) : SpeechInput {
    override suspend fun listenOnce(): SpeechInputResult = result

    override fun cancel() = Unit
}

private class Phase6SpeechOutput : SpeechOutput {
    val spoken = mutableListOf<String>()

    override val isSpeaking: Boolean = false

    override fun speak(text: String) {
        spoken += text
    }

    override fun stop() = Unit
}

private class Phase6ScreenProvider(
    private val screenContext: ScreenContext,
) : ScreenContextProvider {
    override suspend fun collect(): ScreenContext = screenContext
}

private class Phase6FailingScreenProvider(
    private val error: Throwable,
) : ScreenContextProvider {
    override suspend fun collect(): ScreenContext {
        throw error
    }
}

private class Phase6AssistantClient(
    private val response: AssistResponse,
) : AssistantClient {
    var lastUtterance: String? = null
    var lastScreenContext: ScreenContext? = null

    override suspend fun assist(
        sessionId: String,
        locale: String,
        utterance: String,
        screenContext: ScreenContext,
    ): AssistResponse {
        lastUtterance = utterance
        lastScreenContext = screenContext
        return response
    }
}

private class Phase6ActionRunner : ActionRunner {
    val executions = mutableListOf<Phase6ActionExecution>()

    override fun execute(
        actions: List<AssistantAction>,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): List<ActionResult> {
        executions += Phase6ActionExecution(actions, sourceScreen)
        return actions.map { ActionResult(true, "已执行。") }
    }
}

private data class Phase6ActionExecution(
    val actions: List<AssistantAction>,
    val sourceScreen: ScreenContext,
)
