package com.khimaros.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// the live accessibility connection. a process-wide singleton so CommandReceiver
// (same process) can call straight into it. holds no command state -- every view
// and interaction is computed fresh from the current window.
class A11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: A11yService? = null
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
