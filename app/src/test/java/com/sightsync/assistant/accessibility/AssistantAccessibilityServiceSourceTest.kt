package com.sightsync.assistant.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantAccessibilityServiceSourceTest {
    @Test
    fun usesProxySpeechInputInsteadOfAndroidSpeechRecognizer() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("ProxySpeechInputController"))
        assertFalse(source.contains("import com.sightsync.assistant.speech.SpeechInputController"))
    }

    @Test
    fun startsVisibleContinuousListeningWithForegroundNotification() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("startContinuousListening()"))
        assertTrue(source.contains("stopContinuousListening()"))
        assertTrue(source.contains("NotificationChannel"))
        assertTrue(source.contains("startForeground("))
        assertTrue(source.contains("FOREGROUND_SERVICE_TYPE_MICROPHONE"))
    }

    @Test
    fun visibleAlwaysOnListeningKeepsStopEntryPoints() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("setOngoing(true)"))
        assertTrue(source.contains("停止聆听"))
        assertTrue(source.contains("ACTION_STOP_LISTENING"))
        assertTrue(source.contains("startVisibleListening()"))
        assertTrue(source.contains("stopVisibleListening()"))
    }

    @Test
    fun ttsInitializationFailureUsesVisibleFallbackStatus() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("TtsOutputController(this) {"))
        assertTrue(source.contains("showSpeechOutputUnavailableFallback()"))
        assertTrue(source.contains("Toast.makeText"))
        assertTrue(source.contains("语音输出不可用，请检查系统 TTS 设置。"))
        assertTrue(source.contains("updateListeningNotification()"))
        assertTrue(source.contains("notify(LISTENING_NOTIFICATION_ID"))
    }

    @Test
    fun wiresLocalOpenAppResolverIntoSessionManager() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("PackageManagerAppCatalogProvider"))
        assertTrue(source.contains("OpenAppCommandResolver"))
        assertTrue(source.contains("openAppCommandResolver ="))
    }

    @Test
    fun wiresBrowserSearchTaskWithSharedRuntimeDependencies() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("val appCatalogProvider = PackageManagerAppCatalogProvider(this)"))
        assertTrue(source.contains("val openAppCommandResolver = OpenAppCommandResolver(appCatalogProvider)"))
        assertTrue(source.contains("val screenContextProvider = ScreenContextCollector(this)"))
        assertTrue(source.contains("val actionRunner = ActionExecutor(this)"))
        assertTrue(source.contains("BrowserSearchCommandResolver(openAppCommandResolver)"))
        assertTrue(source.contains("AgentPlanExecutor(screenContextProvider, actionRunner)"))
        assertTrue(source.contains("BrowserSearchTaskRunner("))
        assertTrue(source.contains("browserSearchTaskExecutor ="))
    }

    @Test
    fun wiresInAppNavigationWithSharedPlanExecutor() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("val agentPlanExecutor = AgentPlanExecutor(screenContextProvider, actionRunner)"))
        assertTrue(source.contains("planExecutor = agentPlanExecutor"))
        assertTrue(source.contains("val inAppNavigationResolver = InAppNavigationCoordinator(screenContextProvider)"))
        assertTrue(source.contains("inAppNavigationResolver = inAppNavigationResolver"))
        assertTrue(source.contains("navigationPlanExecutor = agentPlanExecutor"))
    }

    @Test
    fun wiresWeChatDraftFlowWithSharedRuntimeDependencies() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("WeChatDraftCommandResolver()"))
        assertTrue(source.contains("val weChatDraftTaskExecutor = WeChatDraftTaskRunner("))
        assertTrue(source.contains("screenContextProvider = screenContextProvider"))
        assertTrue(source.contains("planExecutor = agentPlanExecutor"))
        assertTrue(source.contains("weChatDraftCommandResolver ="))
        assertTrue(source.contains("weChatDraftTaskExecutor = weChatDraftTaskExecutor"))
    }

    @Test
    fun usesSavedAiServiceConnectionConfigInsteadOfBuildConfigProxyDefaults() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("AiServiceConnectionConfigStore.create(this).load()"))
        assertTrue(source.contains("ConfiguredAiProxyClientFactory.create("))
        assertFalse(source.contains("BuildConfig.AI_PROXY_BASE_URL"))
        assertFalse(source.contains("BuildConfig.APP_API_TOKEN"))
    }

    @Test
    fun repeatedServiceConnectionReusesInitializedControllers() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("if (::sessionManager.isInitialized) {"))
        assertTrue(source.contains("Reusing initialized accessibility service controllers"))
    }

    @Test
    fun serviceDestroyDisposesSessionWithoutStopAnnouncement() {
        val source = File("src/main/java/com/sightsync/assistant/accessibility/AssistantAccessibilityService.kt").readText()

        assertTrue(source.contains("sessionManager.dispose()"))
        assertFalse(source.contains("onDestroy()\n        if (::sessionManager.isInitialized) sessionManager.stopContinuousListening()"))
    }

    @Test
    fun v2SpeechAcceptanceChecklistDocumentsLongRunningManualChecks() {
        val source = File("../docs/v2-speech-acceptance.md")

        assertTrue(source.exists())

        val text = source.readText()
        assertTrue(text.contains("V2 语音稳定性验收清单"))
        assertTrue(text.contains("通知动作停止聆听"))
        assertTrue(text.contains("悬浮窗再次开启并停止聆听"))
    }
}
