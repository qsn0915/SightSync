package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.AgentPlan
import com.sightsync.assistant.ai.AgentPlanStep
import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.NodeMatcher
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode

interface InAppNavigationResolver {
    val hasPendingClarification: Boolean
    suspend fun resolve(utterance: String): InAppNavigationResolution
    fun clear()
}

sealed interface InAppNavigationResolution {
    data object NotCommand : InAppNavigationResolution
    data class AskClarification(val spoken: String) : InAppNavigationResolution
    data class Ready(val targetLabel: String, val plan: AgentPlan) : InAppNavigationResolution
    data class Stopped(val spoken: String) : InAppNavigationResolution
}

class InAppNavigationCoordinator(
    private val screenContextProvider: ScreenContextProvider,
) : InAppNavigationResolver {
    private var pendingClarification: PendingClarification? = null

    override val hasPendingClarification: Boolean
        get() = pendingClarification != null

    override suspend fun resolve(utterance: String): InAppNavigationResolution {
        val pending = pendingClarification
        if (pending != null) {
            pendingClarification = null
            return resolvePending(pending, cleanTarget(utterance))
        }

        val parsedTarget = parseExplicitTarget(utterance)
            ?: return InAppNavigationResolution.NotCommand
        val target = cleanTarget(parsedTarget)
        if (target.codePointCount(0, target.length) > MAX_TARGET_CODE_POINTS) {
            return InAppNavigationResolution.Stopped("目标名称太长，请缩短后重新发出完整命令。")
        }

        val screen = screenContextProvider.collectForValidation()
        if (screen.packageName.isBlank()) {
            return InAppNavigationResolution.Stopped("无法确认当前应用，已停止页面导航。")
        }
        if (target.isBlank()) {
            pendingClarification = PendingClarification.Missing(screen.packageName)
            return InAppNavigationResolution.AskClarification("请说出要点击的具体控件名称。")
        }
        return resolveInitial(screen, target, utterance)
    }

    override fun clear() {
        pendingClarification = null
    }

    private fun resolveInitial(
        screen: ScreenContext,
        target: String,
        utterance: String,
    ): InAppNavigationResolution =
        when (val lookup = InAppNavigationTargetResolver.find(screen, target)) {
            is InAppNavigationLookup.Found -> ready(screen, lookup.candidate, utterance)
            InAppNavigationLookup.NotFound -> {
                pendingClarification = PendingClarification.Missing(screen.packageName)
                InAppNavigationResolution.AskClarification(
                    "当前页面没有找到$target。请说一个更具体的控件名称。",
                )
            }
            is InAppNavigationLookup.Ambiguous -> ambiguous(screen, lookup.candidates)
        }

    private fun ambiguous(
        screen: ScreenContext,
        candidates: List<InAppNavigationCandidate>,
    ): InAppNavigationResolution {
        val normalizedLabels = candidates.map { InAppNavigationTargetResolver.normalize(it.label) }
        if (normalizedLabels.toSet().size != candidates.size) {
            return InAppNavigationResolution.Stopped(
                "当前页面有多个同名控件，无法安全确定目标，请先进入更具体的页面。",
            )
        }
        pendingClarification = PendingClarification.Candidates(
            packageName = screen.packageName,
            candidates = candidates.map { candidate ->
                PendingCandidate(
                    expectedNode = candidate.node,
                    expectedLabelNode = candidate.labelNode,
                    label = candidate.label,
                )
            },
        )
        val labels = candidates.map { it.label }.distinct().joinToString("、")
        return InAppNavigationResolution.AskClarification(
            "我找到了多个候选：$labels。请说出更具体的名称。",
        )
    }

    private suspend fun resolvePending(
        pending: PendingClarification,
        clarification: String,
    ): InAppNavigationResolution {
        if (clarification.isBlank()) {
            return InAppNavigationResolution.Stopped("没有听到具体候选，已取消页面导航。")
        }
        if (clarification.codePointCount(0, clarification.length) > MAX_TARGET_CODE_POINTS) {
            return InAppNavigationResolution.Stopped("目标名称太长，已取消页面导航。")
        }
        val screen = screenContextProvider.collectForValidation()
        if (screen.packageName != pending.packageName) {
            return InAppNavigationResolution.Stopped("当前应用已变化，已取消页面导航。")
        }
        return when (pending) {
            is PendingClarification.Missing -> resolveMissingClarification(screen, clarification)
            is PendingClarification.Candidates -> resolveCandidateClarification(
                screen,
                pending.candidates,
                clarification,
            )
        }
    }

    private fun resolveMissingClarification(
        screen: ScreenContext,
        clarification: String,
    ): InAppNavigationResolution =
        when (val lookup = InAppNavigationTargetResolver.find(screen, clarification)) {
            is InAppNavigationLookup.Found -> ready(screen, lookup.candidate, clarification)
            InAppNavigationLookup.NotFound -> InAppNavigationResolution.Stopped(
                "仍然没有找到唯一控件，已取消页面导航。",
            )
            is InAppNavigationLookup.Ambiguous -> InAppNavigationResolution.Stopped(
                "仍然有多个候选，已取消页面导航。请重新说出完整命令。",
            )
        }

    private fun resolveCandidateClarification(
        screen: ScreenContext,
        candidates: List<PendingCandidate>,
        clarification: String,
    ): InAppNavigationResolution {
        val scored = candidates.mapNotNull { candidate ->
            val score = InAppNavigationTargetResolver.scoreLabel(candidate.label, clarification)
                ?: return@mapNotNull null
            candidate to score
        }
        val bestScore = scored.maxOfOrNull { it.second }
            ?: return InAppNavigationResolution.Stopped(
                "回答没有匹配原候选，已取消页面导航。",
            )
        val best = scored.filter { it.second == bestScore }
        if (best.size != 1) {
            return InAppNavigationResolution.Stopped(
                "仍然无法唯一确定候选，已取消页面导航。",
            )
        }

        val selected = best.single().first
        val currentNode = NodeMatcher.findById(screen.nodes, selected.expectedNode.nodeId)
        val currentLabelNode = NodeMatcher.findById(screen.nodes, selected.expectedLabelNode.nodeId)
        if (
            currentNode == null ||
            currentLabelNode == null ||
            !NodeMatcher.matchesSnapshot(currentNode, selected.expectedNode) ||
            !NodeMatcher.matchesSnapshot(currentLabelNode, selected.expectedLabelNode)
        ) {
            return InAppNavigationResolution.Stopped("页面候选已变化，已取消页面导航。")
        }
        return ready(
            screen = screen,
            candidate = InAppNavigationCandidate(
                node = currentNode,
                labelNode = currentLabelNode,
                label = selected.label,
                score = best.single().second,
            ),
            utterance = clarification,
        )
    }

    private fun ready(
        screen: ScreenContext,
        candidate: InAppNavigationCandidate,
        utterance: String,
    ): InAppNavigationResolution.Ready =
        InAppNavigationResolution.Ready(
            targetLabel = candidate.label,
            plan = AgentPlan(
                goal = utterance,
                maxDurationMillis = PLAN_TIMEOUT_MILLIS,
                maxConsecutiveFailures = 1,
                steps = listOf(
                    AgentPlanStep(
                        id = "navigate_click",
                        kind = "ACTION",
                        action = AssistantAction(type = "CLICK_NODE", nodeId = candidate.node.nodeId),
                        precondition = PageExpectation(
                            packageName = screen.packageName,
                            requiredNodeIds = listOf(candidate.node.nodeId),
                        ),
                        timeoutMillis = ACTION_TIMEOUT_MILLIS,
                        requiresConfirmation = false,
                    ),
                    AgentPlanStep(
                        id = "validate_navigation",
                        kind = "VALIDATE_PAGE",
                        action = null,
                        precondition = PageExpectation(packageName = screen.packageName),
                        timeoutMillis = VALIDATE_TIMEOUT_MILLIS,
                        requiresConfirmation = false,
                    ),
                ),
            ),
        )

    private fun parseExplicitTarget(utterance: String): String? {
        val normalized = utterance.trim()
        return listOf(controlPattern, currentPagePrefixPattern, actionBeforeCurrentPagePattern)
            .firstNotNullOfOrNull { pattern -> pattern.matchEntire(normalized)?.groupValues?.get(1) }
    }

    private fun cleanTarget(value: String): String =
        value.trim().trimEnd('。', '.', '！', '!', '？', '?').trim()

    private sealed interface PendingClarification {
        val packageName: String

        data class Missing(override val packageName: String) : PendingClarification

        data class Candidates(
            override val packageName: String,
            val candidates: List<PendingCandidate>,
        ) : PendingClarification
    }

    private data class PendingCandidate(
        val expectedNode: ScreenNode,
        val expectedLabelNode: ScreenNode,
        val label: String,
    )

    private companion object {
        const val MAX_TARGET_CODE_POINTS = 100
        const val PLAN_TIMEOUT_MILLIS = 10_000L
        const val ACTION_TIMEOUT_MILLIS = 8_000L
        const val VALIDATE_TIMEOUT_MILLIS = 2_000L
        val politePrefix = "(?:请帮我|麻烦你|你帮我|帮我|请)?\\s*"
        val controlPattern = Regex(
            "^$politePrefix(?:点击|点一下|点开|选择|按下)\\s*(.*)$",
            RegexOption.IGNORE_CASE,
        )
        val currentPagePrefixPattern = Regex(
            "^$politePrefix(?:在)?当前(?:页面|应用)(?:中|里)?\\s*(?:打开|进入|前往|找到)\\s*(.*)$",
            RegexOption.IGNORE_CASE,
        )
        val actionBeforeCurrentPagePattern = Regex(
            "^$politePrefix(?:打开|进入)\\s*当前(?:页面|应用)的?\\s*(.*)$",
            RegexOption.IGNORE_CASE,
        )
    }
}
