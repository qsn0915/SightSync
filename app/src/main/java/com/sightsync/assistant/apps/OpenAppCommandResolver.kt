package com.sightsync.assistant.apps

import com.sightsync.assistant.ai.AssistantAction
import com.sightsync.assistant.ai.AssistResponse

class OpenAppCommandResolver(
    private val appCatalogProvider: AppCatalogProvider,
) {
    fun resolve(
        utterance: String,
        allowBareTarget: Boolean = false,
        candidatePackages: Set<String>? = null,
    ): OpenAppCommandResult {
        val target = parseOpenAppTarget(utterance, allowBareTarget) ?: return OpenAppCommandResult.NotOpenAppCommand
        val normalizedTarget = normalizeAppName(target)
        if (normalizedTarget.isBlank()) return OpenAppCommandResult.NotOpenAppCommand

        val apps = appCatalogProvider.installedApps()
            .filter { app -> candidatePackages == null || app.packageName in candidatePackages }
        val browserMatch = browserMatch(normalizedTarget, apps)
        if (browserMatch != null) return resolved(browserMatch)

        val rankedMatches = apps.mapNotNull { app ->
            val score = app.normalizedNames
                .mapNotNull { name -> matchScore(name, normalizedTarget) }
                .maxOrNull()
                ?: return@mapNotNull null
            AppMatch(app, score)
        }
        val bestScore = rankedMatches.maxOfOrNull { it.score }
        val matches = rankedMatches
            .filter { it.score == bestScore }
            .map { it.app }

        return when (matches.size) {
            0 -> noMatch(target)
            1 -> resolved(matches.single())
            else -> ambiguous(matches)
        }
    }

    private fun matchScore(normalizedName: String, normalizedTarget: String): Int? =
        when {
            normalizedName == normalizedTarget -> 100
            normalizedName.startsWith(normalizedTarget) -> 90
            normalizedTarget.startsWith(normalizedName) -> 70
            normalizedName.contains(normalizedTarget) || normalizedTarget.contains(normalizedName) -> 50
            else -> null
        }

    private fun browserMatch(normalizedTarget: String, apps: List<InstalledApp>): InstalledApp? {
        if (normalizedTarget !in browserNames) return null
        if (normalizedTarget in chromeNames) {
            apps.filter { app -> "chrome" in app.normalizedNames }
                .takeIf { it.size == 1 }
                ?.single()
                ?.let { return it }
        }
        val defaultBrowserPackage = appCatalogProvider.defaultBrowserPackage()
        if (!defaultBrowserPackage.isNullOrBlank()) {
            apps.firstOrNull { it.packageName == defaultBrowserPackage }?.let { return it }
        }
        return apps.filter { app -> "浏览器" in app.normalizedNames || "browser" in app.normalizedNames }
            .takeIf { it.size == 1 }
            ?.single()
    }

    private fun resolved(app: InstalledApp): OpenAppCommandResult.Resolved =
        OpenAppCommandResult.Resolved(
            AssistResponse(
                spoken = "我会打开${app.label}。",
                requiresConfirmation = false,
                actions = listOf(
                    AssistantAction(
                        type = "OPEN_APP",
                        appPackage = app.packageName,
                    ),
                ),
            ),
        )

    private fun noMatch(target: String): OpenAppCommandResult.NoMatch {
        val cleanTarget = target.trim()
        return OpenAppCommandResult.NoMatch(
            target = cleanTarget,
            response = AssistResponse(
                spoken = "没有找到$cleanTarget。",
                requiresConfirmation = false,
                actions = emptyList(),
            ),
        )
    }

    private fun ambiguous(apps: List<InstalledApp>): OpenAppCommandResult.Ambiguous {
        val labels = apps.joinToString("、") { it.label }
        return OpenAppCommandResult.Ambiguous(
            response = AssistResponse(
                spoken = "我找到了多个应用：$labels。请说得更具体。",
                requiresConfirmation = false,
                actions = emptyList(),
            ),
            candidatePackages = apps.map { it.packageName }.toSet(),
        )
    }

    private fun parseOpenAppTarget(utterance: String, allowBareTarget: Boolean): String? {
        val normalized = utterance.trim()
        val match = openAppPattern.matchEntire(normalized) ?: return normalized.takeIf { allowBareTarget }
        return match.groupValues[1].trim()
    }

    private companion object {
        val browserNames = setOf(
            "浏览器",
            "browser",
            "chrome",
            "网页",
            "上网",
            "谷歌浏览器",
            "google浏览器",
            "googlechrome",
            "chrome浏览器",
            "谷歌",
            "google",
        )
        val chromeNames = setOf(
            "chrome",
            "谷歌浏览器",
            "google浏览器",
            "googlechrome",
            "chrome浏览器",
            "谷歌",
            "google",
        )
        val openAppPattern = Regex("""^(?:请|麻烦你|帮我|你帮我)?\s*(?:打开|启动|开启|进入)\s*(.+)$""")
    }

    private data class AppMatch(
        val app: InstalledApp,
        val score: Int,
    )
}

sealed interface OpenAppCommandResult {
    data class Resolved(val response: AssistResponse) : OpenAppCommandResult
    data class Ambiguous(
        val response: AssistResponse,
        val candidatePackages: Set<String>,
    ) : OpenAppCommandResult
    data class NoMatch(val target: String, val response: AssistResponse) : OpenAppCommandResult
    data object NotOpenAppCommand : OpenAppCommandResult
}
