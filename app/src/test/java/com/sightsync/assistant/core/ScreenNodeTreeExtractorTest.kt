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
