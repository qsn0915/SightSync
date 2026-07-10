package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.ScreenContext

sealed interface PageExpectationMatch {
    data object Matched : PageExpectationMatch
    data class Mismatch(val reason: String) : PageExpectationMatch
}

object PageExpectationMatcher {
    fun match(expectation: PageExpectation, screen: ScreenContext): PageExpectationMatch {
        if (!expectation.packageName.isNullOrBlank() && expectation.packageName != screen.packageName) {
            return PageExpectationMatch.Mismatch("当前应用与计划不一致。")
        }
        if (!expectation.activityName.isNullOrBlank() && expectation.activityName != screen.activityName) {
            return PageExpectationMatch.Mismatch("当前页面与计划不一致。")
        }

        val nodeIds = screen.nodes.mapTo(mutableSetOf()) { it.nodeId }
        if (expectation.requiredNodeIds.filter { it.isNotBlank() }.any { it !in nodeIds }) {
            return PageExpectationMatch.Mismatch("页面缺少计划要求的控件。")
        }

        val visibleValues = screen.nodes.flatMap { node ->
            listOfNotNull(node.text, node.contentDescription, node.inputContext)
        }
        if (expectation.requiredTexts.filter { it.isNotBlank() }.any { required ->
                visibleValues.none { visible -> visible.contains(required.trim(), ignoreCase = true) }
            }
        ) {
            return PageExpectationMatch.Mismatch("页面缺少计划要求的文字。")
        }

        return PageExpectationMatch.Matched
    }
}
