package com.sightsync.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.view.accessibility.AccessibilityEvent
import com.sightsync.assistant.BuildConfig
import com.sightsync.assistant.MainActivity
import com.sightsync.assistant.ai.AiProxyClient
import com.sightsync.assistant.core.ActionExecutor
import com.sightsync.assistant.core.ScreenContextCollector
import com.sightsync.assistant.speech.ProxySpeechInputController
import com.sightsync.assistant.speech.ShortAudioRecorder
import com.sightsync.assistant.speech.TtsOutputController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AssistantAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_STOP_LISTENING = "com.sightsync.assistant.action.STOP_LISTENING"
        private const val LISTENING_CHANNEL_ID = "sightsync_continuous_listening"
        private const val LISTENING_NOTIFICATION_ID = 1001
        private var activeService: AssistantAccessibilityService? = null

        fun stopListeningFromNotification() {
            activeService?.stopVisibleListening()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var overlayController: OverlayController
    private lateinit var ttsOutputController: TtsOutputController
    private lateinit var speechInputController: ProxySpeechInputController
    private lateinit var sessionManager: AssistantSessionManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        ttsOutputController = TtsOutputController(this)
        val aiProxyClient = AiProxyClient(BuildConfig.AI_PROXY_BASE_URL, BuildConfig.APP_API_TOKEN)
        speechInputController = ProxySpeechInputController(
            audioRecorder = ShortAudioRecorder(this),
            transcriptionClient = aiProxyClient,
        )
        sessionManager = AssistantSessionManager(
            scope = scope,
            speechInput = speechInputController,
            speechOutput = ttsOutputController,
            screenContextProvider = ScreenContextCollector(this),
            assistantClient = aiProxyClient,
            actionRunner = ActionExecutor(this),
            onContinuousListeningChanged = { active ->
                if (::overlayController.isInitialized) {
                    overlayController.setListening(active)
                }
                if (!active) {
                    stopListeningForeground()
                }
            },
        )
        overlayController = OverlayController(this) {
            if (sessionManager.isContinuousListening) {
                stopVisibleListening()
            } else {
                startVisibleListening()
            }
        }
        overlayController.show()
        startVisibleListening()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (::ttsOutputController.isInitialized) {
            ttsOutputController.stop()
        }
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        if (::sessionManager.isInitialized) sessionManager.stopContinuousListening()
        stopListeningForeground()
        if (::overlayController.isInitialized) overlayController.hide()
        if (::speechInputController.isInitialized) speechInputController.cancel()
        if (::ttsOutputController.isInitialized) ttsOutputController.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVisibleListening() {
        if (!startListeningForeground()) {
            ttsOutputController.speak("无法显示连续聆听通知，请检查通知权限后重试。")
            return
        }
        sessionManager.startContinuousListening()
    }

    private fun stopVisibleListening() {
        sessionManager.stopContinuousListening()
        stopListeningForeground()
    }

    private fun startListeningForeground(): Boolean {
        return runCatching {
            createListeningNotificationChannel()
            startForeground(
                LISTENING_NOTIFICATION_ID,
                createListeningNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }.isSuccess
    }

    private fun stopListeningForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createListeningNotificationChannel() {
        val channel = NotificationChannel(
            LISTENING_CHANNEL_ID,
            "SightSync 连续聆听",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 SightSync 正在连续聆听语音指令。"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createListeningNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, ContinuousListeningActionReceiver::class.java).apply {
                action = ACTION_STOP_LISTENING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, LISTENING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SightSync 正在连续聆听")
            .setContentText("麦克风正在等待你的语音指令。")
            .setContentIntent(openAppIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "停止聆听",
                    stopIntent,
                ).build(),
            )
            .build()
    }
}

class ContinuousListeningActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AssistantAccessibilityService.ACTION_STOP_LISTENING) {
            AssistantAccessibilityService.stopListeningFromNotification()
        }
    }
}
