package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSearchNodeLocatorTest {
    @Test
    fun findsUniqueEditableAddressField() {
        val address = node(
            id = "node_1",
            description = "Search or type web address",
            role = "EditText",
            editable = true,
        )

        val result = BrowserSearchNodeLocator.findAddressOrSearchField(screen(address))

        assertEquals(BrowserNodeLookup.Found(address), result)
    }

    @Test
    fun fallsBackToUniqueClickableAddressControl() {
        val address = node(
            id = "node_2",
            text = "搜索或输入网址",
            clickable = true,
        )

        val result = BrowserSearchNodeLocator.findAddressOrSearchField(screen(address))

        assertEquals(BrowserNodeLookup.Found(address), result)
    }

    @Test
    fun rejectsAmbiguousAddressControls() {
        val result = BrowserSearchNodeLocator.findAddressOrSearchField(
            screen(
                node(id = "node_1", description = "Search address", editable = true),
                node(id = "node_2", description = "Search the web", editable = true),
            ),
        )

        assertTrue(result is BrowserNodeLookup.Ambiguous)
    }

    @Test
    fun ignoresUnlabelledGenericInput() {
        val result = BrowserSearchNodeLocator.findAddressOrSearchField(
            screen(node(id = "node_1", role = "EditText", editable = true)),
        )

        assertTrue(result is BrowserNodeLookup.Missing)
    }

    @Test
    fun findsDirectClickableSuggestionMatchingQuery() {
        val suggestion = node(id = "node_3", text = "无障碍新闻", clickable = true)

        val result = BrowserSearchNodeLocator.findSubmitTarget(
            screen(suggestion),
            query = "无障碍新闻",
        )

        assertEquals(BrowserNodeLookup.Found(suggestion), result)
    }

    @Test
    fun returnsClickableParentOfMatchingSuggestionLabel() {
        val parent = node(id = "node_4", clickable = true)
        val label = node(id = "node_5", text = "无障碍新闻", parentId = "node_4")

        val result = BrowserSearchNodeLocator.findSubmitTarget(
            screen(parent, label),
            query = "无障碍新闻",
        )

        assertEquals(BrowserNodeLookup.Found(parent), result)
    }

    @Test
    fun fallsBackToUniqueGenericSubmitControl() {
        val submit = node(id = "node_6", text = "Go", clickable = true)

        val result = BrowserSearchNodeLocator.findSubmitTarget(
            screen(submit),
            query = "无障碍新闻",
        )

        assertEquals(BrowserNodeLookup.Found(submit), result)
    }

    @Test
    fun rejectsMultipleExactSuggestions() {
        val result = BrowserSearchNodeLocator.findSubmitTarget(
            screen(
                node(id = "node_1", text = "无障碍新闻", clickable = true),
                node(id = "node_2", description = "无障碍新闻", clickable = true),
            ),
            query = "无障碍新闻",
        )

        assertTrue(result is BrowserNodeLookup.Ambiguous)
    }

    @Test
    fun neverUsesEditableAddressFieldAsSubmitTarget() {
        val result = BrowserSearchNodeLocator.findSubmitTarget(
            screen(
                node(
                    id = "node_1",
                    text = "无障碍新闻",
                    description = "Search or type web address",
                    editable = true,
                    clickable = true,
                ),
            ),
            query = "无障碍新闻",
        )

        assertTrue(result is BrowserNodeLookup.Missing)
    }

    @Test
    fun neverUsesClickableParentOfEditableAddressFieldAsSubmitTarget() {
        val result = BrowserSearchNodeLocator.findSubmitTarget(
            screen(
                node(id = "node_parent", clickable = true),
                node(
                    id = "node_input",
                    text = "无障碍新闻",
                    description = "Search or type web address",
                    editable = true,
                    parentId = "node_parent",
                ),
            ),
            query = "无障碍新闻",
        )

        assertTrue(result is BrowserNodeLookup.Missing)
    }

    private fun screen(vararg nodes: ScreenNode): ScreenContext =
        ScreenContext(
            packageName = "com.android.chrome",
            activityName = "Main",
            nodes = nodes.toList(),
            screenshotBase64 = null,
        )

    private fun node(
        id: String,
        text: String? = null,
        description: String? = null,
        role: String = "View",
        clickable: Boolean = false,
        editable: Boolean = false,
        parentId: String? = null,
    ): ScreenNode =
        ScreenNode(
            nodeId = id,
            text = text,
            contentDescription = description,
            role = role,
            bounds = NodeBounds(0, 0, 100, 100),
            clickable = clickable,
            editable = editable,
            scrollable = false,
            parentNodeId = parentId,
        )
}
