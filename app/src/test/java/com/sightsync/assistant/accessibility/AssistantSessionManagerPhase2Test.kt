package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.ai.AiProxyEndpoint
import com.sightsync.assistant.ai.AiProxyErrorType
import com.sightsync.assistant.ai.AiProxyException
import com.sightsync.assistant.apps.AppCatalogProvider
import com.sightsync.assistant.apps.BrowserSearchCommandResolver
import com.sightsync.assistant.apps.InstalledApp
import com.sightsync.assistant.apps.OpenAppCommandResolver
import com.sightsync.assistant.apps.WeChatDraftCommandResolver
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun rapidStopStartWaitsForCancelledListenToFinishBeforeRestarting() = runTest {
        val speech = DelayedCancellationSpeechInput()
        val manager = manager(FakeSpeechOutput(), speech)

        manager.startContinuousListening()
        speech.firstListenStarted.await()

        manager.stopContinuousListening()
        manager.startContinuousListening()
        runCurrent()

        assertEquals(1, speech.listenCalls)
        assertEquals(1, speech.maxConcurrentListens)

        speech.allowFirstCleanup.complete(Unit)
        runCurrent()

        assertEquals(2, speech.listenCalls)
        assertEquals(1, speech.maxConcurrentListens)
        manager.stopContinuousListening()
        runCurrent()
    }

    @Test
    fun rapidRestartDoesNotSpeakObsoleteStoppedPrompt() = runTest {
        val tts = FakeSpeechOutput()
        val speech = DelayedCancellationSpeechInput()
        val manager = manager(tts, speech)

        manager.startContinuousListening()
        speech.firstListenStarted.await()
        manager.stopContinuousListening()
        manager.startContinuousListening()
        runCurrent()

        assertFalse(tts.spoken.contains("已停止聆听。"))

        speech.allowFirstCleanup.complete(Unit)
        runCurrent()
        manager.stopContinuousListening()
        runCurrent()
    }

    @Test
    fun disposeCancelsVoiceWithoutSpeakingStoppedPrompt() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput()
        val manager = manager(tts, speech)

        manager.startContinuousListening()
        speech.listenStarted.await()
        manager.dispose()
        runCurrent()

        assertTrue(speech.cancelCalled)
        assertTrue(tts.stopCalled)
        assertFalse(tts.spoken.contains("已停止聆听。"))
        assertFalse(manager.isContinuousListening)
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
            SpeechInputResult.Failed(
                message = "我没有听清，请再说一次。",
                kind = com.sightsync.assistant.speech.SpeechInputFailureKind.NoSpeech,
            ),
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
    fun continuousListeningIgnoresAmbientUtteranceWithoutCollectingScreenOrCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val logger = FakeDiagnosticLogger()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("炮塔被摧毁。"),
                SpeechInputResult.Recognized("停止聆听"),
            ),
            screen = screen,
            ai = ai,
            diagnosticLogger = logger,
        )

        manager.startContinuousListening()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertFalse(tts.spoken.contains("正在查看当前屏幕。"))
        assertTrue(logger.messages.any { it.contains("Continuous utterance ignored reason=not_explicit_command") })
        assertFalse(manager.isContinuousListening)
    }

    @Test
    fun continuousListeningCanonicalizesObservedReadScreenVariant() = runTest {
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "可操作项。"))
        val manager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("只说明可操作性。"),
                SpeechInputResult.Recognized("停止聆听"),
            ),
            ai = ai,
        )

        manager.startContinuousListening()
        advanceUntilIdle()

        assertEquals(listOf("只说明可操作项"), ai.utterances)
    }

    @Test
    fun oneShotUnknownUtteranceStillCallsAi() = runTest {
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "回答。"))
        val manager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这句话是什么意思")),
            ai = ai,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(listOf("这句话是什么意思"), ai.utterances)
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
    fun aiRemoteTimeoutUsesSpecificVoicePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = aiProxyError(AiProxyErrorType.RemoteTimeout, statusCode = 504)),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("AI 服务响应超时，请稍后重试。"))
    }

    @Test
    fun aiProviderUnavailableUsesSpecificVoicePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = aiProxyError(AiProxyErrorType.ProviderUnavailable, statusCode = 503)),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("AI 服务暂时不可用，请稍后重试。"))
    }

    @Test
    fun aiProviderUnavailableLogsFailureType() = runTest {
        val tts = FakeSpeechOutput()
        val logger = FakeDiagnosticLogger()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = aiProxyError(AiProxyErrorType.ProviderUnavailable, statusCode = 503)),
            diagnosticLogger = logger,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(logger.messages.contains("SightSyncSession: Assist failed type=ProviderUnavailable status=503"))
    }

    @Test
    fun continuousProviderUnavailablePausesAfterSecondConsecutiveServiceFailure() = runTest {
        val tts = FakeSpeechOutput()
        val speech = FakeSpeechInput(
            SpeechInputResult.Recognized("这里有什么"),
            SpeechInputResult.Recognized("再读一次"),
        )
        val ai = FakeAssistantClient(error = aiProxyError(AiProxyErrorType.ProviderUnavailable, statusCode = 503))
        val manager = manager(tts = tts, speech = speech, ai = ai)

        manager.startContinuousListening()
        advanceUntilIdle()

        assertEquals(listOf("这里有什么", "再读一次"), ai.utterances)
        assertEquals(1, tts.spoken.count { it == "AI 服务暂时不可用，请稍后重试。" })
        assertTrue(tts.spoken.contains("服务仍不可用，已暂停连续聆听，请检查连接后再开启。"))
        assertFalse(manager.isContinuousListening)
    }

    @Test
    fun continuousTranscriptionNetworkFailurePausesAfterSecondConsecutiveFailure() = runTest {
        val tts = FakeSpeechOutput()
        val failure = "语音转写网络不可用，请检查网络后重试。"
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Failed(
                    message = failure,
                    kind = com.sightsync.assistant.speech.SpeechInputFailureKind.Network,
                ),
                SpeechInputResult.Failed(
                    message = failure,
                    kind = com.sightsync.assistant.speech.SpeechInputFailureKind.Network,
                ),
            ),
        )

        manager.startContinuousListening()
        advanceUntilIdle()

        assertEquals(1, tts.spoken.count { it == failure })
        assertTrue(tts.spoken.contains("服务仍不可用，已暂停连续聆听，请检查连接后再开启。"))
        assertFalse(manager.isContinuousListening)
    }

    @Test
    fun ambientAffirmationDoesNotConfirmPendingHighRiskAction() = runTest {
        val actions = FakeActionRunner()
        val manager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("发送这条消息"),
                SpeechInputResult.Recognized("嗯"),
                SpeechInputResult.Recognized("停止聆听"),
            ),
            ai = FakeAssistantClient(
                response = AssistResponse(
                    spoken = "我会发送这条消息。",
                    actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_send")),
                ),
            ),
            actions = actions,
        )

        manager.startContinuousListening()
        advanceUntilIdle()

        assertTrue(actions.executions.isEmpty())
        assertFalse(manager.isContinuousListening)
    }

    @Test
    fun aiAuthorizationFailureUsesSpecificVoicePrompt() = runTest {
        val tts = FakeSpeechOutput()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("这里有什么")),
            ai = FakeAssistantClient(error = aiProxyError(AiProxyErrorType.Authorization, statusCode = 401)),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("AI 服务鉴权失败，请检查代理配置。"))
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
        val logger = FakeDiagnosticLogger()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("帮我打开设置")),
            screen = screen,
            ai = ai,
            actions = actions,
            diagnosticLogger = logger,
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
        assertTrue(logger.messages.contains("SightSyncSession: ASR utterance='帮我打开设置'"))
        assertTrue(logger.messages.contains("SightSyncSession: Resolving local open-app command. pendingCandidates=0"))
        assertTrue(logger.messages.any { it.startsWith("SightSyncSession: Local open-app resolved.") })
    }

    @Test
    fun explicitBrowserSearchRunsLocalTaskWithoutCollectingAiScreenOrCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val taskCalls = mutableListOf<Pair<String, String>>()
        val openResolver = openAppResolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("用浏览器搜索无障碍新闻")),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openResolver,
            browserSearchCommandResolver = BrowserSearchCommandResolver(openResolver),
            browserSearchTaskExecutor = BrowserSearchTaskExecutor { browserPackage, query ->
                taskCalls += browserPackage to query
                BrowserSearchTaskResult.Completed
            },
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertTrue(actions.executions.isEmpty())
        assertEquals(listOf("com.android.chrome" to "无障碍新闻"), taskCalls)
        assertTrue(tts.spoken.contains("我会用浏览器搜索无障碍新闻。"))
        assertTrue(tts.spoken.contains("搜索已提交。"))
    }

    @Test
    fun browserSearchFailureSpeaksReasonWithoutFallingBackToAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val openResolver = openAppResolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开浏览器搜索天气")),
            screen = screen,
            ai = ai,
            openAppCommandResolver = openResolver,
            browserSearchCommandResolver = BrowserSearchCommandResolver(openResolver),
            browserSearchTaskExecutor = BrowserSearchTaskExecutor { _, _ ->
                BrowserSearchTaskResult.Stopped("找不到唯一的搜索或地址栏，已停止搜索。")
            },
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertTrue(tts.spoken.contains("找不到唯一的搜索或地址栏，已停止搜索。"))
    }

    @Test
    fun uniqueInAppNavigationSpeaksBeforeExecutingWithoutCallingAi() = runTest {
        val events = mutableListOf<String>()
        val tts = EventSpeechOutput(events)
        val screen = NavigationSessionScreenProvider(
            screenContext(
                nodes = listOf(
                    ScreenNode(
                        nodeId = "node_wlan",
                        text = "WLAN",
                        contentDescription = null,
                        role = "View",
                        bounds = NodeBounds(0, 0, 100, 100),
                        clickable = true,
                        editable = false,
                        scrollable = false,
                    ),
                ),
            ),
        )
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = EventActionRunner(events)
        val coordinator = InAppNavigationCoordinator(screen)
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("点击 WLAN")),
            screen = screen,
            ai = ai,
            actions = actions,
            inAppNavigationResolver = coordinator,
            navigationPlanExecutor = AgentPlanExecutor(screen, actions),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertEquals(listOf("CLICK_NODE"), actions.executed.map { it.type })
        assertTrue(events.indexOf("speak:我会点击WLAN。") < events.indexOf("action:CLICK_NODE"))
        assertTrue(events.contains("speak:已完成页面导航。"))
    }

    @Test
    fun ambiguousInAppNavigationAsksThenUsesOneBareClarification() = runTest {
        val tts = FakeSpeechOutput()
        val screen = NavigationSessionScreenProvider(ambiguousNavigationScreen())
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val coordinator = InAppNavigationCoordinator(screen)
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("点击 WLAN"),
                SpeechInputResult.Recognized("WLAN 设置"),
            ),
            screen = screen,
            ai = ai,
            actions = actions,
            inAppNavigationResolver = coordinator,
            navigationPlanExecutor = AgentPlanExecutor(screen, actions),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()
        assertTrue(actions.executions.isEmpty())
        assertTrue(tts.spoken.any { it.contains("多个候选") })

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("node_settings", actions.executions.single().actions.single().nodeId)
        assertTrue(ai.utterances.isEmpty())
        assertFalse(coordinator.hasPendingClarification)
    }

    @Test
    fun cancellationClearsPendingInAppNavigationWithoutCollectingAgain() = runTest {
        val tts = FakeSpeechOutput()
        val screen = NavigationSessionScreenProvider(screenContext(nodes = emptyList()))
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val coordinator = InAppNavigationCoordinator(screen)
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("点击 WLAN"),
                SpeechInputResult.Recognized("取消"),
            ),
            screen = screen,
            ai = ai,
            inAppNavigationResolver = coordinator,
            navigationPlanExecutor = AgentPlanExecutor(screen, FakeActionRunner()),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()
        assertTrue(coordinator.hasPendingClarification)

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertFalse(coordinator.hasPendingClarification)
        assertEquals(1, screen.validationCount)
        assertTrue(tts.spoken.contains("已取消。"))
        assertTrue(ai.utterances.isEmpty())
    }

    @Test
    fun highRiskInAppNavigationWaitsForExistingConfirmationFlow() = runTest {
        val tts = FakeSpeechOutput()
        val deleteScreen = screenContext(
            nodes = listOf(
                ScreenNode(
                    nodeId = "node_delete",
                    text = "删除账号",
                    contentDescription = null,
                    role = "Button",
                    bounds = NodeBounds(0, 0, 100, 100),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                ),
            ),
        )
        val screen = NavigationSessionScreenProvider(deleteScreen)
        val actions = FakeActionRunner()
        val coordinator = InAppNavigationCoordinator(screen)
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("点击删除账号"),
                SpeechInputResult.Recognized("确认执行"),
            ),
            screen = screen,
            actions = actions,
            inAppNavigationResolver = coordinator,
            navigationPlanExecutor = AgentPlanExecutor(screen, actions),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()
        assertTrue(actions.executions.isEmpty())
        assertTrue(tts.spoken.any { it.contains("高风险操作") })

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("node_delete", actions.executions.single().actions.single().nodeId)
        assertTrue(actions.executions.single().confirmed)
    }

    @Test
    fun inAppNavigationDoesNotTakeOrdinaryOpenAppCommand() = runTest {
        val screen = NavigationSessionScreenProvider(screenContext())
        val actions = FakeActionRunner()
        val coordinator = InAppNavigationCoordinator(screen)
        val manager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开微信")),
            screen = screen,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "微信", packageName = "com.tencent.mm"),
            ),
            inAppNavigationResolver = coordinator,
            navigationPlanExecutor = AgentPlanExecutor(screen, actions),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("OPEN_APP", actions.executions.single().actions.single().type)
        assertEquals(0, screen.validationCount)
    }

    @Test
    fun stopAndDisposeClearInAppNavigationState() = runTest {
        val stopResolver = RecordingInAppNavigationResolver()
        val stopManager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(),
            inAppNavigationResolver = stopResolver,
        )
        stopManager.startContinuousListening()
        runCurrent()
        stopManager.stopContinuousListening()
        advanceUntilIdle()

        val disposeResolver = RecordingInAppNavigationResolver()
        val disposeManager = manager(
            tts = FakeSpeechOutput(),
            speech = FakeSpeechInput(),
            inAppNavigationResolver = disposeResolver,
        )
        disposeManager.dispose()

        assertTrue(stopResolver.clearCount >= 1)
        assertEquals(1, disposeResolver.clearCount)
    }

    @Test
    fun ambiguousLocalOpenAppCommandAsksWithoutExecutingOrCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开邮箱")),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "QQ邮箱内测版", packageName = "com.tencent.qqmail.beta"),
                InstalledApp(label = "网易邮箱", packageName = "com.netease.mail"),
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
    fun unmatchedExplicitLocalOpenAppCommandSpeaksFailureWithoutCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "我没有找到这个应用。"))
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开不存在的应用")),
            screen = screen,
            ai = ai,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "设置", packageName = "com.android.settings"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertTrue(tts.spoken.contains("没有找到不存在的应用。"))
    }

    @Test
    fun missingBrandedBrowserAsksForLocalAlternativesWithoutCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("打开谷歌浏览器")),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "浏览器", packageName = "com.vivo.browser"),
                InstalledApp(label = "夸克", packageName = "com.quark.browser"),
                defaultBrowserPackage = "com.vivo.browser",
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertTrue(actions.executions.isEmpty())
        assertTrue(tts.spoken.any { it.contains("没有找到谷歌浏览器") })
        assertTrue(tts.spoken.any { it.contains("要打开") })
    }

    @Test
    fun missingBrandedBrowserAcceptsDefaultBrowserOnNextTurn() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("打开谷歌浏览器"),
                SpeechInputResult.Recognized("默认浏览器"),
            ),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "浏览器", packageName = "com.vivo.browser"),
                InstalledApp(label = "夸克", packageName = "com.quark.browser"),
                defaultBrowserPackage = "com.vivo.browser",
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertEquals(
            listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.vivo.browser")),
            actions.executions.single().actions,
        )
    }

    @Test
    fun ambiguousOpenAppCommandAcceptsBareTargetOnNextTurn() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("打开浏览器"),
                SpeechInputResult.Recognized("谷歌浏览器"),
            ),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
                InstalledApp(label = "Edge", packageName = "com.microsoft.emmx"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.contains("我找到了多个应用") })
        assertTrue(actions.executions.isEmpty())

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertEquals(
            listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.android.chrome")),
            actions.executions.single().actions,
        )
    }

    @Test
    fun ambiguousOpenAppCommandLimitsNextBareTargetToPreviousCandidates() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("打开邮箱"),
                SpeechInputResult.Recognized("QQ邮箱内测版"),
            ),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "QQ", packageName = "com.tencent.mobileqq"),
                InstalledApp(label = "QQ邮箱内测版", packageName = "com.tencent.qqmail.beta"),
                InstalledApp(label = "网易邮箱", packageName = "com.netease.mail"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.contains("我找到了多个应用") })
        assertTrue(actions.executions.isEmpty())

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertEquals(
            listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.tencent.qqmail.beta")),
            actions.executions.single().actions,
        )
    }

    @Test
    fun cancelClearsPendingOpenAppClarificationWithoutCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient(response = AssistResponse(spoken = "不应调用。"))
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("打开浏览器"),
                SpeechInputResult.Recognized("取消"),
            ),
            screen = screen,
            ai = ai,
            actions = actions,
            openAppCommandResolver = openAppResolver(
                InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
                InstalledApp(label = "Edge", packageName = "com.microsoft.emmx"),
            ),
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
        assertTrue(actions.executions.isEmpty())
        assertTrue(tts.spoken.contains("已取消。"))
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
            packageName = "com.example.form",
            nodes = listOf(
                ScreenNode(
                    nodeId = "node_submit",
                    text = "提交",
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
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_submit")),
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
    fun paymentPasswordPageRejectsActionInsteadOfRequestingConfirmation() = runTest {
        val tts = FakeSpeechOutput()
        val sourceScreen = screenContext(
            packageName = "com.example.wallet",
            nodes = listOf(
                ScreenNode(
                    nodeId = "title",
                    text = "请输入支付密码",
                    contentDescription = null,
                    role = "Text",
                    bounds = NodeBounds(0, 0, 400, 80),
                    clickable = false,
                    editable = false,
                    scrollable = false,
                ),
                ScreenNode(
                    nodeId = "node_ok",
                    text = "确定",
                    contentDescription = null,
                    role = "Button",
                    bounds = NodeBounds(0, 100, 200, 180),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                ),
            ),
        )
        val actions = FakeActionRunner()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("点击确定")),
            screen = FakeScreenContextProvider(sourceScreen),
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

        assertTrue(tts.spoken.contains("当前页面包含支付、密码或验证码等高风险内容，暂不执行操作。"))
        assertFalse(tts.spoken.any { it.contains("确认执行") })
        assertTrue(actions.executions.isEmpty())
    }

    @Test
    fun highRiskContextRejectionDoesNotLeavePendingConfirmation() = runTest {
        val tts = FakeSpeechOutput()
        val sourceScreen = screenContext(
            packageName = "com.example.bank",
            nodes = listOf(
                ScreenNode(
                    nodeId = "code_input",
                    text = null,
                    contentDescription = "短信验证码",
                    role = "EditText",
                    bounds = NodeBounds(0, 0, 400, 80),
                    clickable = true,
                    editable = true,
                    scrollable = false,
                ),
            ),
        )
        val actions = FakeActionRunner()
        val ai = FakeAssistantClient(
            response = AssistResponse(
                spoken = "我会输入验证码。",
                actions = listOf(AssistantAction(type = "SET_TEXT", nodeId = "code_input", text = "123456")),
            ),
        )
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("输入验证码"),
                SpeechInputResult.Recognized("确认执行"),
            ),
            screen = FakeScreenContextProvider(sourceScreen, sourceScreen),
            ai = ai,
            actions = actions,
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(listOf("输入验证码", "确认执行"), ai.utterances)
        assertEquals(2, tts.spoken.count { it == "当前页面包含支付、密码或验证码等高风险内容，暂不执行操作。" })
        assertTrue(actions.executions.isEmpty())
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

    @Test
    fun explicitWeChatDraftRunsLocallyAndStopsBeforeSend() = runTest {
        val events = mutableListOf<String>()
        val tts = EventSpeechOutput(events)
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient()
        var executedContact: String? = null
        var executedMessage: String? = null
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(
                SpeechInputResult.Recognized("在微信给张三写草稿：明天上午十点见"),
            ),
            screen = screen,
            ai = ai,
            weChatDraftCommandResolver = WeChatDraftCommandResolver(),
            weChatDraftTaskExecutor = WeChatDraftTaskExecutor { contact, message ->
                events += "execute:$contact:$message"
                executedContact = contact
                executedMessage = message
                WeChatDraftTaskResult.DraftReady
            },
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals("张三", executedContact)
        assertEquals("明天上午十点见", executedMessage)
        assertTrue(events.indexOfFirst { it.startsWith("speak:我会在微信") } < events.indexOfFirst { it.startsWith("execute:") })
        assertTrue(events.any { it.contains("手动发送") })
        assertTrue(events.none { it.contains("确认执行") })
        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
    }

    @Test
    fun malformedWeChatDraftIsRejectedLocally() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient()
        var executionCount = 0
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("在微信给张三写草稿")),
            screen = screen,
            ai = ai,
            weChatDraftCommandResolver = WeChatDraftCommandResolver(),
            weChatDraftTaskExecutor = WeChatDraftTaskExecutor { _, _ ->
                executionCount += 1
                WeChatDraftTaskResult.DraftReady
            },
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertEquals(0, executionCount)
        assertTrue(tts.spoken.any { it.contains("命令不完整") })
        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
    }

    @Test
    fun stoppedWeChatDraftUsesLocalReasonWithoutCallingAi() = runTest {
        val tts = FakeSpeechOutput()
        val screen = FakeScreenContextProvider()
        val ai = FakeAssistantClient()
        val manager = manager(
            tts = tts,
            speech = FakeSpeechInput(SpeechInputResult.Recognized("在微信给张三写草稿：你好")),
            screen = screen,
            ai = ai,
            weChatDraftCommandResolver = WeChatDraftCommandResolver(),
            weChatDraftTaskExecutor = WeChatDraftTaskExecutor { _, _ ->
                WeChatDraftTaskResult.Stopped("找到多个同名微信联系人，已停止填写草稿。")
            },
        )

        manager.onAssistantRequested()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("找到多个同名微信联系人，已停止填写草稿。"))
        assertEquals(0, screen.collectCount)
        assertTrue(ai.utterances.isEmpty())
    }

    private fun TestScope.manager(
        tts: SpeechOutput,
        speech: SpeechInput,
        screen: ScreenContextProvider = FakeScreenContextProvider(),
        ai: FakeAssistantClient = FakeAssistantClient(response = AssistResponse(spoken = "好的。")),
        actions: ActionRunner = FakeActionRunner(),
        openAppCommandResolver: OpenAppCommandResolver? = null,
        browserSearchCommandResolver: BrowserSearchCommandResolver? = null,
        browserSearchTaskExecutor: BrowserSearchTaskExecutor? = null,
        inAppNavigationResolver: InAppNavigationResolver? = null,
        navigationPlanExecutor: AgentPlanExecutor? = null,
        weChatDraftCommandResolver: WeChatDraftCommandResolver? = null,
        weChatDraftTaskExecutor: WeChatDraftTaskExecutor? = null,
        diagnosticLogger: com.sightsync.assistant.diagnostics.DiagnosticLogger = com.sightsync.assistant.diagnostics.NoOpDiagnosticLogger,
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
            browserSearchCommandResolver = browserSearchCommandResolver,
            browserSearchTaskExecutor = browserSearchTaskExecutor,
            inAppNavigationResolver = inAppNavigationResolver,
            navigationPlanExecutor = navigationPlanExecutor,
            weChatDraftCommandResolver = weChatDraftCommandResolver,
            weChatDraftTaskExecutor = weChatDraftTaskExecutor,
            diagnosticLogger = diagnosticLogger,
        )
    }
}

