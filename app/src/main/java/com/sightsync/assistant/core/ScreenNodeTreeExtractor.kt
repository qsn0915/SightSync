package com.sightsync.assistant.core

interface ScreenNodeSource {
    val text: String?
    val contentDescription: String?
    val className: String?
    val bounds: NodeBounds
    val clickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val password: Boolean
    val childCount: Int

    fun childAt(index: Int): ScreenNodeSource?
}

data class ScreenNodeSnapshot(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val className: String? = null,
    override val bounds: NodeBounds = NodeBounds(0, 0, 0, 0),
    override val clickable: Boolean = false,
    override val editable: Boolean = false,
    override val scrollable: Boolean = false,
    override val password: Boolean = false,
    val children: List<ScreenNodeSnapshot> = emptyList(),
) : ScreenNodeSource {
    override val childCount: Int
        get() = children.size

    override fun childAt(index: Int): ScreenNodeSource? = children.getOrNull(index)
}

class ScreenNodeTreeExtractor(
    private val maxNodes: Int = 200,
) {
    fun extract(root: ScreenNodeSource): List<ScreenNode> {
        val nodes = mutableListOf<ScreenNode>()
        val rootHeight = root.bounds.height().takeIf { it > 0 } ?: DEFAULT_SCREEN_HEIGHT

        fun visit(
            snapshot: ScreenNodeSource,
            depth: Int,
            childIndex: Int,
            parentNodeId: String?,
            nearestScrollContainerId: String?,
            previousSiblingLabel: String?,
        ): String? {
            if (nodes.size >= maxNodes) return null

            val role = snapshot.className?.substringAfterLast('.')?.ifBlank { null } ?: "Unknown"
            val rawText = snapshot.text.trimToNull()
            val rawDescription = snapshot.contentDescription.trimToNull()
            val text = SensitiveTextRedactor.redact(
                rawText,
                role = role,
                isPassword = snapshot.password,
            )
            val description = SensitiveTextRedactor.redact(
                rawDescription,
                role = role,
                isPassword = snapshot.password,
            )
            val privacySensitive = snapshot.password ||
                wasRedacted(rawText, text) ||
                wasRedacted(rawDescription, description)
            val hasUsefulContent = !text.isNullOrBlank() ||
                !description.isNullOrBlank() ||
                snapshot.clickable ||
                snapshot.editable ||
                snapshot.scrollable

            var currentNodeId: String? = null
            var currentScrollContainerId = nearestScrollContainerId
            if (hasUsefulContent) {
                currentNodeId = "node_${nodes.size}"
                val actionableType = when {
                    snapshot.editable -> "input"
                    snapshot.scrollable -> "scroll"
                    snapshot.clickable -> "click"
                    else -> null
                }
                nodes += ScreenNode(
                    nodeId = currentNodeId,
                    text = text,
                    contentDescription = description,
                    role = role,
                    bounds = snapshot.bounds,
                    clickable = snapshot.clickable,
                    editable = snapshot.editable,
                    scrollable = snapshot.scrollable,
                    parentNodeId = parentNodeId,
                    depth = depth,
                    childIndex = childIndex,
                    region = regionFor(snapshot.bounds, rootHeight),
                    actionableType = actionableType,
                    scrollContainerNodeId = nearestScrollContainerId,
                    inputContext = if (snapshot.editable) previousSiblingLabel ?: description ?: text else null,
                    privacySensitive = privacySensitive,
                )
                if (snapshot.scrollable) currentScrollContainerId = currentNodeId
            }

            var previousLabel: String? = null
            for (index in 0 until snapshot.childCount) {
                if (nodes.size >= maxNodes) return listOf(text, description).firstOrNull { !it.isNullOrBlank() }
                val child = snapshot.childAt(index) ?: continue
                val childLabel = visit(
                    snapshot = child,
                    depth = depth + 1,
                    childIndex = index,
                    parentNodeId = currentNodeId ?: parentNodeId,
                    nearestScrollContainerId = currentScrollContainerId,
                    previousSiblingLabel = previousLabel,
                )
                previousLabel = childLabel ?: previousLabel
            }
            return listOf(text, description).firstOrNull { !it.isNullOrBlank() }
        }

        visit(
            snapshot = root,
            depth = 0,
            childIndex = 0,
            parentNodeId = null,
            nearestScrollContainerId = null,
            previousSiblingLabel = null,
        )
        return nodes
    }

    private fun String?.trimToNull(): String? = this?.trim()?.ifBlank { null }

    private fun wasRedacted(raw: String?, redacted: String?): Boolean =
        raw != null && redacted != null && raw != redacted

    private fun regionFor(bounds: NodeBounds, rootHeight: Int): String? {
        if (bounds.bottom <= bounds.top || rootHeight <= 0) return null
        val centerY = (bounds.top + bounds.bottom) / 2
        return when {
            centerY < rootHeight / 3 -> "top"
            centerY < rootHeight * 2 / 3 -> "middle"
            else -> "bottom"
        }
    }

    private fun NodeBounds.height(): Int = bottom - top

    private companion object {
        const val DEFAULT_SCREEN_HEIGHT = 2400
    }
}

object ScreenContextPolicy {
    fun shouldAttachScreenshot(nodes: List<ScreenNode>): Boolean =
        decideScreenshot(nodes).attachScreenshot

    fun decideScreenshot(nodes: List<ScreenNode>): ScreenshotPolicyDecision {
        if (nodes.any { it.privacySensitive }) {
            return ScreenshotPolicyDecision(
                attachScreenshot = false,
                reason = "privacy_sensitive_content",
                privacyBlocked = true,
            )
        }
        if (nodes.isEmpty()) {
            return ScreenshotPolicyDecision(
                attachScreenshot = true,
                reason = "empty_node_tree",
            )
        }

        val labeledNodes = nodes.count { node ->
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        }

        return if (nodes.size < 3 || labeledNodes < 2) {
            ScreenshotPolicyDecision(
                attachScreenshot = true,
                reason = "sparse_node_tree",
            )
        } else {
            ScreenshotPolicyDecision(
                attachScreenshot = false,
                reason = "node_tree_sufficient",
            )
        }
    }
}
