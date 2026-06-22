package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AiProtocolValidator
import com.sightsync.assistant.ai.AiProxyErrorType
import com.sightsync.assistant.ai.AiProxyException
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.apps.OpenAppCommandResolver
import com.sightsync.assistant.apps.OpenAppCommandResult
import com.sightsync.assistant.core.RiskClassifier
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.diagnostics.AndroidDiagnosticLogger
import com.sightsync.assistant.diagnostics.DiagnosticLogger
import com.sightsync.assistant.speech.SpeechInput
import com.sightsync.assistant.speech.SpeechInputFailureKind
import com.sightsync.assistant.speech.SpeechInputResult
import com.sightsync.assistant.speech.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

class AssistantSessionManager(
    private val scope: CoroutineScope,
    private val speechInput: SpeechInput,
    private val speechOutput: SpeechOutput,
    private val screenContextProvider: ScreenContextProvider,
    private val assistantClient: AssistantClient,
    private val actionRunner: ActionRunner,
    private val openAppCommandResolver: OpenAppCommandResolver? = null,
    private val onContinuousListeningChanged: (Boolean) -> Unit = {},
    private val diagnosticLogger: DiagnosticLogger = AndroidDiagnosticLogger,
) {
    private var activeJob: Job? = null
    private var continuousJob: Job? = null
    private var continuousListeningRequested = false
    private var continuousGeneration = 0
    private var stopAnnouncementVersion = 0
    private var disposed = false
    private val confirmationManager = ConfirmationManager()
    private val sessionId = UUID.randomUUID().toString()
    private var voiceState: VoiceInteractionState = VoiceInteractionState.Idle
    private var pendingOpenAppCandidatePackages: Set<String> = emptySet()
    private val continuousUtteranceGate = ContinuousUtteranceGate()
    private val voiceTurnCoordinator = VoiceTurnCoordinator(
        speechInput = speechInput,
        speechOutput = speechOutput,
        onStateChanged = { state -> voiceState = state },
    )

    val isContinuousListening: Boolean
        get() = continuousListeningRequested

    fun onAssistantRequested() {
        if (isContinuousListening) {
            stopContinuousListening()
            return
        }

        val running = activeJob
        if (running?.isActive == true) {
            cancelActiveRequest()
            return
        }

        if (speechOutput.isSpeaking) {
            voiceTurnCoordinator.cancelVoice()
            return
        }

        activeJob = scope.launch {
            runAssistantTurn(
                promptBeforeListening = true,
                stopCommandEndsContinuousListening = false,
            )
        }
    }

    fun startContinuousListening() {
        if (disposed || continuousListeningRequested) return
        continuousListeningRequested = true
        stopAnnouncementVersion += 1

        val running = activeJob
        if (running?.isActive == true) {
            cancelActiveRequest()
        }
        if (speechOutput.isSpeaking) {
            voiceTurnCoordinator.cancelVoice()
        }

        onContinuousListeningChanged(true)
        ensureContinuousLoop()
    }

    fun stopContinuousListening() {
        if (!continuousListeningRequested && continuousJob == null) return
        continuousListeningRequested = false
        val announcementVersion = ++stopAnnouncementVersion
        val running = continuousJob
        running?.cancel()
        onContinuousListeningChanged(false)
        confirmationManager.clear()
        pendingOpenAppCandidatePackages = emptySet()
        voiceTurnCoordinator.cancelVoice()
        announceStoppedAfterCleanup(running, announcementVersion)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        continuousListeningRequested = false
        stopAnnouncementVersion += 1
        activeJob?.cancel()
        activeJob = null
        continuousJob?.cancel()
        confirmationManager.clear()
        pendingOpenAppCandidatePackages = emptySet()
        voiceTurnCoordinator.cancelVoice()
        onContinuousListeningChanged(false)
    }

    private fun ensureContinuousLoop() {
        if (disposed || !continuousListeningRequested || continuousJob != null) return
        val generation = ++continuousGeneration
        val job = scope.launch {
            runContinuousLoop(generation)
        }
        continuousJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (continuousJob !== job) return@launch
                continuousJob = null
                debugLog("Continuous listening generation=$generation completed")
                if (!disposed && continuousListeningRequested) {
                    ensureContinuousLoop()
                }
            }
        }
    }

    private suspend fun runContinuousLoop(generation: Int) {
        debugLog("Continuous listening generation=$generation started")
        voiceTurnCoordinator.speakResult("连续聆听已开启。")
        var consecutiveServiceFailures = 0
        var turn = 0
        while (true) {
            turn += 1
            debugLog("Continuous listening generation=$generation turn=$turn listening")
            val result = runAssistantTurn(
                promptBeforeListening = false,
                stopCommandEndsContinuousListening = true,
                suppressServiceFailurePrompt = consecutiveServiceFailures > 0,
            )
            when (result) {
                TurnResult.Completed -> consecutiveServiceFailures = 0
                TurnResult.Ignored -> Unit
                TurnResult.ServiceFailure -> {
                    consecutiveServiceFailures += 1
                    if (consecutiveServiceFailures >= MAX_CONSECUTIVE_SERVICE_FAILURES) {
                        continuousListeningRequested = false
                        onContinuousListeningChanged(false)
                        voiceTurnCoordinator.speakResult("服务仍不可用，已暂停连续聆听，请检查连接后再开启。")
                        break
                    }
                }
                TurnResult.StopRequested -> {
                    continuousListeningRequested = false
                    onContinuousListeningChanged(false)
                    voiceTurnCoordinator.speakResult("已停止聆听。")
                    break
                }
            }
        }
    }

    private fun announceStoppedAfterCleanup(running: Job?, announcementVersion: Int) {
        val announce = {
            scope.launch {
                if (
                    !disposed &&
                    !continuousListeningRequested &&
                    stopAnnouncementVersion == announcementVersion
                ) {
                    voiceTurnCoordinator.speakResult("已停止聆听。")
                }
            }
        }
        if (running == null || running.isCompleted) {
            announce()
        } else {
            running.invokeOnCompletion { announce() }
        }
    }

    private fun cancelActiveRequest() {
        activeJob?.cancel()
        activeJob = null
        confirmationManager.clear()
        pendingOpenAppCandidatePackages = emptySet()
        voiceTurnCoordinator.cancelVoice()
        scope.launch {
            voiceTurnCoordinator.speakResult("已取消。")
        }
    }

    private suspend fun runAssistantTurn(
        promptBeforeListening: Boolean,
        stopCommandEndsContinuousListening: Boolean,
        suppressServiceFailurePrompt: Boolean = false,
    ): TurnResult {
        return try {
            val speechResult = voiceTurnCoordinator.listenForTurn(
                prompt = if (promptBeforeListening) "请说。" else null,
            )
            val recognizedUtterance = when (speechResult) {
                is SpeechInputResult.Recognized -> speechResult.text.trim()
                is SpeechInputResult.Failed -> {
                    val serviceFailure = speechResult.kind.isServiceFailure()
                    debugLog(
                        "Speech input failed kind=${speechResult.kind} " +
                            "status=${speechResult.statusCode ?: "none"}",
                    )
                    if (
                        (!stopCommandEndsContinuousListening || speechResult.kind != SpeechInputFailureKind.NoSpeech) &&
                        !(serviceFailure && suppressServiceFailurePrompt)
                    ) {
                        voiceTurnCoordinator.speakResult(speechResult.message)
                    }
                    return if (serviceFailure) TurnResult.ServiceFailure else TurnResult.Completed
                }
                SpeechInputResult.Cancelled -> return TurnResult.Completed
            }
            if (recognizedUtterance.isBlank()) {
                if (!stopCommandEndsContinuousListening) {
                    voiceTurnCoordinator.speakResult("我没有听清，请再说一次。")
                }
                return TurnResult.Completed
            }
            debugLog("ASR utterance='$recognizedUtterance'")
            var utterance = continuousUtteranceGate.normalize(recognizedUtterance)
            if (utterance != recognizedUtterance) {
                debugLog("ASR normalized='$utterance'")
            }
            val confirmedRequest = confirmationManager.consumeIfConfirmed(utterance)
            if (confirmedRequest != null) {
                pendingOpenAppCandidatePackages = emptySet()
                executeResponse(
                    response = confirmedRequest.response,
                    confirmed = true,
                    sourceScreen = confirmedRequest.sourceScreen,
                )
                return TurnResult.Completed
            }
            if (confirmationManager.hasPending) {
                confirmationManager.clear()
                if (confirmationManager.isCancellation(utterance)) {
                    if (stopCommandEndsContinuousListening) {
                        voiceTurnCoordinator.speakResult("已取消高风险操作。")
                        return TurnResult.StopRequested
                    }
                    voiceTurnCoordinator.speakResult("已取消高风险操作。")
                    return TurnResult.Completed
                }
            }
            if (stopCommandEndsContinuousListening && isContinuousStopCommand(utterance)) {
                pendingOpenAppCandidatePackages = emptySet()
                return TurnResult.StopRequested
            }
            if (pendingOpenAppCandidatePackages.isNotEmpty() && confirmationManager.isCancellation(utterance)) {
                pendingOpenAppCandidatePackages = emptySet()
                voiceTurnCoordinator.speakResult("已取消。")
                return TurnResult.Completed
            }

            if (handleLocalOpenAppCommand(utterance)) {
                return TurnResult.Completed
            }

            if (stopCommandEndsContinuousListening) {
                when (val decision = continuousUtteranceGate.decide(utterance)) {
                    is ContinuousUtteranceDecision.Accepted -> {
                        utterance = decision.canonicalUtterance
                        debugLog(
                            "Continuous utterance accepted category=${decision.category} " +
                                "canonical='$utterance'",
                        )
                    }
                    is ContinuousUtteranceDecision.Ignored -> {
                        debugLog("Continuous utterance ignored reason=${decision.reason}")
                        return TurnResult.Ignored
                    }
                }
            }

            voiceTurnCoordinator.speakResult("正在查看当前屏幕。")
            voiceState = VoiceInteractionState.Thinking
            val screenContext = screenContextProvider.collect()
            val response = assistantClient.assist(
                sessionId = sessionId,
                locale = "zh-CN",
                utterance = utterance,
                screenContext = screenContext,
            )

            processPlannedResponse(utterance, response, screenContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (proxy: AiProxyException) {
            debugLog("Assist failed type=${proxy.type} status=${proxy.statusCode ?: "none"}")
            if (!suppressServiceFailurePrompt) {
                voiceTurnCoordinator.speakResult(proxy.toAssistVoicePrompt())
            }
            TurnResult.ServiceFailure
        } catch (timeout: InterruptedIOException) {
            debugLog("Assist failed type=ClientTimeout status=none")
            if (!suppressServiceFailurePrompt) {
                voiceTurnCoordinator.speakResult("AI 请求超时，请稍后重试。")
            }
            TurnResult.ServiceFailure
        } catch (network: IOException) {
            debugLog("Assist failed type=Network status=none")
            if (!suppressServiceFailurePrompt) {
                voiceTurnCoordinator.speakResult("网络连接失败，请检查网络后重试。")
            }
            TurnResult.ServiceFailure
        } catch (security: SecurityException) {
            voiceTurnCoordinator.speakResult("无障碍权限已关闭，请重新开启后再试。")
            TurnResult.Completed
        } catch (serialization: SerializationException) {
            voiceTurnCoordinator.speakResult("AI 返回内容无法解析，已停止执行。")
            TurnResult.Completed
        } catch (error: Throwable) {
            voiceTurnCoordinator.speakResult("操作失败，请重试。${error.message ?: "未知错误"}")
            TurnResult.Completed
        }
    }

    private fun isContinuousStopCommand(utterance: String): Boolean {
        val normalized = utterance.trim()
        return normalized in setOf("停止", "停止聆听", "停止监听", "停止助手", "暂停助手", "取消", "退出")
    }

    private fun SpeechInputFailureKind.isServiceFailure(): Boolean =
        this in setOf(
            SpeechInputFailureKind.Configuration,
            SpeechInputFailureKind.Authorization,
            SpeechInputFailureKind.RateLimited,
            SpeechInputFailureKind.ProviderUnavailable,
            SpeechInputFailureKind.Timeout,
            SpeechInputFailureKind.Network,
            SpeechInputFailureKind.ResponseInvalid,
        )

    private suspend fun handleLocalOpenAppCommand(utterance: String): Boolean {
        val resolver = openAppCommandResolver ?: return false
        val pendingCandidates = pendingOpenAppCandidatePackages
        debugLog("Resolving local open-app command. pendingCandidates=${pendingCandidates.size}")
        return when (val result = resolver.resolve(
            utterance = utterance,
            allowBareTarget = pendingCandidates.isNotEmpty(),
            candidatePackages = pendingCandidates.takeIf { it.isNotEmpty() },
        )) {
            is OpenAppCommandResult.Resolved -> {
                pendingOpenAppCandidatePackages = emptySet()
                debugLog("Local open-app resolved. actions=${result.response.actions}")
                processPlannedResponse(utterance, result.response, localActionScreenContext())
                true
            }

            is OpenAppCommandResult.Ambiguous -> {
                pendingOpenAppCandidatePackages = result.candidatePackages
                debugLog("Local open-app ambiguous. candidates=${result.candidatePackages}")
                voiceTurnCoordinator.speakResult(result.response.spoken)
                true
            }

            is OpenAppCommandResult.Alternatives -> {
                pendingOpenAppCandidatePackages = result.candidatePackages
                debugLog("Local open-app alternatives. target=${result.target} candidates=${result.candidatePackages}")
                voiceTurnCoordinator.speakResult(result.response.spoken)
                true
            }

            is OpenAppCommandResult.NoMatch -> {
                pendingOpenAppCandidatePackages = emptySet()
                debugLog("Local open-app no match. target=${result.target}")
                voiceTurnCoordinator.speakResult(result.response.spoken)
                true
            }

            OpenAppCommandResult.NotOpenAppCommand -> {
                debugLog("Not a local open-app command.")
                false
            }
        }
    }

    private suspend fun processPlannedResponse(
        utterance: String,
        response: AssistResponse,
        screenContext: ScreenContext,
    ): TurnResult {
        val validation = AiProtocolValidator.validate(response)
        if (!validation.isValid) {
            voiceTurnCoordinator.speakResult("AI 返回了不支持的动作，已拒绝执行。${validation.reason}")
            return TurnResult.Completed
        }

        if (RiskClassifier.shouldRejectActionsInContext(response.actions, screenContext)) {
            confirmationManager.clear()
            voiceTurnCoordinator.speakResult("当前页面包含支付、密码或验证码等高风险内容，暂不执行操作。")
            return TurnResult.Completed
        }

        val risky = response.requiresConfirmation ||
            RiskClassifier.requiresConfirmation(utterance, response.actions, screenContext)
        if (risky && response.actions.isNotEmpty()) {
            confirmationManager.store(response, screenContext)
            voiceTurnCoordinator.speakResult("${response.spoken} 这是高风险操作，如需继续，请再次唤起并说确认执行。")
            return TurnResult.Completed
        }

        executeResponse(response, confirmed = false, sourceScreen = screenContext)
        return TurnResult.Completed
    }

    private suspend fun executeResponse(
        response: AssistResponse,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ) {
        if (response.spoken.isNotBlank()) {
            voiceTurnCoordinator.speakResult(response.spoken)
        }
        if (response.actions.isEmpty()) return
        voiceState = VoiceInteractionState.Acting
        val results = actionRunner.execute(response.actions, confirmed, sourceScreen)
        val failed = results.firstOrNull { !it.success }
        if (failed != null) {
            if (failed.requiresScreenRefresh) {
                screenContextProvider.collect()
            }
            voiceTurnCoordinator.speakResult(failed.message)
        }
    }

    private enum class TurnResult {
        Completed,
        Ignored,
        ServiceFailure,
        StopRequested,
    }

    private fun localActionScreenContext(): ScreenContext =
        ScreenContext(
            packageName = "",
            activityName = null,
            nodes = emptyList(),
            screenshotBase64 = null,
        )

    private fun debugLog(message: String) {
        diagnosticLogger.log(TAG, message)
    }

    private fun AiProxyException.toAssistVoicePrompt(): String =
        when (type) {
            AiProxyErrorType.ConfigurationMissing -> "AI 服务连接未配置，请先在应用首页填写代理地址和 App token。"
            AiProxyErrorType.Authorization -> "AI 服务鉴权失败，请检查代理配置。"
            AiProxyErrorType.RateLimited -> "AI 服务请求过于频繁，请稍后重试。"
            AiProxyErrorType.ProviderUnavailable -> "AI 服务暂时不可用，请稍后重试。"
            AiProxyErrorType.RemoteTimeout -> "AI 服务响应超时，请稍后重试。"
            AiProxyErrorType.ClientTimeout -> "AI 请求超时，请检查网络后重试。"
            AiProxyErrorType.Network -> "网络连接失败，请检查网络后重试。"
            AiProxyErrorType.EmptyBody,
            AiProxyErrorType.Http,
            -> "AI 服务响应异常，已停止执行。"
        }

    private companion object {
        const val TAG = "SightSyncSession"
        const val MAX_CONSECUTIVE_SERVICE_FAILURES = 2
    }
}
