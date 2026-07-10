package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode

sealed interface WeChatNodeLookup {
    data class Found(val node: ScreenNode) : WeChatNodeLookup
    data class Missing(val reason: String) : WeChatNodeLookup
    data class Ambiguous(val reason: String) : WeChatNodeLookup
}

object WeChatDraftNodeLocator {
    fun findSearchTarget(screen: ScreenContext): WeChatNodeLookup {
        val editable = screen.nodes.filter { node -> node.editable && node.hasSearchMeaning() }
        chooseUnique(editable, SEARCH_AMBIGUOUS)?.let { return it }

        val nodesById = screen.nodes.associateBy(ScreenNode::nodeId)
        val clickable = screen.nodes
            .filter { node -> node.hasSearchMeaning() }
            .mapNotNull { node -> node.clickableTarget(nodesById) }
        return chooseUnique(clickable, SEARCH_AMBIGUOUS)
            ?: WeChatNodeLookup.Missing(SEARCH_MISSING)
    }

    fun findContact(screen: ScreenContext, contact: String): WeChatNodeLookup {
        val expected = normalize(contact)
        if (expected.isBlank()) return WeChatNodeLookup.Missing(CONTACT_MISSING)
        val nodesById = screen.nodes.associateBy(ScreenNode::nodeId)
        val candidates = screen.nodes
            .filter { node ->
                !node.editable && node.semanticValues().any { value -> normalize(value) == expected }
            }
            .mapNotNull { node -> node.clickableTarget(nodesById) }
        return chooseUnique(candidates, CONTACT_AMBIGUOUS)
            ?: WeChatNodeLookup.Missing(CONTACT_MISSING)
    }

    fun findMessageInput(screen: ScreenContext): WeChatNodeLookup {
        val editable = screen.nodes.filter(ScreenNode::editable)
        val semantic = editable.filter { node -> node.hasMessageInputMeaning() }
        chooseUnique(semantic, MESSAGE_INPUT_AMBIGUOUS)?.let { return it }
        val nonSearchEditable = editable.filterNot { node -> node.hasSearchMeaning() }
        return chooseUnique(nonSearchEditable, MESSAGE_INPUT_AMBIGUOUS)
            ?: WeChatNodeLookup.Missing(MESSAGE_INPUT_MISSING)
    }

    private fun ScreenNode.clickableTarget(nodesById: Map<String, ScreenNode>): ScreenNode? {
        var current: ScreenNode? = this
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.nodeId)) {
            if (current.clickable && !current.editable) return current
            current = current.parentNodeId?.let(nodesById::get)
        }
        return null
    }

    private fun ScreenNode.hasSearchMeaning(): Boolean =
        semanticValues().any { value -> searchKeywords.any(normalize(value)::contains) }

    private fun ScreenNode.hasMessageInputMeaning(): Boolean =
        semanticValues().any { value -> messageInputKeywords.any(normalize(value)::contains) }

    private fun ScreenNode.semanticValues(): List<String> =
        listOfNotNull(text, contentDescription, inputContext)

    private fun chooseUnique(
        candidates: List<ScreenNode>,
        ambiguousReason: String,
    ): WeChatNodeLookup? {
        val unique = candidates.distinctBy(ScreenNode::nodeId)
        return when (unique.size) {
            0 -> null
            1 -> WeChatNodeLookup.Found(unique.single())
            else -> WeChatNodeLookup.Ambiguous(ambiguousReason)
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(nonLettersOrNumbers, "")

    private val nonLettersOrNumbers = Regex("""[^\p{L}\p{N}]+""")
    private val searchKeywords = setOf("搜索", "search")
    private val messageInputKeywords = setOf("消息", "输入", "聊天", "message")

    private const val SEARCH_MISSING = "找不到唯一的微信搜索入口，已停止填写草稿。"
    private const val SEARCH_AMBIGUOUS = "找到多个微信搜索入口，无法确定目标，已停止填写草稿。"
    private const val CONTACT_MISSING = "找不到完全匹配的微信联系人，已停止填写草稿。"
    private const val CONTACT_AMBIGUOUS = "找到多个同名微信联系人，无法确定目标，已停止填写草稿。"
    private const val MESSAGE_INPUT_MISSING = "找不到唯一的微信消息输入框，已停止填写草稿。"
    private const val MESSAGE_INPUT_AMBIGUOUS = "找到多个微信输入框，无法确定消息输入位置，已停止填写草稿。"
}
