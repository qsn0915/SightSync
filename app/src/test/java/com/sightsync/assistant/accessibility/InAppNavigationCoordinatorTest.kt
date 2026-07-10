package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppNavigationCoordinatorTest {
    @Test
    fun explicitCurrentPageCommandsProduceBoundedClickPlan() = runTest {
        listOf(
            "点击 WLAN",
            "在当前页面进入 WLAN",
            "打开当前页面的 WLAN",
        ).forEach { utterance ->
            val wlan = node(id = "node_wlan", text = "WLAN", clickable = true)
            val coordinator = InAppNavigationCoordinator(QueueNavigationScreenProvider(screen(wlan)))

            val result = coordinator.resolve(utterance)

            assertTrue("Expected Ready for $utterance", result is InAppNavigationResolution.Ready)
            val ready = result as InAppNavigationResolution.Ready
            assertEquals("WLAN", ready.targetLabel)
            assertEquals(listOf("ACTION", "VALIDATE_PAGE"), ready.plan.steps.map { it.kind })
            assertEquals("CLICK_NODE", ready.plan.steps.first().action?.type)
            assertEquals("node_wlan", ready.plan.steps.first().action?.nodeId)
            assertEquals(10_000L, ready.plan.maxDurationMillis)
        }
    }

    @Test
    fun nonNavigationCommandsDoNotCollectScreen() = runTest {
        val provider = QueueNavigationScreenProvider(screen())
        val coordinator = InAppNavigationCoordinator(provider)

        listOf("打开微信", "进入设置", "今天天气不错").forEach { utterance ->
            assertEquals(InAppNavigationResolution.NotCommand, coordinator.resolve(utterance))
        }

        assertEquals(0, provider.validationCalls)
    }

    @Test
    fun emptyTargetAsksAndOversizedTargetStops() = runTest {
        val provider = QueueNavigationScreenProvider(screen(), screen())
        val emptyCoordinator = InAppNavigationCoordinator(provider)
        val longCoordinator = InAppNavigationCoordinator(provider)

        val empty = emptyCoordinator.resolve("点击")
        val oversized = longCoordinator.resolve("点击${"无".repeat(101)}")

        assertTrue(empty is InAppNavigationResolution.AskClarification)
        assertTrue(oversized is InAppNavigationResolution.Stopped)
        assertFalse(longCoordinator.hasPendingClarification)
    }

    @Test
    fun missingTargetAllowsOneBareClarificationOnSamePackage() = runTest {
        val wireless = node(id = "node_wireless", text = "无线网络", clickable = true)
        val provider = QueueNavigationScreenProvider(screen(), screen(wireless))
        val coordinator = InAppNavigationCoordinator(provider)

        val first = coordinator.resolve("点击 WLAN")
        val second = coordinator.resolve("无线网络")

        assertTrue(first is InAppNavigationResolution.AskClarification)
        assertTrue(coordinator.hasPendingClarification.not())
        assertTrue(second is InAppNavigationResolution.Ready)
        assertEquals("node_wireless", (second as InAppNavigationResolution.Ready).plan.steps.first().action?.nodeId)
    }

    @Test
    fun missingClarificationStopsAfterOneAttemptOrPackageChange() = runTest {
        val stillMissing = InAppNavigationCoordinator(
            QueueNavigationScreenProvider(screen(), screen()),
        )
        assertTrue(stillMissing.resolve("点击 WLAN") is InAppNavigationResolution.AskClarification)
        val missingResult = stillMissing.resolve("无线网络")

        val changed = InAppNavigationCoordinator(
            QueueNavigationScreenProvider(screen(), screen(packageName = "com.other")),
        )
        assertTrue(changed.resolve("点击 WLAN") is InAppNavigationResolution.AskClarification)
        val changedResult = changed.resolve("无线网络")

        assertTrue(missingResult is InAppNavigationResolution.Stopped)
        assertFalse(stillMissing.hasPendingClarification)
        assertTrue(changedResult is InAppNavigationResolution.Stopped)
        assertTrue((changedResult as InAppNavigationResolution.Stopped).spoken.contains("应用已变化"))
    }

    @Test
    fun ambiguousCandidatesCanBeDisambiguatedOnceBySavedLabels() = runTest {
        val first = ambiguousScreen()
        val second = ambiguousScreen()
        val coordinator = InAppNavigationCoordinator(QueueNavigationScreenProvider(first, second))

        val question = coordinator.resolve("点击 WLAN")
        val resolved = coordinator.resolve("WLAN 设置")

        assertTrue(question is InAppNavigationResolution.AskClarification)
        assertTrue((question as InAppNavigationResolution.AskClarification).spoken.contains("WLAN 设置"))
        assertTrue(question.spoken.contains("WLAN 帮助"))
        assertTrue(resolved is InAppNavigationResolution.Ready)
        assertEquals(
            "node_settings",
            (resolved as InAppNavigationResolution.Ready).plan.steps.first().action?.nodeId,
        )
        assertFalse(coordinator.hasPendingClarification)
    }

    @Test
    fun ambiguousClarificationCandidateLossOrLabelChangeStops() = runTest {
        val stillAmbiguous = InAppNavigationCoordinator(
            QueueNavigationScreenProvider(ambiguousScreen(), ambiguousScreen()),
        )
        assertTrue(stillAmbiguous.resolve("点击 WLAN") is InAppNavigationResolution.AskClarification)
        assertTrue(stillAmbiguous.resolve("WLAN") is InAppNavigationResolution.Stopped)

        val missingCandidate = InAppNavigationCoordinator(
            QueueNavigationScreenProvider(
                ambiguousScreen(),
                screen(
                    node(id = "node_help", clickable = true),
                    node(id = "node_help_label", text = "WLAN 帮助", parentId = "node_help"),
                ),
            ),
        )
        assertTrue(missingCandidate.resolve("点击 WLAN") is InAppNavigationResolution.AskClarification)
        assertTrue(missingCandidate.resolve("WLAN 设置") is InAppNavigationResolution.Stopped)

        val changedLabel = InAppNavigationCoordinator(
            QueueNavigationScreenProvider(
                ambiguousScreen(),
                screen(
                    node(id = "node_settings", clickable = true),
                    node(id = "node_settings_label", text = "WLAN 高级", parentId = "node_settings"),
                    node(id = "node_help", clickable = true),
                    node(id = "node_help_label", text = "WLAN 帮助", parentId = "node_help"),
                ),
            ),
        )
        assertTrue(changedLabel.resolve("点击 WLAN") is InAppNavigationResolution.AskClarification)
        val changedResult = changedLabel.resolve("WLAN 设置")
        assertTrue(changedResult is InAppNavigationResolution.Stopped)
        assertTrue((changedResult as InAppNavigationResolution.Stopped).spoken.contains("候选已变化"))
    }

    @Test
    fun sameNamedCandidatesCannotCreateUnsafeClarification() = runTest {
        val coordinator = InAppNavigationCoordinator(
            QueueNavigationScreenProvider(
                screen(
                    node(id = "node_a", text = "WLAN", clickable = true),
                    node(id = "node_b", text = "WLAN", clickable = true),
                ),
            ),
        )

        val result = coordinator.resolve("点击 WLAN")

        assertTrue(result is InAppNavigationResolution.Stopped)
        assertTrue((result as InAppNavigationResolution.Stopped).spoken.contains("同名"))
        assertFalse(coordinator.hasPendingClarification)
    }

    @Test
    fun clearDropsPendingClarification() = runTest {
        val coordinator = InAppNavigationCoordinator(QueueNavigationScreenProvider(screen()))
        assertTrue(coordinator.resolve("点击 WLAN") is InAppNavigationResolution.AskClarification)
        assertTrue(coordinator.hasPendingClarification)

        coordinator.clear()

        assertFalse(coordinator.hasPendingClarification)
        assertEquals(InAppNavigationResolution.NotCommand, coordinator.resolve("无线网络"))
    }

    private fun ambiguousScreen(): ScreenContext =
        screen(
            node(id = "node_settings", clickable = true),
            node(id = "node_settings_label", text = "WLAN 设置", parentId = "node_settings"),
            node(id = "node_help", clickable = true),
            node(id = "node_help_label", text = "WLAN 帮助", parentId = "node_help"),
        )

    private fun screen(
        vararg nodes: ScreenNode,
        packageName: String = "com.android.settings",
    ): ScreenContext =
        ScreenContext(
            packageName = packageName,
            activityName = "Settings",
            nodes = nodes.toList(),
            screenshotBase64 = null,
        )

    private fun node(
        id: String,
        text: String? = null,
        clickable: Boolean = false,
        parentId: String? = null,
    ): ScreenNode =
        ScreenNode(
            nodeId = id,
            text = text,
            contentDescription = null,
            role = "View",
            bounds = NodeBounds(0, 0, 100, 100),
            clickable = clickable,
            editable = false,
            scrollable = false,
            parentNodeId = parentId,
        )
}

private class QueueNavigationScreenProvider(
    vararg screens: ScreenContext,
) : ScreenContextProvider {
    private val pending = ArrayDeque(screens.toList())
    var validationCalls = 0

    override suspend fun collect(): ScreenContext = error("普通 collect 不应被导航使用")

    override suspend fun collectForValidation(): ScreenContext {
        validationCalls += 1
        return pending.removeFirstOrNull() ?: error("没有更多验证页面")
    }
}
