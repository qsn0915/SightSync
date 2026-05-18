package com.sightsync.assistant.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.sightsync.assistant.accessibility.ActionRunner
import com.sightsync.assistant.ai.AssistantAction

data class ActionResult(
    val success: Boolean,
    val message: String,
    val requiresScreenRefresh: Boolean = false,
)

class ActionExecutor(private val service: AccessibilityService) : ActionRunner {
    override fun execute(
        actions: List<AssistantAction>,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): List<ActionResult> =
        actions.map { executeOne(it, confirmed, sourceScreen) }

    private fun executeOne(
        action: AssistantAction,
        confirmed: Boolean,
        sourceScreen: ScreenContext,
    ): ActionResult {
        return when (action.type) {
            "SPEAK" -> ActionResult(true, "已朗读。")
            "CLICK_NODE" -> clickNode(action.nodeId, sourceScreen)
            "SET_TEXT" -> setText(action.nodeId, action.text.orEmpty(), sourceScreen)
            "SCROLL_FORWARD" -> scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "SCROLL_BACKWARD" -> scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "GLOBAL_BACK" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "已返回。")
            "GLOBAL_HOME" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "已回到主页。")
            "OPEN_APP" -> openApp(action.appPackage)
            else -> ActionResult(false, "不支持的动作：${action.type}")
        }
    }

    private fun clickNode(nodeId: String?, sourceScreen: ScreenContext): ActionResult {
        val lookup = findCurrentNode(nodeId, sourceScreen, requireSnapshotMatch = true)
        if (lookup.failure != null) return lookup.failure
        val node = lookup.node ?: return pageChanged()
        if (!node.isClickable) return ActionResult(false, "目标控件当前不可点击。")
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (clicked) ActionResult(true, "已点击。") else ActionResult(false, "这个控件无法点击。")
    }

    private fun setText(nodeId: String?, text: String, sourceScreen: ScreenContext): ActionResult {
        val lookup = findCurrentNode(nodeId, sourceScreen, requireSnapshotMatch = true)
        if (lookup.failure != null) return lookup.failure
        val node = lookup.node ?: return pageChanged()
        if (!node.isEditable) return ActionResult(false, "目标输入框当前不可输入。")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (success) ActionResult(true, "已输入。") else ActionResult(false, "这个输入框无法输入。")
    }

    private fun scroll(action: Int): ActionResult {
        val root = service.rootInActiveWindow ?: return ActionResult(false, "无法读取当前页面。")
        val scrollable = findFirst(root) { it.isScrollable }
        val success = scrollable?.performAction(action) == true
        return if (success) ActionResult(true, "已滚动。") else ActionResult(false, "当前页面不能继续滚动。")
    }

    private fun global(action: Int, message: String): ActionResult =
        if (service.performGlobalAction(action)) ActionResult(true, message) else ActionResult(false, "系统动作执行失败。")

    private fun openApp(packageName: String?): ActionResult {
        if (packageName.isNullOrBlank()) return ActionResult(false, "缺少应用包名。")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "找不到这个应用。")
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "已打开应用。")
    }

    private fun findCurrentNode(
        nodeId: String?,
        sourceScreen: ScreenContext,
        requireSnapshotMatch: Boolean,
    ): NodeLookup {
        if (nodeId.isNullOrBlank()) return NodeLookup(failure = pageChanged())
        val targetIndex = nodeId.removePrefix("node_").toIntOrNull() ?: return NodeLookup(failure = pageChanged())
        val root = service.rootInActiveWindow ?: return NodeLookup(failure = ActionResult(false, "无法读取当前页面。"))
        val currentPackage = root.packageName?.toString().orEmpty()
        if (sourceScreen.packageName.isNotBlank() && currentPackage != sourceScreen.packageName) {
            return NodeLookup(failure = pageChanged())
        }

        val expected = NodeMatcher.findById(sourceScreen.nodes, nodeId) ?: return NodeLookup(failure = pageChanged())
        var current = -1
        val node = findFirst(root) { node ->
            val hasUsefulContent = !node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank() ||
                node.isClickable ||
                node.isEditable ||
                node.isScrollable
            if (hasUsefulContent) current += 1
            current == targetIndex
        } ?: return NodeLookup(failure = pageChanged())

        if (requireSnapshotMatch && !NodeMatcher.matchesSnapshot(node.toScreenNode(nodeId), expected)) {
            return NodeLookup(failure = pageChanged())
        }

        return NodeLookup(node = node)
    }

    private fun findFirst(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val match = findFirst(node.getChild(index), predicate)
            if (match != null) return match
        }
        return null
    }

    private fun AccessibilityNodeInfo.toScreenNode(nodeId: String): ScreenNode {
        val rect = Rect()
        getBoundsInScreen(rect)
        val role = className?.toString()?.substringAfterLast('.')?.ifBlank { null } ?: "Unknown"
        return ScreenNode(
            nodeId = nodeId,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            role = role,
            bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = isClickable,
            editable = isEditable,
            scrollable = isScrollable,
        )
    }

    private fun pageChanged(): ActionResult =
        ActionResult(
            success = false,
            message = "页面已变化，我已重新查看当前屏幕，请再说一次。",
            requiresScreenRefresh = true,
        )

    private data class NodeLookup(
        val node: AccessibilityNodeInfo? = null,
        val failure: ActionResult? = null,
    )
}
