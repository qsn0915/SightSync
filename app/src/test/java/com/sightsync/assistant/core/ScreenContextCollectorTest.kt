package com.sightsync.assistant.core

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextCollectorTest {
    @Test
    fun screenshotCallbackCopiesBeforeClosingBufferAndHandlesCancellation() {
        val source = File("src/main/java/com/sightsync/assistant/core/ScreenContextCollector.kt").readText()
        val copyIndex = source.indexOf("bitmap.copy")
        val closeIndex = source.indexOf("hardwareBuffer.close()")

        assertTrue(copyIndex >= 0)
        assertTrue(closeIndex > copyIndex)
        assertTrue(source.contains("if (!continuation.isActive)"))
        assertTrue(source.contains("copy?.recycle()"))
    }

    @Test
    fun richNodeTreeKeepsPackageAndActivityWithoutScreenshot() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                "encoded-image"
            },
        )
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(text = "设置", className = "android.widget.TextView"),
                ScreenNodeSnapshot(text = "WLAN", className = "android.widget.Button", clickable = true),
                ScreenNodeSnapshot(text = "蓝牙", className = "android.widget.Button", clickable = true),
            ),
        )

        val context = assembler.collectFrom(
            packageName = "com.android.settings",
            activityName = "Settings",
            root = root,
        )

        assertEquals("com.android.settings", context.packageName)
        assertEquals("Settings", context.activityName)
        assertEquals(listOf("设置", "WLAN", "蓝牙"), context.nodes.map { it.text })
        assertNull(context.screenshotBase64)
        assertEquals(false, context.screenshotPolicy?.attachScreenshot)
        assertEquals("node_tree_sufficient", context.screenshotPolicy?.reason)
        assertEquals(0, screenshotCalls)
    }

    @Test
    fun sparseNodeTreeAttachesScreenshotForAiUnderstanding() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                "encoded-screenshot"
            },
        )
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    className = "android.webkit.WebView",
                    scrollable = true,
                ),
            ),
        )

        val context = assembler.collectFrom(
            packageName = "com.android.browser",
            activityName = "Browser",
            root = root,
        )

        assertEquals("encoded-screenshot", context.screenshotBase64)
        assertEquals(true, context.screenshotPolicy?.attachScreenshot)
        assertEquals("sparse_node_tree", context.screenshotPolicy?.reason)
        assertEquals(1, screenshotCalls)
    }

    @Test
    fun collectorIncludesScreenshotPolicyAndBlocksScreenshotForSensitiveContent() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                "must-not-be-used"
            },
        )
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    text = "secret",
                    className = "android.widget.EditText",
                    editable = true,
                    password = true,
                ),
            ),
        )

        val context = assembler.collectFrom(
            packageName = "com.example.secure",
            activityName = "Password",
            root = root,
        )

        assertNull(context.screenshotBase64)
        assertEquals(0, screenshotCalls)
        assertEquals(false, context.screenshotPolicy?.attachScreenshot)
        assertEquals("privacy_sensitive_content", context.screenshotPolicy?.reason)
        assertEquals(true, context.screenshotPolicy?.privacyBlocked)
    }

    @Test
    fun missingRootReturnsMetadataWithoutCapturingUnknownScreen() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                null
            },
        )

        val context = assembler.collectFrom(
            packageName = "",
            activityName = "Unknown",
            root = null,
        )

        assertEquals("", context.packageName)
        assertEquals("Unknown", context.activityName)
        assertEquals(emptyList<ScreenNode>(), context.nodes)
        assertNull(context.screenshotBase64)
        assertEquals("unreliable_node_tree", context.screenshotPolicy?.reason)
        assertEquals(0, screenshotCalls)
    }

    @Test
    fun sensitiveSparseNodeTreeNeverAttachesScreenshot() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                "must-not-be-captured"
            },
        )
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    text = "123456",
                    contentDescription = "短信验证码",
                    className = "android.widget.EditText",
                    editable = true,
                ),
            ),
        )

        val context = assembler.collectFrom(
            packageName = "com.example.login",
            activityName = "Verify",
            root = root,
        )

        assertNull(context.screenshotBase64)
        assertEquals("[验证码已隐藏]", context.nodes.single().text)
        assertEquals("privacy_sensitive_content", context.screenshotPolicy?.reason)
        assertEquals(0, screenshotCalls)
    }

    @Test
    fun sensitivePackageNameNeverAttachesScreenshot() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                "must-not-be-captured"
            },
        )

        val context = assembler.collectFrom(
            packageName = "com.example.wallet",
            activityName = "Payment",
            root = ScreenNodeSnapshot(
                children = listOf(ScreenNodeSnapshot(className = "android.webkit.WebView", scrollable = true)),
            ),
        )

        assertNull(context.screenshotBase64)
        assertEquals("privacy_sensitive_context", context.screenshotPolicy?.reason)
        assertEquals(0, screenshotCalls)
    }

    @Test
    fun validationCollectionNeverRequestsScreenshotForSparsePage() = runTest {
        var screenshotCalls = 0
        val assembler = ScreenContextAssembler(
            nodeTreeExtractor = ScreenNodeTreeExtractor(),
            screenshotProvider = ScreenshotProvider {
                screenshotCalls += 1
                "must-not-be-used"
            },
        )
        val root = ScreenNodeSnapshot(
            children = listOf(
                ScreenNodeSnapshot(
                    className = "android.webkit.WebView",
                    scrollable = true,
                ),
            ),
        )

        val context = assembler.collectFrom(
            packageName = "com.android.browser",
            activityName = "Browser",
            root = root,
            allowScreenshot = false,
        )

        assertNull(context.screenshotBase64)
        assertEquals(0, screenshotCalls)
        assertEquals(1, context.nodes.size)
    }
}
