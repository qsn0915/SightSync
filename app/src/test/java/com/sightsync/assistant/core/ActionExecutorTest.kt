package com.sightsync.assistant.core

import com.sightsync.assistant.ai.AssistantAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun openAppReportsMissingLaunchIntent() {
        val executor = ActionExecutor(
            appLauncher = FakeAppLauncher(AppLaunchResult.NoLaunchIntent),
        )

        val result = executor.execute(
            actions = listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.example.missing")),
            confirmed = false,
            sourceScreen = screenContext(),
        ).single()

        assertFalse(result.success)
        assertEquals("找不到这个应用的可启动入口。", result.message)
    }

    @Test
    fun openAppReportsLaunchException() {
        val executor = ActionExecutor(
            appLauncher = FakeAppLauncher(AppLaunchResult.Failed("Activity not found")),
        )

        val result = executor.execute(
            actions = listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.example.browser")),
            confirmed = false,
            sourceScreen = screenContext(),
        ).single()

        assertFalse(result.success)
        assertTrue(result.message.contains("打开应用失败"))
        assertTrue(result.message.contains("Activity not found"))
    }

    @Test
    fun openAppWritesAcceptanceLogsForRequestAndResult() {
        val logger = FakeDiagnosticLogger()
        val executor = ActionExecutor(
            appLauncher = FakeAppLauncher(AppLaunchResult.Succeeded),
            diagnosticLogger = logger,
        )

        val result = executor.execute(
            actions = listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.vivo.browser")),
            confirmed = false,
            sourceScreen = screenContext(),
        ).single()

        assertTrue(result.success)
        assertTrue(logger.messages.contains("SightSyncAction: OPEN_APP requested. package=com.vivo.browser"))
        assertTrue(logger.messages.contains("SightSyncAction: OPEN_APP succeeded. package=com.vivo.browser"))
    }
}

private class FakeAppLauncher(
    private val result: AppLaunchResult,
) : AppLauncher {
    override fun launch(packageName: String): AppLaunchResult = result
}

private class FakeDiagnosticLogger : com.sightsync.assistant.diagnostics.DiagnosticLogger {
    val messages = mutableListOf<String>()

    override fun log(tag: String, message: String) {
        messages += "$tag: $message"
    }
}

private fun screenContext(): ScreenContext =
    ScreenContext(
        packageName = "",
        activityName = null,
        nodes = emptyList(),
        screenshotBase64 = null,
    )
