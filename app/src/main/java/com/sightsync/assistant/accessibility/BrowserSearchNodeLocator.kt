package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode

sealed interface BrowserNodeLookup {
    data class Found(val node: ScreenNode) : BrowserNodeLookup
    data class Missing(val reason: String) : BrowserNodeLookup
    data class Ambiguous(val reason: String) : BrowserNodeLookup
}

object BrowserSearchNodeLocator {
    fun findAddressOrSearchField(screen: ScreenContext): BrowserNodeLookup {
        val editable = screen.nodes
            .filter { node -> node.editable && node.hasAddressMeaning() }
        chooseUnique(editable, ADDRESS_AMBIGUOUS_MESSAGE)?.let { return it }

        val nodesById = screen.nodes.associateBy { it.nodeId }
        val clickable = screen.nodes
            .filter { node -> node.hasAddressMeaning() }
            .mapNotNull { node -> node.clickableTarget(nodesById) }
        return chooseUnique(clickable, ADDRESS_AMBIGUOUS_MESSAGE)
            ?: BrowserNodeLookup.Missing(ADDRESS_MISSING_MESSAGE)
    }

    fun findSubmitTarget(screen: ScreenContext, query: String): BrowserNodeLookup {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return BrowserNodeLookup.Missing(SUBMIT_MISSING_MESSAGE)
        val nodesById = screen.nodes.associateBy { it.nodeId }

        val exactQuery = screen.nodes
            .filter { node -> !node.editable && node.semanticValues().any { normalize(it) == normalizedQuery } }
            .mapNotNull { node -> node.clickableTarget(nodesById) }
        chooseUnique(exactQuery, SUBMIT_AMBIGUOUS_MESSAGE)?.let { return it }

        val queryWithSearchMeaning = screen.nodes
            .filter { node ->
                !node.editable && node.semanticValues().any { value ->
                    val normalized = normalize(value)
                    normalized.contains(normalizedQuery) && containsSearchMeaning(normalized)
                }
            }
            .mapNotNull { node -> node.clickableTarget(nodesById) }
        chooseUnique(queryWithSearchMeaning, SUBMIT_AMBIGUOUS_MESSAGE)?.let { return it }

        val genericSubmit = screen.nodes
            .filter { node ->
                !node.editable && node.semanticValues().any { value -> normalize(value) in genericSubmitLabels }
            }
            .mapNotNull { node -> node.clickableTarget(nodesById) }
        return chooseUnique(genericSubmit, SUBMIT_AMBIGUOUS_MESSAGE)
            ?: BrowserNodeLookup.Missing(SUBMIT_MISSING_MESSAGE)
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

    private fun ScreenNode.hasAddressMeaning(): Boolean =
        semanticValues().any { value -> containsAddressMeaning(normalize(value)) }

    private fun ScreenNode.semanticValues(): List<String> =
        listOfNotNull(text, contentDescription, inputContext)

    private fun chooseUnique(
        candidates: List<ScreenNode>,
        ambiguousReason: String,
    ): BrowserNodeLookup? {
        val unique = candidates.distinctBy { it.nodeId }
        return when (unique.size) {
            0 -> null
            1 -> BrowserNodeLookup.Found(unique.single())
            else -> BrowserNodeLookup.Ambiguous(ambiguousReason)
        }
    }

    private fun containsAddressMeaning(normalized: String): Boolean =
        addressKeywords.any(normalized::contains)

    private fun containsSearchMeaning(normalized: String): Boolean =
        searchKeywords.any(normalized::contains)

    private fun normalize(value: String): String =
        value.lowercase().replace(nonLettersOrNumbers, "")

    private val nonLettersOrNumbers = Regex("""[^\p{L}\p{N}]+""")
    private val addressKeywords = setOf("搜索", "地址", "网址", "search", "address", "url")
    private val searchKeywords = setOf("搜索", "查找", "查询", "search")
    private val genericSubmitLabels = setOf("搜索", "查找", "查询", "前往", "search", "go")
    private const val ADDRESS_MISSING_MESSAGE = "找不到唯一的搜索或地址栏，已停止搜索。"
    private const val ADDRESS_AMBIGUOUS_MESSAGE = "找到多个搜索或地址栏，无法确定目标，已停止搜索。"
    private const val SUBMIT_MISSING_MESSAGE = "找不到唯一的搜索提交控件，已停止搜索。"
    private const val SUBMIT_AMBIGUOUS_MESSAGE = "找到多个搜索提交控件，无法确定目标，已停止搜索。"
}
