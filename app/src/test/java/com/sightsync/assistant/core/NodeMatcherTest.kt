package com.sightsync.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeMatcherTest {
    private val nodes = listOf(
        ScreenNode(
            nodeId = "title",
            text = "网络和互联网",
            contentDescription = null,
            role = "TextView",
            bounds = NodeBounds(0, 0, 400, 100),
            clickable = false,
            editable = false,
            scrollable = false,
        ),
        ScreenNode(
            nodeId = "ok",
            text = "确定",
            contentDescription = null,
            role = "Button",
            bounds = NodeBounds(0, 100, 200, 180),
            clickable = true,
            editable = false,
            scrollable = false,
        ),
        ScreenNode(
            nodeId = "cancel",
            text = "取消",
            contentDescription = null,
            role = "Button",
            bounds = NodeBounds(210, 100, 400, 180),
            clickable = true,
            editable = false,
            scrollable = false,
        ),
    )

    @Test
    fun findsUniqueClickableNodeByText() {
        val result = NodeMatcher.findUniqueClickable(nodes, "确定")

        assertTrue(result is NodeMatch.Found)
        assertEquals("ok", (result as NodeMatch.Found).node.nodeId)
    }

    @Test
    fun ignoresNonClickableNodes() {
        val result = NodeMatcher.findUniqueClickable(nodes, "网络")

        assertTrue(result is NodeMatch.NotFound)
    }

    @Test
    fun reportsAmbiguousWhenMultipleClickableNodesMatch() {
        val duplicated = nodes + nodes[1].copy(nodeId = "ok2")

        val result = NodeMatcher.findUniqueClickable(duplicated, "确定")

        assertTrue(result is NodeMatch.Ambiguous)
        assertEquals(2, (result as NodeMatch.Ambiguous).candidates.size)
    }

    @Test
    fun findsNodeByStableId() {
        val result = NodeMatcher.findById(nodes, "ok")

        assertEquals(nodes[1], result)
    }

    @Test
    fun snapshotMatchesWhenIdentityAndClickabilityAreStable() {
        val expected = nodes[1]
        val current = expected.copy(bounds = NodeBounds(5, 100, 205, 180))

        assertTrue(NodeMatcher.matchesSnapshot(current = current, expected = expected))
    }

    @Test
    fun snapshotDoesNotMatchWhenTargetTextChanged() {
        val expected = nodes[1]
        val current = expected.copy(text = "取消")

        assertFalse(NodeMatcher.matchesSnapshot(current = current, expected = expected))
    }

    @Test
    fun snapshotDoesNotMatchWhenClickableTargetStopsBeingClickable() {
        val expected = nodes[1]
        val current = expected.copy(clickable = false)

        assertFalse(NodeMatcher.matchesSnapshot(current = current, expected = expected))
    }

    @Test
    fun findsClickableNodeByContentDescription() {
        val result = NodeMatcher.findUniqueClickable(
            listOf(
                ScreenNode(
                    nodeId = "search",
                    text = null,
                    contentDescription = "搜索",
                    role = "ImageButton",
                    bounds = NodeBounds(0, 0, 80, 80),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                ),
            ),
            "搜索",
        )

        assertTrue(result is NodeMatch.Found)
        assertEquals("search", (result as NodeMatch.Found).node.nodeId)
    }

    @Test
    fun targetMatchingIgnoresWhitespaceAndPunctuation() {
        val result = NodeMatcher.findUniqueClickable(nodes, "确 定！")

        assertTrue(result is NodeMatch.Found)
        assertEquals("ok", (result as NodeMatch.Found).node.nodeId)
    }

    @Test
    fun emptyTargetReturnsNotFound() {
        val result = NodeMatcher.findUniqueClickable(nodes, "   ")

        assertTrue(result is NodeMatch.NotFound)
    }
}
