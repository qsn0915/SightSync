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
        if (isGenericBrowserTarget(normalizedTarget)) {
            val browserMatch = browserMatch(apps)
            if (browserMatch != null) return resolved(browserMatch)
        }

        val rankedMatches = apps.mapNotNull { app ->
            val score = app.normalizedNames
                .filterNot { name -> isCategoryOnlyName(name) && !isGenericBrowserTarget(normalizedTarget) }
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
            0 -> browserAlternatives(target, normalizedTarget, apps) ?: noMatch(target)
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

    private fun browserMatch(apps: List<InstalledApp>): InstalledApp? {
        val defaultBrowserPackage = appCatalogProvider.defaultBrowserPackage()
        if (!defaultBrowserPackage.isNullOrBlank()) {
            apps.firstOrNull { it.packageName == defaultBrowserPackage }?.let { return it }
        }
        return browserApps(apps)
            .takeIf { it.size == 1 }
            ?.single()
    }

    private fun browserAlternatives(
        target: String,
        normalizedTarget: String,
        apps: List<InstalledApp>,
    ): OpenAppCommandResult.Alternatives? {
        if (!isSpecificBrowserTarget(normalizedTarget)) return null
        val candidates = browserApps(apps)
        if (candidates.isEmpty()) return null

        val defaultBrowserPackage = appCatalogProvider.defaultBrowserPackage()
        val orderedCandidates = candidates.sortedWith(
            compareBy<InstalledApp> { app -> app.packageName != defaultBrowserPackage }
                .thenBy { app -> app.label.lowercase() },
        )
        val labels = orderedCandidates
            .map { app -> if (app.packageName == defaultBrowserPackage) "默认浏览器" else app.label }
            .distinct()
            .joinToString("、")
        val cleanTarget = cleanTargetForSpeech(target)
        return OpenAppCommandResult.Alternatives(
            target = cleanTarget,
            response = AssistResponse(
                spoken = "没有找到$cleanTarget。要打开${labels}吗？",
                requiresConfirmation = false,
                actions = emptyList(),
            ),
            candidatePackages = orderedCandidates.map { it.packageName }.toSet(),
        )
    }

    private fun browserApps(apps: List<InstalledApp>): List<InstalledApp> =
        apps.filter { app -> app.normalizedNames.any(::isCategoryOnlyName) }

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
        val cleanTarget = cleanTargetForSpeech(target)
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

    private fun cleanTargetForSpeech(target: String): String =
        target.trim().trimEnd('。', '.', '！', '!', '？', '?')

    private fun isGenericBrowserTarget(normalizedTarget: String): Boolean =
        normalizedTarget in genericBrowserNames

    private fun isSpecificBrowserTarget(normalizedTarget: String): Boolean =
        normalizedTarget.endsWith("浏览器") ||
            normalizedTarget.endsWith("browser") ||
            normalizedTarget in browserBrandNames

    private fun isCategoryOnlyName(normalizedName: String): Boolean =
        normalizedName in categoryOnlyNames

    private companion object {
        val genericBrowserNames = setOf(
            "浏览器",
            "默认浏览器",
            "browser",
            "网页",
            "上网",
        )
        val browserBrandNames = setOf(
            "chrome",
            "谷歌浏览器",
            "google浏览器",
            "googlechrome",
            "chrome浏览器",
            "谷歌",
            "google",
            "vivo",
            "vivo浏览器",
            "quark",
            "quark浏览器",
            "夸克",
            "夸克浏览器",
            "edge",
            "edge浏览器",
        )
        val categoryOnlyNames = setOf(
            "浏览器",
            "browser",
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
    data class Alternatives(
        val target: String,
        val response: AssistResponse,
        val candidatePackages: Set<String>,
    ) : OpenAppCommandResult
    data class NoMatch(val target: String, val response: AssistResponse) : OpenAppCommandResult
    data object NotOpenAppCommand : OpenAppCommandResult
}
