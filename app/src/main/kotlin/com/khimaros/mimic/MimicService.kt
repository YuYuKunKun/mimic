package com.khimaros.mimic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// the live accessibility connection. a process-wide singleton so CommandReceiver
// (same process) can call straight into it. holds no command state -- every view
// and interaction is computed fresh from the current window.
class MimicService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MimicService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---- view ----

    fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    // ---- interact ----

    fun tap(x: Int, y: Int, durationMs: Long): Boolean = dispatchPath(linePath(x, y, x, y), durationMs)

    fun longPress(x: Int, y: Int, durationMs: Long): Boolean = dispatchPath(linePath(x, y, x, y), durationMs)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        dispatchPath(linePath(x1, y1, x2, y2), durationMs)

    fun globalNav(nav: String): Boolean {
        val action = when (nav) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    // click a resolved node, climbing to a clickable ancestor and finally tapping
    // its center, so a non-clickable label inside a clickable row still works.
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        return tap(r.centerX(), r.centerY(), Defaults.TAP_DURATION_MS)
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---- screenshot ----

    // capture the default display and encode it. blocks (off the main thread) for
    // the framework callback. the system rate-limits this to about one per second.
    // requires android:canTakeScreenshot in the service config (api 30+).
    @SuppressLint("NewApi")
    fun captureScreenshot(format: String, quality: Int, scale: Double): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>(null)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    result.set(encode(screenshot, format, quality, scale))
                } finally {
                    latch.countDown()
                }
            }
            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(Defaults.SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    @SuppressLint("NewApi")
    private fun encode(shot: ScreenshotResult, format: String, quality: Int, scale: Double): ByteArray? {
        val buffer = shot.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace) ?: return null
            // hardware bitmaps cannot be read for compression; copy to software first.
            var bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            if (scale in 0.05..0.999) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
            }
            val out = ByteArrayOutputStream()
            val fmt = if (format == "jpeg" || format == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            bmp.compress(fmt, quality.coerceIn(1, 100), out)
            bmp.recycle()
            return out.toByteArray()
        } finally {
            buffer.close()
        }
    }

    // ---- gesture plumbing ----

    private fun linePath(x1: Int, y1: Int, x2: Int, y2: Int): Path = Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
    }

    // dispatch a gesture and block until the framework reports completion. the
    // caller runs on the receiver's async background thread, so the main-thread
    // result callback can fire and release the latch (no deadlock).
    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val started = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!started) return false
        latch.await(Defaults.GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return ok.get()
    }
}
