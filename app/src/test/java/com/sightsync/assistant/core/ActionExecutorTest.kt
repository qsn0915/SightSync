package com.sightsync.assistant.core

import com.sightsync.assistant.ai.AssistantAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun scrollAndGlobalActionsValidateTheSourcePageBeforeExecuting() {
        val source = File("src/main/java/com/sightsync/assistant/core/ActionExecutor.kt").readText()

        assertTrue(source.contains("private fun scroll(action: Int, sourceScreen: ScreenContext)"))
        assertTrue(source.contains("private fun global(action: Int, message: String, sourceScreen: ScreenContext)"))
        assertEquals(3, Regex("matchesSourcePackage\\(root, sourceScreen\\)").findAll(source).count())
    }

    @Test
    fun rejectsBatchActionsBeforeExecutingAnyOfThem() {
        val launcher = FakeAppLauncher(AppLaunchResult.Succeeded)
        val executor = ActionExecutor(appLauncher = launcher)

        val results = executor.execute(
            actions = listOf(
                AssistantAction(type = "OPEN_APP", appPackage = "com.example.first"),
                AssistantAction(type = "OPEN_APP", appPackage = "com.example.second"),
            ),
            confirmed = false,
            sourceScreen = screenContext(),
        )

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertEquals("当前阶段每次最多执行一个动作。", results.single().message)
        assertTrue(launcher.launchedPackages.isEmpty())
    }

    @Test
    fun rejectsUnconfirmedHighRiskTargetBeforeCallingAccessibilityService() {
        val executor = ActionExecutor(
            appLauncher = FakeAppLauncher(AppLaunchResult.Succeeded),
        )

        val result = executor.execute(
            actions = listOf(AssistantAction(type = "CLICK_NODE", nodeId = "node_delete")),
            confirmed = false,
            sourceScreen = screenContext(
                nodes = listOf(
                    ScreenNode(
                        nodeId = "node_delete",
                        text = "删除",
                        contentDescription = null,
                        role = "Button",
                        bounds = NodeBounds(0, 0, 100, 50),
                        clickable = true,
                        editable = false,
                        scrollable = false,
                    ),
                ),
            ),
        ).single()

        assertFalse(result.success)
        assertEquals("高风险操作尚未确认，已拒绝执行。", result.message)
    }

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
        assertTrue(logger.messages.contains("SightSyncAction: OPEN_APP requested"))
        assertTrue(logger.messages.contains("SightSyncAction: OPEN_APP succeeded"))
        assertFalse(logger.messages.any { it.contains("com.vivo.browser") })
    }
}

private class FakeAppLauncher(
    private val result: AppLaunchResult,
) : AppLauncher {
    val launchedPackages = mutableListOf<String>()

    override fun launch(packageName: String): AppLaunchResult {
        launchedPackages += packageName
        return result
    }
}

private class FakeDiagnosticLogger : com.sightsync.assistant.diagnostics.DiagnosticLogger {
    val messages = mutableListOf<String>()

    override fun log(tag: String, message: String) {
        messages += "$tag: $message"
    }
}

private fun screenContext(nodes: List<ScreenNode> = emptyList()): ScreenContext =
    ScreenContext(
        packageName = "",
        activityName = null,
        nodes = nodes,
        screenshotBase64 = null,
    )
