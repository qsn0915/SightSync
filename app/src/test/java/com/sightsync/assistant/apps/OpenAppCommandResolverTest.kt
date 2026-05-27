package com.sightsync.assistant.apps

import com.sightsync.assistant.ai.AssistantAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAppCommandResolverTest {
    @Test
    fun resolvesExplicitAppNameToOpenAppAction() {
        val resolver = resolver(
            InstalledApp(label = "设置", packageName = "com.android.settings"),
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
        )

        val result = resolver.resolve("帮我打开设置")

        val resolved = result as OpenAppCommandResult.Resolved
        assertEquals("我会打开设置。", resolved.response.spoken)
        assertEquals(
            listOf(AssistantAction(type = "OPEN_APP", appPackage = "com.android.settings")),
            resolved.response.actions,
        )
    }

    @Test
    fun prefersDefaultBrowserForBrowserCommand() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            InstalledApp(label = "Edge", packageName = "com.microsoft.emmx"),
            defaultBrowserPackage = "com.microsoft.emmx",
        )

        val result = resolver.resolve("打开浏览器")

        val resolved = result as OpenAppCommandResult.Resolved
        assertEquals("我会打开Edge。", resolved.response.spoken)
        assertEquals("com.microsoft.emmx", resolved.response.actions.single().appPackage)
    }

    @Test
    fun resolvesGoogleBrowserAliasesToBrowser() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )

        val aliases = listOf(
            "帮我打开谷歌浏览器",
            "打开 Google Chrome",
            "启动 Chrome 浏览器",
            "进入 google",
        )

        aliases.forEach { utterance ->
            val result = resolver.resolve(utterance)

            val resolved = result as OpenAppCommandResult.Resolved
            assertEquals("com.android.chrome", resolved.response.actions.single().appPackage)
        }
    }

    @Test
    fun resolvesBareTargetWhenClarificationIsPending() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )

        val result = resolver.resolve("谷歌浏览器", allowBareTarget = true)

        val resolved = result as OpenAppCommandResult.Resolved
        assertEquals("com.android.chrome", resolved.response.actions.single().appPackage)
    }

    @Test
    fun asksWhenMultipleAppsMatch() {
        val resolver = resolver(
            InstalledApp(label = "微信", packageName = "com.tencent.mm"),
            InstalledApp(label = "企业微信", packageName = "com.tencent.wework"),
        )

        val result = resolver.resolve("启动微信")

        val ambiguous = result as OpenAppCommandResult.Ambiguous
        assertTrue(ambiguous.response.spoken.contains("我找到了多个"))
        assertTrue(ambiguous.response.actions.isEmpty())
    }

    @Test
    fun returnsNoMatchWithLocalFailureForExplicitOpenCommand() {
        val resolver = resolver(
            InstalledApp(label = "设置", packageName = "com.android.settings"),
        )

        val result = resolver.resolve("打开不存在的应用")

        val noMatch = result as OpenAppCommandResult.NoMatch
        assertEquals("不存在的应用", noMatch.target)
        assertEquals("没有找到不存在的应用。", noMatch.response.spoken)
        assertTrue(noMatch.response.actions.isEmpty())
    }

    @Test
    fun ignoresNonOpenAppCommands() {
        val resolver = resolver(
            InstalledApp(label = "设置", packageName = "com.android.settings"),
        )

        assertEquals(OpenAppCommandResult.NotOpenAppCommand, resolver.resolve("点击确定"))
    }

    @Test
    fun normalizesChineseAndAsciiAppNames() {
        val resolver = resolver(
            InstalledApp(label = "Google Chrome 浏览器", packageName = "com.android.chrome"),
        )

        val result = resolver.resolve("帮我打开 chrome")

        val resolved = result as OpenAppCommandResult.Resolved
        assertEquals("com.android.chrome", resolved.response.actions.single().appPackage)
    }

    private fun resolver(
        vararg apps: InstalledApp,
        defaultBrowserPackage: String? = null,
    ): OpenAppCommandResolver =
        OpenAppCommandResolver(
            appCatalogProvider = FakeAppCatalogProvider(apps.toList(), defaultBrowserPackage),
        )
}

private class FakeAppCatalogProvider(
    private val apps: List<InstalledApp>,
    private val defaultBrowserPackage: String?,
) : AppCatalogProvider {
    override fun installedApps(): List<InstalledApp> = apps

    override fun defaultBrowserPackage(): String? = defaultBrowserPackage
}
