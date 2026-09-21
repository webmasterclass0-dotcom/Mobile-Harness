package com.jarves.mh.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AccessibilityService that gives the AI agent "hands" on the device.
 *
 * This is the only Android API that allows a non-root app to perform real
 * taps / swipes / text entry and to read the currently visible UI tree.
 * The service itself is dumb: it exposes small operations that the HTTP
 * control server ([PhoneControlServer]) translates into commands.
 */
class PhoneControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // All work happens on demand via the control server; nothing to do here.
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- gestures -------------------------------------------------------

    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        // A tiny 1px offset guarantees the path is well-defined for the gesture
        // engine while still reading as a plain tap / press.
        return dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    fun tap(x: Int, y: Int, durationMs: Long = 80L): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y + 1f)
        }
        return dispatchPath(path, durationMs)
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 600L): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y + 1f)
        }
        return dispatchPath(path, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchPath(path, durationMs)
    }

    // ---- reading the screen --------------------------------------------

    fun uiTree(maxDepth: Int = 24): JSONObject? {
        val root = rootInActiveWindow ?: return null
        val counters = intArrayOf(0)
        return JSONObject()
            .put("package", root.packageName ?: "")
            .put("root", buildNode(root, 0, maxDepth, counters))
    }

    private fun buildNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        counters: IntArray,
    ): JSONObject? {
        if (depth > maxDepth) return null
        if (counters[0] >= 600) return null // keep responses bounded
        counters[0]++

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null

        val entry = JSONObject()
            .put("text", node.text?.toString().orEmpty())
            .put("desc", node.contentDescription?.toString().orEmpty())
            .put("id", node.viewIdResourceName ?: "")
            .put("cls", node.className?.toString().orEmpty())
            .put("pkg", node.packageName?.toString().orEmpty())
            .put("x", bounds.left)
            .put("y", bounds.top)
            .put("w", bounds.width())
            .put("h", bounds.height())
            .put("clickable", node.isClickable)
            .put("scrollable", node.isScrollable)
            .put("editable", node.isEditable)
            .put("checked", node.isChecked)
            .put("visible", node.isVisibleToUser)

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                buildNode(child, depth + 1, maxDepth, counters)?.let { children.put(it) }
            }
        }
        if (children.length() > 0) entry.put("children", children)
        return entry
    }

    fun findNode(
        text: String? = null,
        resourceId: String? = null,
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val wantText = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wantId = resourceId?.trim()?.takeIf { it.isNotEmpty() }
        if (wantText == null && wantId == null) return null

        // Iterative DFS: deep hierarchies must not overflow the stack.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var fallback: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val matches = when {
                wantText != null -> {
                    val hay = listOf(
                        node.text?.toString(),
                        node.contentDescription?.toString(),
                    ).filterNotNull().joinToString(" ").lowercase()
                    hay.contains(wantText)
                }
                else -> {
                    val id = node.viewIdResourceName ?: ""
                    id == wantId || id.substringAfterLast('/', "").let { short ->
                        short.isNotEmpty() && wantId!!.contains(short)
                    }
                }
            }
            if (matches) {
                if (node.isVisibleToUser && !node.isAccessibilityFocused) return node
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return fallback
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return tap(bounds.centerX(), bounds.centerY())
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
        val target: AccessibilityNodeInfo? = focused ?: findEditableNode(root)
        if (target == null) return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isEditable && current.isVisibleToUser) return current
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    // ---- global actions -------------------------------------------------

    fun globalKey(name: String): Boolean = when (name.lowercase()) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else -> false
    }

    // ---- screenshots ----------------------------------------------------

    /**
     * Returns the current screen as a PNG (API 30+). On older devices this
     * returns null; the caller can fall back to the UI tree instead.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    fun screenshotPng(timeoutMs: Long = 6_000L): ByteArray? {
        val latch = CountDownLatch(1)
        var result: ByteArray? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        result = bitmap?.let {
                            ByteArrayOutputStream().use { out ->
                                it.compress(Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }
                        buffer.close()
                    } catch (_: Throwable) {
                        // Wrap failure surfaces through the latch; result stays null.
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    companion object {
        @Volatile
        var instance: PhoneControlService? = null
            private set

        fun isConnected(): Boolean = instance != null

        fun tap(x: Int, y: Int, durationMs: Long = 80L): Boolean =
            instance?.tap(x, y, durationMs) ?: false

        fun encodeBase64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}