package com.sightsync.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiServiceConnectionUiSourceTest {
    @Test
    fun permissionGuideShowsAiServiceConnectionConfigUi() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertTrue(source.contains("AiServiceConnectionCard"))
        assertTrue(source.contains("OutlinedTextField"))
        assertTrue(source.contains("AI 服务连接"))
        assertTrue(source.contains("代理地址"))
        assertTrue(source.contains("App token"))
        assertTrue(source.contains("保存配置"))
        assertTrue(source.contains("测试连接"))
        assertTrue(source.contains("当前连接状态"))
    }

    @Test
    fun aiConnectionUiDoesNotAskForProviderApiKey() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertFalse(source.contains("Provider API key"))
        assertFalse(source.contains("QWEN_API_KEY"))
        assertFalse(source.contains("DASHSCOPE_API_KEY"))
        assertFalse(source.contains("AI_API_KEY"))
    }

    @Test
    fun aiConnectionTestUsesBackendHealthTester() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertTrue(source.contains("AiServiceConnectionHealthTester"))
        assertFalse(source.contains("PendingHealthCheckConnectionTester"))
    }
}
