package com.sightsync.assistant.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatDraftCommandResolverTest {
    private val resolver = WeChatDraftCommandResolver()

    @Test
    fun resolvesExplicitWeChatDraftPhrases() {
        val expected = WeChatDraftCommandResult.Resolved("张三", "明天上午十点见")

        assertEquals(expected, resolver.resolve("在微信给张三写草稿：明天上午十点见"))
        assertEquals(expected, resolver.resolve("在微信给张三准备消息：明天上午十点见"))
        assertEquals(expected, resolver.resolve("给张三写微信草稿：明天上午十点见"))
    }

    @Test
    fun trimsContactAndMessage() {
        assertEquals(
            WeChatDraftCommandResult.Resolved("张三", "你好"),
            resolver.resolve("在微信给  张三  写草稿：  你好  "),
        )
    }

    @Test
    fun ignoresUtterancesWithoutExplicitWeChatDraftIntent() {
        listOf(
            "打开微信",
            "给张三发消息",
            "在短信给张三写草稿：你好",
            "今天见到张三了",
        ).forEach { utterance ->
            assertEquals(WeChatDraftCommandResult.NotCommand, resolver.resolve(utterance))
        }
    }

    @Test
    fun rejectsMalformedExplicitCommandLocally() {
        listOf(
            "在微信给张三写草稿",
            "在微信给写草稿：你好",
            "在微信给张三写草稿：",
            "给张三写微信草稿",
        ).forEach { utterance ->
            assertTrue(resolver.resolve(utterance) is WeChatDraftCommandResult.Invalid)
        }
    }

    @Test
    fun rejectsOversizedContactOrMessage() {
        assertTrue(
            resolver.resolve("在微信给${"人".repeat(41)}写草稿：你好") is
                WeChatDraftCommandResult.Invalid,
        )
        assertTrue(
            resolver.resolve("在微信给张三写草稿：${"字".repeat(501)}") is
                WeChatDraftCommandResult.Invalid,
        )
    }
}
