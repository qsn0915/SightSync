package com.sightsync.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNodeTreeExtractorTest {
    @Test
    fun extractsUsefulNodesWithStableIdsRolesAndFlags() {
        val root = ScreenNodeSnapshot(
            className = "android.widget.LinearLayout",
            children = listOf(
                ScreenNodeSnapshot(
                    text = "设置",
                    className = "android.widget.TextView",
                    bounds = NodeBounds(0, 0, 200, 80),
                ),
                ScreenNodeSnapshot(
                    text = "WLAN",
                    className = "android.widget.Button",
                    bounds = NodeBounds(0, 90, 200, 170),
                    clickable = true,
                ),
                ScreenNodeSnapshot(
                    text = "6222021234567890123",
                    className = "android.widget.EditText",
                    bounds = NodeBounds(0, 180, 200, 260),
                    editable = true,
                ),
                ScreenNodeSnapshot(className = "android.view.View"),
            ),
        )

        val nodes = ScreenNodeTreeExtractor().extract(root)

        assertEquals(3, nodes.size)
        assertEquals("node_0", nodes[0].nodeId)
        assertEquals("设置", nodes[0].text)
        assertEquals("TextView", nodes[0].role)
        assertFalse(nodes[0].clickable)

        assertEquals("node_1", nodes[1].nodeId)
        assertEquals("WLAN", nodes[1].text)
        assertEquals("Button", nodes[1].role)
        assertTrue(nodes[1].clickable)

        assertEquals("node_2", nodes[2].nodeId)
        assertEquals("[号码已隐藏]", nodes[2].text)
        assertEquals("EditText", nodes[2].role)
        assertTrue(nodes[2].editable)
    }

    @Test
    fun redactsPasswordAndSensitiveContentDescriptions() {
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    text = "secret",
                    contentDescription = "验证码 123456",
                    className = "android.widget.EditText",
                    password = true,
                    editable = true,
                ),
            ),
        )

        val node = ScreenNodeTreeExtractor().extract(root).single()

        assertEquals("[已隐藏]", node.text)
        assertEquals("[已隐藏]", node.contentDescription)
    }

    @Test
    fun limitsExtractedNodesForAiPayloadSize() {
        val root = ScreenNodeSnapshot(
            children = (0 until 250).map { index ->
                ScreenNodeSnapshot(
                    text = "项目 $index",
                    className = "android.widget.TextView",
                )
            },
        )

        val nodes = ScreenNodeTreeExtractor().extract(root)

        assertEquals(200, nodes.size)
        assertEquals("node_199", nodes.last().nodeId)
    }

    @Test
    fun extractsHierarchyRegionActionableTypeScrollContainerAndInputContext() {
        val root = ScreenNodeSnapshot(
            className = "android.widget.FrameLayout",
            bounds = NodeBounds(0, 0, 1080, 2400),
            children = listOf(
                ScreenNodeSnapshot(
                    text = "网络设置",
                    className = "android.widget.TextView",
                    bounds = NodeBounds(0, 0, 1080, 120),
                ),
                ScreenNodeSnapshot(
                    className = "android.widget.ScrollView",
                    bounds = NodeBounds(0, 120, 1080, 2200),
                    scrollable = true,
                    children = listOf(
                        ScreenNodeSnapshot(
                            text = "WLAN",
                            className = "android.widget.Button",
                            bounds = NodeBounds(0, 150, 1080, 260),
                            clickable = true,
                        ),
                        ScreenNodeSnapshot(
                            text = "网络名称",
                            className = "android.widget.TextView",
                            bounds = NodeBounds(0, 300, 1080, 360),
                        ),
                        ScreenNodeSnapshot(
                            contentDescription = "请输入网络名称",
                            className = "android.widget.EditText",
                            bounds = NodeBounds(0, 380, 1080, 480),
                            editable = true,
                        ),
                    ),
                ),
            ),
        )

        val nodes = ScreenNodeTreeExtractor().extract(root)

        val title = nodes.single { it.text == "网络设置" }
        val scrollContainer = nodes.single { it.role == "ScrollView" }
        val wlan = nodes.single { it.text == "WLAN" }
        val input = nodes.single { it.editable }

        assertEquals(null, title.parentNodeId)
        assertEquals(1, title.depth)
        assertEquals("top", title.region)
        assertEquals(null, title.actionableType)

        assertEquals("scroll", scrollContainer.actionableType)
        assertEquals(null, scrollContainer.scrollContainerNodeId)

        assertEquals(scrollContainer.nodeId, wlan.parentNodeId)
        assertEquals(2, wlan.depth)
        assertEquals("click", wlan.actionableType)
        assertEquals(scrollContainer.nodeId, wlan.scrollContainerNodeId)

        assertEquals("input", input.actionableType)
        assertEquals("网络名称", input.inputContext)
        assertEquals(scrollContainer.nodeId, input.scrollContainerNodeId)
    }

    @Test
    fun marksNodesPrivacySensitiveWhenLocalRedactionHidesContent() {
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    text = "验证码 123456",
                    className = "android.widget.TextView",
                ),
                ScreenNodeSnapshot(
                    text = "secret",
                    className = "android.widget.EditText",
                    password = true,
                    editable = true,
                ),
            ),
        )

        val nodes = ScreenNodeTreeExtractor().extract(root)

        assertEquals("[验证码已隐藏]", nodes[0].text)
        assertTrue(nodes[0].privacySensitive)
        assertEquals("[已隐藏]", nodes[1].text)
        assertTrue(nodes[1].privacySensitive)
    }

    @Test
    fun screenshotPolicyBlocksSensitiveNodesBeforeSparseFallback() {
        val sparseSensitiveNodes = listOf(
            ScreenNode(
                nodeId = "node_0",
                text = "[已隐藏]",
                contentDescription = null,
                role = "EditText",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = false,
                editable = true,
                scrollable = false,
                privacySensitive = true,
            ),
        )
        val emptyDecision = ScreenContextPolicy.decideScreenshot(emptyList())
        val sparseDecision = ScreenContextPolicy.decideScreenshot(
            listOf(
                ScreenNode(
                    nodeId = "node_0",
                    text = null,
                    contentDescription = null,
                    role = "WebView",
                    bounds = NodeBounds(0, 0, 100, 100),
                    clickable = false,
                    editable = false,
                    scrollable = true,
                ),
            ),
        )
        val sensitiveDecision = ScreenContextPolicy.decideScreenshot(sparseSensitiveNodes)

        assertTrue(emptyDecision.attachScreenshot)
        assertEquals("empty_node_tree", emptyDecision.reason)
        assertFalse(emptyDecision.privacyBlocked)

        assertTrue(sparseDecision.attachScreenshot)
        assertEquals("sparse_node_tree", sparseDecision.reason)
        assertFalse(sparseDecision.privacyBlocked)

        assertFalse(sensitiveDecision.attachScreenshot)
        assertEquals("privacy_sensitive_content", sensitiveDecision.reason)
        assertTrue(sensitiveDecision.privacyBlocked)
    }

    @Test
    fun attachesScreenshotOnlyWhenNodeTreeIsInsufficient() {
        val richNodes = listOf(
            ScreenNode(
                nodeId = "node_0",
                text = "设置",
                contentDescription = null,
                role = "TextView",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = false,
                editable = false,
                scrollable = false,
            ),
            ScreenNode(
                nodeId = "node_1",
                text = "WLAN",
                contentDescription = null,
                role = "Button",
                bounds = NodeBounds(0, 100, 100, 200),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
            ScreenNode(
                nodeId = "node_2",
                text = "蓝牙",
                contentDescription = null,
                role = "Button",
                bounds = NodeBounds(0, 200, 100, 300),
                clickable = true,
                editable = false,
                scrollable = false,
            ),
        )
        val sparseNodes = listOf(
            ScreenNode(
                nodeId = "node_0",
                text = null,
                contentDescription = null,
                role = "WebView",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = false,
                editable = false,
                scrollable = true,
            ),
        )

        assertFalse(ScreenContextPolicy.shouldAttachScreenshot(richNodes))
        assertTrue(ScreenContextPolicy.shouldAttachScreenshot(sparseNodes))
        assertTrue(ScreenContextPolicy.shouldAttachScreenshot(emptyList()))
    }

    @Test
    fun blankValuesRemainNullAfterExtraction() {
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    contentDescription = "   ",
                    className = "android.widget.Button",
                    clickable = true,
                ),
            ),
        )

        val node = ScreenNodeTreeExtractor().extract(root).single()

        assertNull(node.text)
        assertNull(node.contentDescription)
    }
}
