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

        fun visit(snapshot: ScreenNodeSource) {
            if (nodes.size >= maxNodes) return

            val role = snapshot.className?.substringAfterLast('.')?.ifBlank { null } ?: "Unknown"
            val rawText = snapshot.text.trimToNull()
            val rawDescription = snapshot.contentDescription.trimToNull()
            val text = SensitiveTextRedactor.redact(
                rawText,
                role = role,
                isPassword = snapshot.password,
                context = rawDescription,
            )
            val description = SensitiveTextRedactor.redact(
                rawDescription,
                role = role,
                isPassword = snapshot.password,
                context = rawText,
            )
            val sensitive = SensitiveTextRedactor.isSensitive(
                value = rawText,
                role = role,
                isPassword = snapshot.password,
                context = rawDescription,
            )
            val hasUsefulContent = !text.isNullOrBlank() ||
                !description.isNullOrBlank() ||
                snapshot.clickable ||
                snapshot.editable ||
                snapshot.scrollable

            if (hasUsefulContent) {
                nodes += ScreenNode(
                    nodeId = "node_${nodes.size}",
                    text = text,
                    contentDescription = description,
                    role = role,
                    bounds = snapshot.bounds,
                    clickable = snapshot.clickable,
                    editable = snapshot.editable,
                    scrollable = snapshot.scrollable,
                    sensitive = sensitive,
                )
            }

            for (index in 0 until snapshot.childCount) {
                if (nodes.size >= maxNodes) return
                snapshot.childAt(index)?.let(::visit)
            }
        }

        visit(root)
        return nodes
    }

    private fun String?.trimToNull(): String? = this?.trim()?.ifBlank { null }
}

object ScreenContextPolicy {
    private val sensitiveContextKeywords = listOf(
        "支付",
        "付款",
        "转账",
        "密码",
        "验证码",
        "wallet",
        "payment",
        "checkout",
        "bank",
        "login",
        "auth",
    )

    fun shouldAttachScreenshot(
        nodes: List<ScreenNode>,
        packageName: String = "",
        activityName: String? = null,
        hasReliableNodeTree: Boolean = true,
    ): Boolean {
        if (!hasReliableNodeTree) return false
        if (nodes.any(ScreenNode::sensitive)) return false

        val metadata = "$packageName ${activityName.orEmpty()}".lowercase()
        if (sensitiveContextKeywords.any(metadata::contains)) return false
        if (nodes.isEmpty()) return true

        val labeledNodes = nodes.count { node ->
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        }

        return nodes.size < 3 || labeledNodes < 2
    }
}
