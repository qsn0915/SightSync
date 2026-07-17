package com.sightsync.assistant.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousUtteranceGateTest {
    private val gate = ContinuousUtteranceGate()

    @Test
    fun normalizesObservedReadScreenVariants() {
        assertEquals(
            ContinuousUtteranceDecision.Accepted(
                canonicalUtterance = "只说明可操作项",
                category = ContinuousCommandCategory.ScreenReading,
            ),
            gate.decide("只说明可操作性。"),
        )
        assertEquals(
            ContinuousUtteranceDecision.Accepted(
                canonicalUtterance = "详细读屏",
                category = ContinuousCommandCategory.ScreenReading,
            ),
            gate.decide("详细读拼音。"),
        )
        listOf("剪短读屏。", "剪断读屏。").forEach { observed ->
            assertEquals(
                ContinuousUtteranceDecision.Accepted(
                    canonicalUtterance = "简短读屏",
                    category = ContinuousCommandCategory.ScreenReading,
                ),
                gate.decide(observed),
            )
        }
    }

    @Test
    fun acceptsExistingExplicitCommandFamilies() {
        val commands = mapOf(
            "简短读屏。" to ContinuousCommandCategory.ScreenReading,
            "这里有什么？" to ContinuousCommandCategory.ScreenReading,
            "返回" to ContinuousCommandCategory.Navigation,
            "向下滚动" to ContinuousCommandCategory.Navigation,
            "点击确定" to ContinuousCommandCategory.Click,
            "输入：测试内容" to ContinuousCommandCategory.Input,
            "打开微信" to ContinuousCommandCategory.OpenApp,
        )

        commands.forEach { (utterance, expectedCategory) ->
            val decision = gate.decide(utterance)

            assertTrue("Expected accepted command: $utterance", decision is ContinuousUtteranceDecision.Accepted)
            assertEquals(expectedCategory, (decision as ContinuousUtteranceDecision.Accepted).category)
        }
    }

    @Test
    fun ignoresObservedAmbientUtterances() {
        val ambientUtterances = listOf(
            "嗯。",
            "真的假的？",
            "外面快飙疯了。",
            "我去这么贵。",
            "夜市给我刷一盘子红将。",
            "炮塔被摧毁。",
            "今天天气不错。",
            "这局马上结束。",
        )

        ambientUtterances.forEach { utterance ->
            assertEquals(
                "Expected ambient utterance to be ignored: $utterance",
                ContinuousUtteranceDecision.Ignored(reason = "not_explicit_command"),
                gate.decide(utterance),
            )
        }
    }
}
