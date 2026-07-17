package com.sightsync.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SightSyncPackageBoundaryTest {
    @Test
    fun androidAppUsesSightSyncNamespaceAndApplicationId() {
        val gradleFile = File("build.gradle.kts").readText()

        assertTrue(gradleFile.contains("namespace = \"com.sightsync.assistant\""))
        assertTrue(gradleFile.contains("applicationId = \"com.sightsync.assistant\""))
    }

    @Test
    fun androidSourceDoesNotUsePreviousPackage() {
        val forbiddenPackage = listOf("com", "accessible" + "ai").joinToString(".")
        val sourceRoots = listOf(File("src/main/java"), File("src/test/java"))
        val remainingOldPackageReferences = sourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { it.extension in setOf("kt", "kts", "xml") }
            .filter { it.readText().contains(forbiddenPackage) }

        assertFalse(
            "Old Android package references remain: ${remainingOldPackageReferences.joinToString { it.path }}",
            remainingOldPackageReferences.isNotEmpty(),
        )
    }

    @Test
    fun proxyCredentialsAreNotHardcodedAndReleaseConfigIsValidated() {
        val gradleFile = File("build.gradle.kts").readText()

        assertFalse(gradleFile.contains("orElse(\"dev-token\")"))
        assertTrue(gradleFile.contains("validateReleaseProxyConfig"))
        assertTrue(gradleFile.contains("startsWith(\"https://\")"))
        assertTrue(gradleFile.contains("APP_API_TOKEN is required for release"))
    }
}