private class DelayedCancellationSpeechInput : SpeechInput {
    val firstListenStarted = CompletableDeferred<Unit>()
    val allowFirstCleanup = CompletableDeferred<Unit>()
    var listenCalls = 0
    var maxConcurrentListens = 0
    private var activeListens = 0

    override suspend fun listenOnce(): SpeechInputResult {
        listenCalls += 1
        val call = listenCalls
        activeListens += 1
        maxConcurrentListens = maxOf(maxConcurrentListens, activeListens)
        if (call == 1) firstListenStarted.complete(Unit)
        return try {
            awaitCancellation()
        } finally {
            if (call == 1) {
                withContext(NonCancellable) {
                    allowFirstCleanup.await()
                }
            }
            activeListens -= 1
        }
    }

    override fun cancel() = Unit
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

private class EventSpeechOutput(
    private val events: MutableList<String>,
) : SpeechOutput {
    override val isSpeaking: Boolean = false

    override fun speak(text: String) {
        events += "speak:$text"
    }

    override fun stop() = Unit
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

    override suspend fun collectForValidation(): ScreenContext = collect()
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

private class EventActionRunner(
    private val events: MutableList<String>,
) : ActionRunner {
    val executed = mutableListOf<AssistantAction>()

    override fun execute(
        actions: List<AssistantAction>,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): List<ActionResult> = actions.map { action ->
        events += "action:${action.type}"
        executed += action
        ActionResult(true, "已执行。")
    }
}

private class NavigationSessionScreenProvider(
    private var current: ScreenContext,
) : ScreenContextProvider {
    var collectCount = 0
    var validationCount = 0

    override suspend fun collect(): ScreenContext {
        collectCount += 1
        return current
    }

    override suspend fun collectForValidation(): ScreenContext {
        validationCount += 1
        return current
    }
}

private class RecordingInAppNavigationResolver : InAppNavigationResolver {
    var clearCount = 0

    override val hasPendingClarification: Boolean = false

    override suspend fun resolve(utterance: String): InAppNavigationResolution =
        InAppNavigationResolution.NotCommand

    override fun clear() {
        clearCount += 1
    }
}

private class FakeDiagnosticLogger : com.sightsync.assistant.diagnostics.DiagnosticLogger {
    val messages = mutableListOf<String>()

    override fun log(tag: String, message: String) {
        messages += "$tag: $message"
    }
}

private fun aiProxyError(
    type: AiProxyErrorType,
    statusCode: Int? = null,
): AiProxyException =
    AiProxyException(
        endpoint = AiProxyEndpoint.Assist,
        type = type,
        statusCode = statusCode,
        message = "typed test error",
    )

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

private fun ambiguousNavigationScreen(): ScreenContext =
    screenContext(
        nodes = listOf(
            ScreenNode(
                nodeId = "node_settings",
                text = "WLAN 设置",
                contentDescription = null,
                role = "View",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
            ScreenNode(
                nodeId = "node_help",
                text = "WLAN 帮助",
                contentDescription = null,
                role = "View",
                bounds = NodeBounds(0, 100, 100, 200),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        ),
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
