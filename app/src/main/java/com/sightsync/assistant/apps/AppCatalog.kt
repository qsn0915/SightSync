package com.sightsync.assistant.apps

import android.content.Context
import android.content.Intent
import android.net.Uri

data class InstalledApp(
    val label: String,
    val packageName: String,
    val normalizedNames: Set<String> = buildNormalizedNames(label, packageName),
)

interface AppCatalogProvider {
    fun installedApps(): List<InstalledApp>
    fun defaultBrowserPackage(): String?
}

class PackageManagerAppCatalogProvider(
    context: Context,
) : AppCatalogProvider {
    private val appContext = context.applicationContext

    override fun installedApps(): List<InstalledApp> {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                InstalledApp(label = label, packageName = packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun defaultBrowserPackage(): String? {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return appContext.packageManager
            .resolveActivity(browserIntent, 0)
            ?.activityInfo
            ?.packageName
    }
}

fun normalizeAppName(value: String): String =
    value
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")
        .trim()

private fun buildNormalizedNames(label: String, packageName: String): Set<String> {
    val labelName = normalizeAppName(label)
    val packageTail = normalizeAppName(packageName.substringAfterLast('.'))
    val names = linkedSetOf(labelName, packageTail, normalizeAppName(packageName))

    if (isBrowserLike(label, packageName)) {
        names += "浏览器"
        names += "browser"
    }

    return names.filter { it.isNotBlank() }.toSet()
}

private fun isBrowserLike(label: String, packageName: String): Boolean {
    val value = "${label.lowercase()} ${packageName.lowercase()}"
    return listOf(
        "browser",
        "chrome",
        "firefox",
        "edge",
        "emmx",
        "miuibrowser",
        "ucmobile",
        "浏览器",
    ).any(value::contains)
}
