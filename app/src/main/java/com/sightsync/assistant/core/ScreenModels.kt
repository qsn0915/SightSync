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
)

@Serializable
data class ScreenContext(
    val packageName: String,
    val activityName: String?,
    val nodes: List<ScreenNode>,
    val screenshotBase64: String?,
)
