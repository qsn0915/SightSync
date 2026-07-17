package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PageStabilityResult {
    data class Stable(val screen: ScreenContext) : PageStabilityResult
    data object TimedOut : PageStabilityResult
}

class PageStabilityWaiter(
    private val screenContextProvider: ScreenContextProvider,
    private val pollIntervalMillis: Long = 200L,
) {
    suspend fun awaitStable(
        expectation: PageExpectation?,
        timeoutMillis: Long,
    ): PageStabilityResult {
        val stable = withTimeoutOrNull(timeoutMillis) {
            var previousFingerprint: PageFingerprint? = null
            while (true) {
                val screen = screenContextProvider.collectForValidation()
                val matches = expectation == null ||
                    PageExpectationMatcher.match(expectation, screen) == PageExpectationMatch.Matched
                val fingerprint = screen.toFingerprint()
                if (matches && fingerprint == previousFingerprint) {
                    return@withTimeoutOrNull PageStabilityResult.Stable(screen)
                }
                previousFingerprint = fingerprint.takeIf { matches }
                delay(pollIntervalMillis)
            }
            @Suppress("UNREACHABLE_CODE")
            PageStabilityResult.TimedOut
        }
        return stable ?: PageStabilityResult.TimedOut
    }

    private fun ScreenContext.toFingerprint() = PageFingerprint(
        packageName = packageName,
        activityName = activityName,
        nodes = nodes,
    )

    private data class PageFingerprint(
        val packageName: String,
        val activityName: String?,
        val nodes: List<ScreenNode>,
    )
}
