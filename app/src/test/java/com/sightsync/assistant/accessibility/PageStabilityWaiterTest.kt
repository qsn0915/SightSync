package com.sightsync.assistant.accessibility

import com.sightsync.assistant.ai.PageExpectation
import com.sightsync.assistant.core.NodeBounds
import com.sightsync.assistant.core.ScreenContext
import com.sightsync.assistant.core.ScreenContextProvider
import com.sightsync.assistant.core.ScreenNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageStabilityWaiterTest {
    @Test
    fun returnsOnlyAfterTwoConsecutiveMatchingFingerprints() = runTest {
        val transient = screenContext(packageName = "com.browser", text = "加载中")
        val stable = screenContext(packageName = "com.browser", text = "搜索")
        val provider = QueueScreenProvider(transient, stable, stable)
        val waiter = PageStabilityWaiter(provider, pollIntervalMillis = 100)

        val result = waiter.awaitStable(
            expectation = PageExpectation(
                packageName = "com.browser",
                requiredTexts = listOf("搜索"),
            ),
            timeoutMillis = 1_000,
        )

        assertEquals(PageStabilityResult.Stable(stable), result)
        assertEquals(3, provider.collectForValidationCount)
    }

    @Test
    fun timesOutWhenMatchingPageNeverStabilizes() = runTest {
        val first = screenContext(packageName = "com.browser", text = "第一帧")
        val second = screenContext(packageName = "com.browser", text = "第二帧")
        val provider = AlternatingScreenProvider(first, second)
        val waiter = PageStabilityWaiter(provider, pollIntervalMillis = 100)

        val result = waiter.awaitStable(
            expectation = PageExpectation(packageName = "com.browser"),
            timeoutMillis = 350,
        )

        assertEquals(PageStabilityResult.TimedOut, result)
        assertTrue(provider.collectForValidationCount >= 3)
    }

    @Test
    fun externalCancellationPropagatesInsteadOfReturningTimeout() = runTest {
        val provider = AlternatingScreenProvider(
            screenContext(text = "第一帧"),
            screenContext(text = "第二帧"),
        )
        val waiter = PageStabilityWaiter(provider, pollIntervalMillis = 100)
        val waiting = async {
            waiter.awaitStable(
                expectation = PageExpectation(packageName = "com.example"),
                timeoutMillis = 10_000,
            )
        }
        runCurrent()

        waiting.cancel()

        try {
            waiting.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            assertTrue(waiting.isCancelled)
        }
    }

    private fun screenContext(
        packageName: String = "com.example",
        text: String,
    ) = ScreenContext(
        packageName = packageName,
        activityName = "Activity",
        nodes = listOf(
            ScreenNode(
                nodeId = "node_1",
                text = text,
                contentDescription = null,
                role = "TextView",
                bounds = NodeBounds(0, 0, 100, 100),
                clickable = false,
                editable = false,
                scrollable = false,
            ),
        ),
        screenshotBase64 = null,
    )
}

private class QueueScreenProvider(
    vararg screens: ScreenContext,
) : ScreenContextProvider {
    private val pending = ArrayDeque(screens.toList())
    private val fallback = screens.last()
    var collectForValidationCount = 0

    override suspend fun collect(): ScreenContext = error("Validation waiter must not call collect()")

    override suspend fun collectForValidation(): ScreenContext {
        collectForValidationCount += 1
        return pending.removeFirstOrNull() ?: fallback
    }
}

private class AlternatingScreenProvider(
    private val first: ScreenContext,
    private val second: ScreenContext,
) : ScreenContextProvider {
    var collectForValidationCount = 0

    override suspend fun collect(): ScreenContext = error("Validation waiter must not call collect()")

    override suspend fun collectForValidation(): ScreenContext {
        val result = if (collectForValidationCount % 2 == 0) first else second
        collectForValidationCount += 1
        return result
    }
}
