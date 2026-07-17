package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatDraftNodeLocatorTest {
    @Test
    fun findsUniqueEditableSearchField() {
        val search = node("search", description = "搜索", editable = true)

        assertEquals(
            WeChatNodeLookup.Found(search),
            WeChatDraftNodeLocator.findSearchTarget(screen(search)),
        )
    }

    @Test
    fun mapsSearchLabelToClickableParentAndDeduplicatesIt() {
        val parent = node("parent", clickable = true)
        val text = node("text", text = "搜索", parentId = parent.nodeId)
        val icon = node("icon", description = "Search", parentId = parent.nodeId)

        assertEquals(
            WeChatNodeLookup.Found(parent),
            WeChatDraftNodeLocator.findSearchTarget(screen(parent, text, icon)),
        )
    }

    @Test
    fun rejectsAmbiguousSearchTargets() {
        val result = WeChatDraftNodeLocator.findSearchTarget(
            screen(
                node("one", description = "搜索", clickable = true),
                node("two", description = "Search contacts", clickable = true),
            ),
        )

        assertTrue(result is WeChatNodeLookup.Ambiguous)
    }

    @Test
    fun findsUniqueExactContactThroughClickableParent() {
        val parent = node("contact", clickable = true)
        val label = node("label", text = "张三", parentId = parent.nodeId)

        assertEquals(
            WeChatNodeLookup.Found(parent),
            WeChatDraftNodeLocator.findContact(screen(parent, label), "张三"),
        )
    }

    @Test
    fun rejectsSameNameContacts() {
        val result = WeChatDraftNodeLocator.findContact(
            screen(
                node("one", text = "张三", clickable = true),
                node("two", description = "张三", clickable = true),
            ),
            "张三",
        )

        assertTrue(result is WeChatNodeLookup.Ambiguous)
    }

    @Test
    fun doesNotUsePartialContactMatch() {
        val result = WeChatDraftNodeLocator.findContact(
            screen(node("one", text = "张三的文件传输助手", clickable = true)),
            "张三",
        )

        assertTrue(result is WeChatNodeLookup.Missing)
    }

    @Test
    fun ignoresEditableSearchFieldWhenFindingContact() {
        val result = WeChatDraftNodeLocator.findContact(
            screen(node("search", text = "张三", editable = true, clickable = true)),
            "张三",
        )

        assertTrue(result is WeChatNodeLookup.Missing)
    }

    @Test
    fun findsSemanticMessageInput() {
        val input = node("message", description = "输入消息", editable = true)
        val unrelated = node("search", description = "搜索", editable = true)

        assertEquals(
            WeChatNodeLookup.Found(input),
            WeChatDraftNodeLocator.findMessageInput(screen(input, unrelated)),
        )
    }

    @Test
    fun fallsBackToOnlyEditableNode() {
        val input = node("message", role = "EditText", editable = true)

        assertEquals(
            WeChatNodeLookup.Found(input),
            WeChatDraftNodeLocator.findMessageInput(screen(input)),
        )
    }

    @Test
    fun neverUsesSearchFieldAsMessageInputFallback() {
        val result = WeChatDraftNodeLocator.findMessageInput(
            screen(node("search", description = "搜索", editable = true)),
        )

        assertTrue(result is WeChatNodeLookup.Missing)
    }

    @Test
    fun rejectsMultipleGenericEditableNodes() {
        val result = WeChatDraftNodeLocator.findMessageInput(
            screen(
                node("one", role = "EditText", editable = true),
                node("two", role = "EditText", editable = true),
            ),
        )

        assertTrue(result is WeChatNodeLookup.Ambiguous)
    }

    private fun screen(vararg nodes: ScreenNode): ScreenContext =
        ScreenContext(
            packageName = "com.tencent.mm",
            activityName = "LauncherUI",
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
