package com.sightsync.assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyExplanationSourceTest {
    @Test
    fun mainActivityProvidesPhaseOnePrivacyExplanationPage() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertTrue(source.contains("PrivacyExplanationScreen"))
        assertTrue(source.contains("当前屏幕文本"))
        assertTrue(source.contains("截图"))
        assertTrue(source.contains("AI 服务处理"))
        assertTrue(source.contains("可启动应用名称"))
        assertTrue(source.contains("不会上传应用列表"))
    }
}
