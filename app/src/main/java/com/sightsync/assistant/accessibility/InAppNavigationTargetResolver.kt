package com.sightsync.assistant.accessibility

import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenNode

data class InAppNavigationCandidate(
    val node: ScreenNode,
    val labelNode: ScreenNode,
    val label: String,
    val score: Int,
)

sealed interface InAppNavigationLookup {
    data class Found(val candidate: InAppNavigationCandidate) : InAppNavigationLookup
    data class Ambiguous(val candidates: List<InAppNavigationCandidate>) : InAppNavigationLookup
    data object NotFound : InAppNavigationLookup
}

object InAppNavigationTargetResolver {
    fun find(screen: ScreenContext, target: String): InAppNavigationLookup {
        val normalizedTarget = normalize(target)
        if (normalizedTarget.isBlank()) return InAppNavigationLookup.NotFound
        val nodesById = screen.nodes.associateBy { it.nodeId }
        val candidates = screen.nodes.flatMap { labelNode ->
            labelNode.semanticValues().mapNotNull { label ->
                val score = scoreLabel(label, target) ?: return@mapNotNull null
                val clickable = labelNode.closestClickable(nodesById) ?: return@mapNotNull null
                InAppNavigationCandidate(
                    node = clickable,
                    labelNode = labelNode,
                    label = label.trim(),
                    score = score,
                )
            }
        }
        val bestPerNode = candidates
            .groupBy { it.node.nodeId }
            .map { (_, values) ->
                values.sortedWith(
                    compareByDescending<InAppNavigationCandidate> { it.score }
                        .thenBy { normalize(it.label).length }
                        .thenBy { it.label.lowercase() },
                ).first()
            }
        val bestScore = bestPerNode.maxOfOrNull { it.score }
            ?: return InAppNavigationLookup.NotFound
        val best = bestPerNode
            .filter { it.score == bestScore }
            .sortedWith(compareBy<InAppNavigationCandidate> { it.label.lowercase() }.thenBy { it.node.nodeId })
        return if (best.size == 1) {
            InAppNavigationLookup.Found(best.single())
        } else {
            InAppNavigationLookup.Ambiguous(best)
        }
    }

    internal fun scoreLabel(label: String, target: String): Int? {
        val normalizedLabel = normalize(label)
        val normalizedTarget = normalize(target)
        if (normalizedLabel.isBlank() || normalizedTarget.isBlank()) return null
        return when {
            normalizedLabel == normalizedTarget -> 100
            normalizedLabel.startsWith(normalizedTarget) || normalizedTarget.startsWith(normalizedLabel) -> 80
            normalizedLabel.contains(normalizedTarget) || normalizedTarget.contains(normalizedLabel) -> 60
            else -> null
        }
    }

    internal fun normalize(value: String): String =
        value.lowercase().replace(nonLettersOrNumbers, "")

    private fun ScreenNode.semanticValues(): List<String> =
        listOfNotNull(text, contentDescription, inputContext)
            .map(String::trim)
            .filter(String::isNotBlank)

    private fun ScreenNode.closestClickable(nodesById: Map<String, ScreenNode>): ScreenNode? {
        var current: ScreenNode? = this
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.nodeId)) {
            if (current.editable) return null
            if (current.clickable) return current
            current = current.parentNodeId?.let(nodesById::get)
        }
        return null
    }

    private val nonLettersOrNumbers = Regex("""[^\p{L}\p{N}]+""")
}
