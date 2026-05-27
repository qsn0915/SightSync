package com.sightsync.assistant.accessibility

import android.util.Log
import com.sightsync.assistant.ai.AiProtocolValidator
import com.sightsync.assistant.ai.AssistResponse
import com.sightsync.assistant.apps.OpenAppCommandResolver
import com.sightsync.assistant.apps.OpenAppCommandResult
import com.sightsync.assistant.core.RiskClassifier
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.speech.SpeechInput
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
) {
    private var activeJob: Job? = null
    private var continuousJob: Job? = null
    private val confirmationManager = ConfirmationManager()
    private val sessionId = UUID.randomUUID().toString()
    private var voiceState: VoiceInteractionState = VoiceInteractionState.Idle
    private var pendingOpenAppClarification = false
    private val voiceTurnCoordinator = VoiceTurnCoordinator(
        speechInput = speechInput,
        speechOutput = speechOutput,
        onStateChanged = { state -> voiceState = state },
    )

    val isContinuousListening: Boolean
        get() = continuousJob?.isActive == true

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
        if (isContinuousListening) return

        val running = activeJob
        if (running?.isActive == true) {
            cancelActiveRequest()
        }
        if (speechOutput.isSpeaking) {
            voiceTurnCoordinator.cancelVoice()
        }

        continuousJob = scope.launch {
            try {
                voiceTurnCoordinator.speakResult("连续聆听已开启。")
                while (true) {
                    val result = runAssistantTurn(
                        promptBeforeListening = false,
                        stopCommandEndsContinuousListening = true,
                    )
                    if (result == TurnResult.StopRequested) {
                        voiceTurnCoordinator.speakResult("已停止聆听。")
                        break
                    }
                }
            } finally {
                continuousJob = null
                onContinuousListeningChanged(false)
            }
        }
        onContinuousListeningChanged(true)
    }

    fun stopContinuousListening() {
        val running = continuousJob ?: return
        running.cancel()
        continuousJob = null
        onContinuousListeningChanged(false)
        confirmationManager.clear()
        pendingOpenAppClarification = false
        voiceTurnCoordinator.cancelVoice()
        scope.launch {
            voiceTurnCoordinator.speakResult("已停止聆听。")
        }
    }

    private fun cancelActiveRequest() {
        activeJob?.cancel()
        activeJob = null
        confirmationManager.clear()
        pendingOpenAppClarification = false
        voiceTurnCoordinator.cancelVoice()
        scope.launch {
            voiceTurnCoordinator.speakResult("已取消。")
        }
    }

    private suspend fun runAssistantTurn(
        promptBeforeListening: Boolean,
        stopCommandEndsContinuousListening: Boolean,
    ): TurnResult {
        return try {
            val speechResult = voiceTurnCoordinator.listenForTurn(
                prompt = if (promptBeforeListening) "请说。" else null,
            )
            val utterance = when (speechResult) {
                is SpeechInputResult.Recognized -> speechResult.text.trim()
                is SpeechInputResult.Failed -> {
                    if (!stopCommandEndsContinuousListening || !isNoSpeechFailure(speechResult.message)) {
                        voiceTurnCoordinator.speakResult(speechResult.message)
                    }
                    return TurnResult.Completed
                }
                SpeechInputResult.Cancelled -> return TurnResult.Completed
            }
            if (utterance.isBlank()) {
                if (!stopCommandEndsContinuousListening) {
                    voiceTurnCoordinator.speakResult("我没有听清，请再说一次。")
                }
                return TurnResult.Completed
            }
            debugLog("ASR utterance='$utterance'")
            val confirmedRequest = confirmationManager.consumeIfConfirmed(utterance)
            if (confirmedRequest != null) {
                pendingOpenAppClarification = false
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
                pendingOpenAppClarification = false
                return TurnResult.StopRequested
            }
            if (pendingOpenAppClarification && confirmationManager.isCancellation(utterance)) {
                pendingOpenAppClarification = false
                voiceTurnCoordinator.speakResult("已取消。")
                return TurnResult.Completed
            }

            if (handleLocalOpenAppCommand(utterance)) {
                return TurnResult.Completed
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
        } catch (timeout: InterruptedIOException) {
            voiceTurnCoordinator.speakResult("AI 请求超时，请稍后重试。")
            TurnResult.Completed
        } catch (network: IOException) {
            voiceTurnCoordinator.speakResult("网络连接失败，请检查网络后重试。")
            TurnResult.Completed
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

    private fun isNoSpeechFailure(message: String): Boolean =
        message == "我没有听清，请再说一次。"

    private suspend fun handleLocalOpenAppCommand(utterance: String): Boolean {
        val resolver = openAppCommandResolver ?: return false
        debugLog("Resolving local open-app command. pendingClarification=$pendingOpenAppClarification")
        return when (val result = resolver.resolve(utterance, allowBareTarget = pendingOpenAppClarification)) {
            is OpenAppCommandResult.Resolved -> {
                pendingOpenAppClarification = false
                debugLog("Local open-app resolved. actions=${result.response.actions}")
                processPlannedResponse(utterance, result.response, localActionScreenContext())
                true
            }

            is OpenAppCommandResult.Ambiguous -> {
                pendingOpenAppClarification = true
                debugLog("Local open-app ambiguous.")
                voiceTurnCoordinator.speakResult(result.response.spoken)
                true
            }

            is OpenAppCommandResult.NoMatch -> {
                pendingOpenAppClarification = false
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
        runCatching { Log.d(TAG, message) }
    }

    private companion object {
        const val TAG = "SightSyncSession"
    }
}
