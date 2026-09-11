package com.devassistant.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.devassistant.app.actions.ScreenObserver

/**
 * Bound Accessibility Service. This is the ONLY mechanism DEVANSH uses to read screen content and
 * perform taps / typing / scrolling / back-navigation in third-party apps. It does not read or
 * inject text into password fields (see isPasswordField checks below) and never attempts to
 * defeat login, OTP, CAPTCHA, or biometric prompts.
 */
class DevanshAccessibilityService : AccessibilityService() {

    companion object {
        // Single active instance the ActionExecutor talks to. Android guarantees at most one
        // instance of a given accessibility service runs at a time.
           var instance: DevanshAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event stream is available for future reactive features (e.g. detecting app crashes,
        // waiting for a screen transition). The agent currently polls the tree on demand instead.
    }

    override fun onInterrupt() {}

    fun currentForegroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun snapshotScreen(): Pair<String, List<ScreenObserver.ScreenElement>> {
        return ScreenObserver.describeScreen(rootInActiveWindow, currentForegroundPackage())
    }

    /** Clicks the element at [index] as produced by the most recent snapshotScreen() call. */
    fun clickElement(element: ScreenObserver.ScreenElement): Boolean {
        var node: AccessibilityNodeInfo? = element.node
        // Walk up to the nearest clickable ancestor if the leaf itself isn't clickable.
        while (node != null && !node.isClickable) {
            node = node.parent
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: run {
            // Fallback: tap the center of the element's bounds via a gesture.
            val bounds = Rect()
            element.node.getBoundsInScreen(bounds)
            tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
    }

    /** Types text into an editable field. Refuses password fields as a safety backstop. */
    fun typeIntoElement(element: ScreenObserver.ScreenElement, text: String): Boolean {
        if (isPasswordField(element.node)) return false
        val args = Bundle().apply {
               putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE_VALUE", text)
        }
        return element.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Submits/confirms the currently focused text field (e.g. after typing a search query),
     * simulating pressing Enter / the keyboard's search action. Falls back to clicking the
     * field itself (some search bars submit on a second tap of a "search" icon within them)
     * if the IME action isn't available on this Android version.
     */
    fun pressEnter(element: ScreenObserver.ScreenElement): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
               if (element.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }
        // Fallback for older Android versions or fields that ignore ACTION_IME_ENTER.
        return element.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        return node.isPassword
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun takeScreenshotGlobal(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    fun scroll(down: Boolean) {
        val display = resources.displayMetrics
        val startY = if (down) display.heightPixels * 0.75f else display.heightPixels * 0.25f
        val endY = if (down) display.heightPixels * 0.25f else display.heightPixels * 0.75f
        val x = display.widthPixels / 2f
        swipe(x, startY, x, endY, 300)
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
