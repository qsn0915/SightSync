package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppNavigationTargetResolverTest {
    @Test
    fun findsDirectClickableExactTarget() {
        val wlan = node(id = "node_wlan", text = "WLAN", clickable = true)

        val result = InAppNavigationTargetResolver.find(screen(wlan), "WLAN")

        assertEquals(
            InAppNavigationLookup.Found(
                InAppNavigationCandidate(
                    node = wlan,
                    labelNode = wlan,
                    label = "WLAN",
                    score = 100,
                ),
            ),
            result,
        )
    }

    @Test
    fun resolvesLabelChildToClosestClickableParent() {
        val row = node(id = "node_row", clickable = true)
        val label = node(id = "node_label", text = "WLAN", parentId = row.nodeId)

        val result = InAppNavigationTargetResolver.find(screen(row, label), "WLAN")

        assertEquals(
            InAppNavigationLookup.Found(
                InAppNavigationCandidate(row, label, "WLAN", 100),
            ),
            result,
        )
    }

    @Test
    fun exactMatchWinsOverContainingCandidate() {
        val exact = node(id = "node_exact", text = "WLAN", clickable = true)
        val containing = node(id = "node_help", text = "WLAN 帮助", clickable = true)

        val result = InAppNavigationTargetResolver.find(screen(exact, containing), "WLAN")

        assertEquals(
            InAppNavigationLookup.Found(InAppNavigationCandidate(exact, exact, "WLAN", 100)),
            result,
        )
    }

    @Test
    fun deduplicatesMultipleMatchingLabelsForSameClickableNode() {
        val row = node(id = "node_row", contentDescription = "WLAN", clickable = true)
        val label = node(id = "node_label", text = "WLAN", parentId = row.nodeId)

        val result = InAppNavigationTargetResolver.find(screen(row, label), "WLAN")

        assertTrue(result is InAppNavigationLookup.Found)
        assertEquals("node_row", (result as InAppNavigationLookup.Found).candidate.node.nodeId)
    }

    @Test
    fun returnsAmbiguousForTwoHighestScoreTargets() {
        val settings = node(id = "node_settings", text = "WLAN 设置", clickable = true)
        val help = node(id = "node_help", text = "WLAN 帮助", clickable = true)

        val result = InAppNavigationTargetResolver.find(screen(settings, help), "WLAN")

        assertTrue(result is InAppNavigationLookup.Ambiguous)
        assertEquals(
            setOf("node_settings", "node_help"),
            (result as InAppNavigationLookup.Ambiguous).candidates.map { it.node.nodeId }.toSet(),
        )
    }

    @Test
    fun ignoresLabelWithoutClickableAncestor() {
        val result = InAppNavigationTargetResolver.find(
            screen(node(id = "node_label", text = "WLAN")),
            "WLAN",
        )

        assertEquals(InAppNavigationLookup.NotFound, result)
    }

    @Test
    fun neverUsesEditableNodeOrCrossesEditableAncestor() {
        val outer = node(id = "node_outer", clickable = true)
        val input = node(
            id = "node_input",
            contentDescription = "WLAN",
            clickable = true,
            editable = true,
            parentId = outer.nodeId,
        )
        val label = node(id = "node_label", text = "WLAN", parentId = input.nodeId)

        val result = InAppNavigationTargetResolver.find(screen(outer, input, label), "WLAN")

        assertEquals(InAppNavigationLookup.NotFound, result)
    }

    private fun screen(vararg nodes: ScreenNode): ScreenContext =
        ScreenContext(
            packageName = "com.android.settings",
            activityName = "Settings",
            nodes = nodes.toList(),
            screenshotBase64 = null,
        )

    private fun node(
        id: String,
        text: String? = null,
        contentDescription: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        parentId: String? = null,
    ): ScreenNode =
        ScreenNode(
            nodeId = id,
            text = text,
            contentDescription = contentDescription,
            role = if (editable) "EditText" else "View",
            bounds = NodeBounds(0, 0, 100, 100),
            clickable = clickable,
            editable = editable,
            scrollable = false,
            parentNodeId = parentId,
        )
}
