package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Test

class PageExpectationMatcherTest {
    @Test
    fun matchesAllDeclaredPageConditions() {
        val screen = screenContext(
            packageName = "com.android.browser",
            activityName = "BrowserActivity",
            nodes = listOf(
                node("node_1", text = "搜索网页"),
                node("node_2", contentDescription = "提交搜索"),
            ),
        )
        val expectation = PageExpectation(
            packageName = "com.android.browser",
            activityName = "BrowserActivity",
            requiredNodeIds = listOf("node_1", "node_2"),
            requiredTexts = listOf("搜索", "提交"),
        )

        assertEquals(PageExpectationMatch.Matched, PageExpectationMatcher.match(expectation, screen))
    }

    @Test
    fun reportsPackageAndActivityMismatch() {
        val screen = screenContext(
            packageName = "com.actual",
            activityName = "ActualActivity",
        )

        assertEquals(
            PageExpectationMatch.Mismatch("当前应用与计划不一致。"),
            PageExpectationMatcher.match(PageExpectation(packageName = "com.expected"), screen),
        )
        assertEquals(
            PageExpectationMatch.Mismatch("当前页面与计划不一致。"),
            PageExpectationMatcher.match(PageExpectation(activityName = "ExpectedActivity"), screen),
        )
    }

    @Test
    fun reportsMissingRequiredNode() {
        val result = PageExpectationMatcher.match(
            PageExpectation(requiredNodeIds = listOf("node_missing")),
            screenContext(nodes = listOf(node("node_1", text = "设置"))),
        )

        assertEquals(PageExpectationMatch.Mismatch("页面缺少计划要求的控件。"), result)
    }

    @Test
    fun matchesVisibleTextAcrossDescriptionAndInputContext() {
        val screen = screenContext(
            nodes = listOf(
                node("node_1", contentDescription = "提交搜索"),
                node("node_2", inputContext = "网页地址栏"),
            ),
        )

        assertEquals(
            PageExpectationMatch.Matched,
            PageExpectationMatcher.match(
                PageExpectation(requiredTexts = listOf("提交", "地址栏")),
                screen,
            ),
        )
        assertEquals(
            PageExpectationMatch.Mismatch("页面缺少计划要求的文字。"),
            PageExpectationMatcher.match(PageExpectation(requiredTexts = listOf("下载")), screen),
        )
    }

    private fun screenContext(
        packageName: String = "com.example",
        activityName: String? = null,
        nodes: List<ScreenNode> = emptyList(),
    ) = ScreenContext(
        packageName = packageName,
        activityName = activityName,
        nodes = nodes,
        screenshotBase64 = null,
    )

    private fun node(
        id: String,
        text: String? = null,
        contentDescription: String? = null,
        inputContext: String? = null,
    ) = ScreenNode(
        nodeId = id,
        text = text,
        contentDescription = contentDescription,
        role = "View",
        bounds = NodeBounds(0, 0, 100, 100),
        clickable = false,
        editable = inputContext != null,
        scrollable = false,
        inputContext = inputContext,
    )
}
