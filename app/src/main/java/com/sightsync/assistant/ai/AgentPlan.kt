package com.sightsync.assistant.ai

import kotlinx.serialization.Serializable

@Serializable
data class AgentPlan(
    val goal: String,
    val maxDurationMillis: Long,
    val maxConsecutiveFailures: Int,
    val steps: List<AgentPlanStep>,
)

@Serializable
data class AgentPlanStep(
    val id: String,
    val kind: String,
    val action: AssistantAction? = null,
    val precondition: PageExpectation,
    val timeoutMillis: Long,
    val requiresConfirmation: Boolean,
)

@Serializable
data class PageExpectation(
    val packageName: String? = null,
    val activityName: String? = null,
    val requiredNodeIds: List<String> = emptyList(),
    val requiredTexts: List<String> = emptyList(),
)
