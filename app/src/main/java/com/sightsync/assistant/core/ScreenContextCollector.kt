package com.sightsync.assistant.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

interface ScreenContextProvider {
    suspend fun collect(): ScreenContext
    suspend fun collectForValidation(): ScreenContext
}

fun interface ScreenshotProvider {
    suspend fun takeScreenshotBase64(): String?
}

class ScreenContextCollector(
    private val service: AccessibilityService,
    private val nodeTreeExtractor: ScreenNodeTreeExtractor = ScreenNodeTreeExtractor(),
    private val screenshotProvider: ScreenshotProvider = AccessibilityScreenshotProvider(service),
) : ScreenContextProvider {
    override suspend fun collect(): ScreenContext = collect(allowScreenshot = true)

    override suspend fun collectForValidation(): ScreenContext = collect(allowScreenshot = false)

    private suspend fun collect(allowScreenshot: Boolean): ScreenContext {
        val activeWindow = service.findActiveWindow()
        val root = service.rootInActiveWindow ?: activeWindow?.root
        return ScreenContextAssembler(
            nodeTreeExtractor = nodeTreeExtractor,
            screenshotProvider = screenshotProvider,
        ).collectFrom(
            packageName = root?.packageName?.toString().orEmpty(),
            activityName = activeWindow?.title?.toString(),
            root = root?.let(::AccessibilityNodeSource),
            allowScreenshot = allowScreenshot,
        )
    }

    private fun AccessibilityService.findActiveWindow(): AccessibilityWindowInfo? {
        val availableWindows = try {
            windows
        } catch (_: SecurityException) {
            emptyList()
        }
        return availableWindows.firstOrNull { it.isActive }
            ?: availableWindows.firstOrNull { it.isFocused }
            ?: availableWindows.firstOrNull()
    }

}

internal class ScreenContextAssembler(
    private val nodeTreeExtractor: ScreenNodeTreeExtractor,
    private val screenshotProvider: ScreenshotProvider,
) {
    suspend fun collectFrom(
        packageName: String,
        activityName: String?,
        root: ScreenNodeSource?,
        allowScreenshot: Boolean = true,
    ): ScreenContext {
        val nodes = root?.let { nodeTreeExtractor.extract(it) }.orEmpty()
        val screenshotPolicy = ScreenContextPolicy.decideScreenshot(
            nodes = nodes,
            packageName = packageName,
            activityName = activityName,
            hasReliableNodeTree = root != null,
        )
        val screenshot = if (allowScreenshot && screenshotPolicy.attachScreenshot) {
            screenshotProvider.takeScreenshotBase64()
        } else {
            null
        }

        return ScreenContext(
            packageName = packageName,
            activityName = activityName,
            nodes = nodes,
            screenshotBase64 = screenshot,
            screenshotPolicy = screenshotPolicy,
        )
    }
}

private class AccessibilityNodeSource(
    private val node: AccessibilityNodeInfo,
) : ScreenNodeSource {
    override val text: String?
        get() = node.text?.toString()

    override val contentDescription: String?
        get() = node.contentDescription?.toString()

    override val className: String?
        get() = node.className?.toString()

    override val bounds: NodeBounds
        get() {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val clickable: Boolean
        get() = node.isClickable

    override val editable: Boolean
        get() = node.isEditable

    override val scrollable: Boolean
        get() = node.isScrollable

    override val password: Boolean
        get() = node.isPassword

    override val childCount: Int
        get() = node.childCount

    override fun childAt(index: Int): ScreenNodeSource? =
        node.getChild(index)?.let(::AccessibilityNodeSource)
}

private class AccessibilityScreenshotProvider(
    private val service: AccessibilityService,
) : ScreenshotProvider {
    override suspend fun takeScreenshotBase64(): String? {
        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        var bitmap: Bitmap? = null
                        var copy: Bitmap? = null
                        try {
                            if (!continuation.isActive) return
                            bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            if (bitmap == null) {
                                continuation.resume(null)
                                return
                            }
                            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            val output = ByteArrayOutputStream()
                            copy.compress(Bitmap.CompressFormat.JPEG, 45, output)
                            if (continuation.isActive) {
                                continuation.resume(Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                            }
                        } finally {
                            copy?.recycle()
                            bitmap?.recycle()
                            hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }
    }
}
