package com.sightsync.assistant.core

sealed class NodeMatch {
    data class Found(val node: ScreenNode) : NodeMatch()
    data class Ambiguous(val candidates: List<ScreenNode>) : NodeMatch()
    data object NotFound : NodeMatch()
}

object NodeMatcher {
    fun findById(nodes: List<ScreenNode>, nodeId: String?): ScreenNode? {
        if (nodeId.isNullOrBlank()) return null
        return nodes.firstOrNull { it.nodeId == nodeId }
    }

    fun findUniqueClickable(nodes: List<ScreenNode>, target: String): NodeMatch {
        val normalizedTarget = normalize(target)
        if (normalizedTarget.isBlank()) return NodeMatch.NotFound

        val candidates = nodes
            .filter { it.clickable }
            .filter { node ->
                listOfNotNull(node.text, node.contentDescription)
                    .map(::normalize)
                    .any { label -> label.contains(normalizedTarget) || normalizedTarget.contains(label) }
            }

        return when (candidates.size) {
            0 -> NodeMatch.NotFound
            1 -> NodeMatch.Found(candidates.first())
            else -> NodeMatch.Ambiguous(candidates)
        }
    }

    fun matchesSnapshot(current: ScreenNode, expected: ScreenNode): Boolean {
        if (current.nodeId != expected.nodeId) return false
        if (expected.clickable && !current.clickable) return false
        if (expected.editable && !current.editable) return false
        if (expected.scrollable && !current.scrollable) return false
        if (!boundsAreClose(current.bounds, expected.bounds)) return false
        if (expected.role != "Unknown" && current.role != "Unknown" && expected.role != current.role) {
            return false
        }

        val expectedLabels = listOfNotNull(expected.text, expected.contentDescription)
            .map(::normalize)
            .filter { it.isNotBlank() }
        if (expectedLabels.isEmpty()) return true

        val currentLabels = listOfNotNull(current.text, current.contentDescription)
            .map(::normalize)
            .filter { it.isNotBlank() }
        return expectedLabels.any { expectedLabel ->
            currentLabels.any { currentLabel ->
                currentLabel == expectedLabel ||
                    currentLabel.contains(expectedLabel) ||
                    expectedLabel.contains(currentLabel)
            }
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().filterNot { it.isWhitespace() || it in "，。！？、,.!?:" }

    private fun boundsAreClose(current: NodeBounds, expected: NodeBounds): Boolean {
        if (current.isEmpty() || expected.isEmpty()) return true
        return kotlin.math.abs(current.left - expected.left) <= MAX_BOUNDS_DELTA_PX &&
            kotlin.math.abs(current.top - expected.top) <= MAX_BOUNDS_DELTA_PX &&
            kotlin.math.abs(current.right - expected.right) <= MAX_BOUNDS_DELTA_PX &&
            kotlin.math.abs(current.bottom - expected.bottom) <= MAX_BOUNDS_DELTA_PX
    }

    private fun NodeBounds.isEmpty(): Boolean =
        left == 0 && top == 0 && right == 0 && bottom == 0

    private const val MAX_BOUNDS_DELTA_PX = 100
}
