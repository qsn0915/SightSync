package com.sightsync.assistant

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MainActivityPhase1SourceTest {
    @Test
    fun launchScreenWiresPermissionEntryActions() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertTrue(source.contains("PermissionViewModel"))
        assertTrue(source.contains("ActivityResultContracts.RequestPermission"))
        assertTrue(source.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(source.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(source.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        assertTrue(source.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
    }

    @Test
    fun launchScreenKeepsSightSyncWelcomeAndPolishedPermissionLayout() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertTrue(source.contains("Hi，welcome to SightSync"))
        assertTrue(source.contains("WelcomeIntroScreen"))
        assertTrue(source.contains("SightSyncIntroTransition"))
        assertTrue(source.contains("delay(2000)"))
        assertTrue(source.contains("PermissionProgressStrip"))
        assertTrue(source.contains("HeroStatusPill"))
    }

    @Test
    fun permissionAndPrivacyCopyDescribeVisibleContinuousListening() {
        val source = File("src/main/java/com/sightsync/assistant/MainActivity.kt").readText()

        assertTrue(source.contains("连续聆听"))
        assertTrue(source.contains("系统通知保持可见"))
        assertFalse(source.contains("仅在你点击悬浮助手后采集一次语音请求。"))
        assertFalse(source.contains("语音只在你点击悬浮入口后采集一次。"))
    }
}
