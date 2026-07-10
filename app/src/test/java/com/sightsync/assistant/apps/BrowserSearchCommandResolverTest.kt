package com.sightsync.assistant.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSearchCommandResolverTest {
    @Test
    fun resolvesExplicitBrowserSearchWithDefaultBrowser() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            InstalledApp(label = "Firefox", packageName = "org.mozilla.firefox"),
            defaultBrowserPackage = "com.android.chrome",
        )

        val result = resolver.resolve("请用浏览器搜索无障碍新闻。")

        assertEquals(
            BrowserSearchCommandResult.Resolved(
                query = "无障碍新闻",
                browserPackage = "com.android.chrome",
            ),
            result,
        )
    }

    @Test
    fun supportsOpenBrowserAndQuerySynonyms() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )

        assertEquals(
            BrowserSearchCommandResult.Resolved("天气", "com.android.chrome"),
            resolver.resolve("打开默认浏览器，查找天气？"),
        )
        assertEquals(
            BrowserSearchCommandResult.Resolved("SightSync", "com.android.chrome"),
            resolver.resolve("使用浏览器查询一下 SightSync"),
        )
        assertEquals(
            BrowserSearchCommandResult.Resolved("测试", "com.android.chrome"),
            resolver.resolve("在浏览器中搜索测试"),
        )
    }

    @Test
    fun rejectsEmptyOrOversizedQueryWithoutOpeningBrowser() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )

        assertTrue(resolver.resolve("打开浏览器搜索。") is BrowserSearchCommandResult.Unavailable)
        assertTrue(
            resolver.resolve("用浏览器搜索${"无".repeat(201)}") is BrowserSearchCommandResult.Unavailable,
        )
    }

    @Test
    fun ignoresUtterancesWithoutExplicitBrowserSearchIntent() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            defaultBrowserPackage = "com.android.chrome",
        )

        listOf(
            "今天天气不错。",
            "搜索一下天气",
            "打开浏览器",
            "浏览器里有什么",
        ).forEach { utterance ->
            assertEquals(
                BrowserSearchCommandResult.NotBrowserSearchCommand,
                resolver.resolve(utterance),
            )
        }
    }

    @Test
    fun stopsWhenBrowserCandidateIsNotUnique() {
        val resolver = resolver(
            InstalledApp(label = "Chrome", packageName = "com.android.chrome"),
            InstalledApp(label = "Firefox", packageName = "org.mozilla.firefox"),
        )

        val result = resolver.resolve("用浏览器搜索天气")

        assertTrue(result is BrowserSearchCommandResult.Unavailable)
        assertTrue((result as BrowserSearchCommandResult.Unavailable).spoken.contains("多个应用"))
    }

    private fun resolver(
        vararg apps: InstalledApp,
        defaultBrowserPackage: String? = null,
    ): BrowserSearchCommandResolver =
        BrowserSearchCommandResolver(
            OpenAppCommandResolver(
                BrowserSearchFakeCatalogProvider(apps.toList(), defaultBrowserPackage),
            ),
        )
}

private class BrowserSearchFakeCatalogProvider(
    private val apps: List<InstalledApp>,
    private val defaultBrowserPackage: String?,
) : AppCatalogProvider {
    override fun installedApps(): List<InstalledApp> = apps

    override fun defaultBrowserPackage(): String? = defaultBrowserPackage
}
