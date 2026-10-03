package com.awaz.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "GestureAutomator"

/**
 * Internal automator class for simulated user input actions via AccessibilityService.
 * Constructed only by [AwazAccessibilityService].
 * Handles:
 * - Tapping elements by AccessibilityNodeInfo with ancestor traversal (max 3 levels) and coordinate gesture fallback
 * - Long pressing and double tapping
 * - Scrolling via node actions (ACTION_SCROLL_FORWARD/BACKWARD) with gesture swipe fallback
 * - System navigation button presses (BACK, HOME, RECENTS only)
 */
internal class GestureAutomator internal constructor(
    private val serviceProvider: () -> AccessibilityService?
) {

    internal constructor(service: AccessibilityService) : this({ service })

    private val service: AccessibilityService?
        get() = serviceProvider()

    // =========================================================================
    // TAP / CLICK ACTIONS
    // =========================================================================

    /**
     * Taps an element using its [AccessibilityNodeInfo].
     * 1. Attempts direct ACTION_CLICK on the node.
     * 2. If not clickable or unsuccessful, traverses up to at most 3 levels of ancestors.
     * 3. If node actions fail, falls back to a coordinate-based gesture tap at the center of the node's screen bounds.
     */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        // Step 1: Direct click
        try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Tapped node directly via ACTION_CLICK")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct ACTION_CLICK failed: ${e.message}")
        }

        // Step 2: Ancestor hierarchy traversal (at most 3 ancestors)
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 1
        while (current != null && depth <= 3) {
            try {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "Tapped clickable ancestor at depth $depth via ACTION_CLICK")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ancestor ACTION_CLICK at depth $depth failed: ${e.message}")
            } finally {
                val next = current?.parent
                current?.recycle()
                current = next
                depth++
            }
        }

        // Step 3: Fallback to simulated coordinate gesture tap
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()
            Log.d(TAG, "Falling back to gesture tap at coordinates ($centerX, $centerY)")
            return tapCoordinates(centerX, centerY)
        }

        return false
    }

    /**
     * Performs a single touch tap gesture at the specified screen coordinates.
     */
    fun tapCoordinates(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    /**
     * Coroutine-friendly tap that suspends until the gesture callback completes or times out.
     */
    suspend fun tapCoordinatesSync(x: Float, y: Float, durationMs: Long = 50L, timeoutMs: Long = 1000L): Boolean {
        val s = service ?: return false
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                s.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, null)
            }
        } ?: false
    }

    /**
     * Long presses an element using its [AccessibilityNodeInfo] or coordinate gesture fallback.
     */
    fun longPressNode(node: AccessibilityNodeInfo, durationMs: Long = 750L): Boolean {
        try {
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                Log.d(TAG, "Long pressed node via ACTION_LONG_CLICK")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_LONG_CLICK failed: ${e.message}")
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return longPressCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat(), durationMs)
        }
        return false
    }

    /**
     * Long presses at the specified screen coordinates.
     */
    fun longPressCoordinates(x: Float, y: Float, durationMs: Long = 750L): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    /**
     * Performs a double-tap gesture on the specified node.
     */
    fun doubleTapNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return doubleTapCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /**
     * Performs a double-tap gesture at the given coordinates.
     */
    fun doubleTapCoordinates(x: Float, y: Float): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path, 0, 50)
        val stroke2 = GestureDescription.StrokeDescription(path, 150, 50)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return s.dispatchGesture(gesture, null, null)
    }

    // =========================================================================
    // SCROLL ACTIONS
    // =========================================================================

    /**
     * Scrolls the screen in the given direction.
     * 1. Attempts node-level scroll action on targetNode or the first scrollable node in active window.
     * 2. If node scroll action fails or no scrollable node exists, falls back to a simulated gesture swipe.
     */
    fun scroll(direction: ScrollDirection, targetNode: AccessibilityNodeInfo? = null): Boolean {
        val action = when (direction) {
            ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        // 1. Try target node if provided
        if (targetNode != null) {
            try {
                if (targetNode.performAction(action)) {
                    Log.d(TAG, "Scrolled target node successfully via node action")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Target node scroll action failed: ${e.message}")
            }
        }

        // 2. Try first scrollable node in active window
        val root = service?.rootInActiveWindow
        if (root != null) {
            try {
                val scrollableNode = findFirstScrollableNode(root)
                if (scrollableNode != null) {
                    val result = scrollableNode.performAction(action)
                    scrollableNode.recycle()
                    if (result) {
                        Log.d(TAG, "Scrolled active scrollable node via ACTION_SCROLL")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error finding or scrolling scrollable node: ${e.message}")
            } finally {
                root.recycle()
            }
        }

        // 3. Fallback: Gesture swipe
        Log.d(TAG, "Falling back to gesture swipe for scroll direction: $direction")
        return performScrollSwipeFallback(direction, targetNode)
    }

    /**
     * Performs a gesture swipe simulating scroll forward (swipe up) or scroll backward (swipe down).
     */
    private fun performScrollSwipeFallback(direction: ScrollDirection, targetNode: AccessibilityNodeInfo?): Boolean {
        val bounds = Rect()
        if (targetNode != null) {
            targetNode.getBoundsInScreen(bounds)
        }

        if (bounds.isEmpty) {
            val root = service?.rootInActiveWindow
            if (root != null) {
                root.getBoundsInScreen(bounds)
                root.recycle()
            }
        }

        // Default screen bounds if unavailable
        val width = if (bounds.width() > 0) bounds.width() else 1080
        val height = if (bounds.height() > 0) bounds.height() else 2400
        val left = if (bounds.left >= 0) bounds.left else 0
        val top = if (bounds.top >= 0) bounds.top else 0

        val centerX = left + (width / 2f)
        val topY = top + (height * 0.25f)
        val bottomY = top + (height * 0.75f)

        return when (direction) {
            ScrollDirection.FORWARD -> {
                swipe(centerX, bottomY, centerX, topY, durationMs = 300L)
            }
            ScrollDirection.BACKWARD -> {
                swipe(centerX, topY, centerX, bottomY, durationMs = 300L)
            }
        }
    }

    /**
     * Performs a directional swipe gesture from start coordinates to end coordinates.
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
        val s = service ?: return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    /**
     * Recursively traverses an AccessibilityNodeInfo hierarchy to find the first node with isScrollable == true.
     */
    fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollableNode(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }

    // =========================================================================
    // SYSTEM BUTTON ACTIONS (BACK, HOME, RECENTS ONLY)
    // =========================================================================

    /**
     * Presses standard Android system navigation buttons: BACK, HOME, RECENTS only.
     */
    fun pressSystemButton(button: String): Boolean {
        return when (button.trim().uppercase(Locale.ROOT)) {
            "BACK" -> pressBack()
            "HOME" -> pressHome()
            "RECENTS" -> pressRecents()
            else -> {
                Log.w(TAG, "Unrecognized system button '$button'")
                false
            }
        }
    }

    fun pressBack(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }

    fun pressHome(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }

    fun pressRecents(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
    }
}
