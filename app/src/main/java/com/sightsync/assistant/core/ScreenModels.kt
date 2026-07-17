package com.sightsync.assistant.core

import kotlinx.serialization.Serializable

@Serializable
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class ScreenNode(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val role: String,
    val bounds: NodeBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val parentNodeId: String? = null,
    val depth: Int = 0,
    val childIndex: Int = 0,
    val region: String? = null,
    val actionableType: String? = null,
    val scrollContainerNodeId: String? = null,
    val inputContext: String? = null,
    val privacySensitive: Boolean = false,
    val sensitive: Boolean = false,
)

@Serializable
data class ScreenshotPolicyDecision(
    val attachScreenshot: Boolean,
    val reason: String,
    val privacyBlocked: Boolean = false,
)

@Serializable
data class ScreenContext(
    val packageName: String,
    val activityName: String?,
    val nodes: List<ScreenNode>,
    val screenshotBase64: String?,
    val screenshotPolicy: ScreenshotPolicyDecision? = null,
)
