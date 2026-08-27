package com.example.tethr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TethrAccessibilityService : AccessibilityService() {

    private var activeTimeMs: Long = 0
    private var reelCount: Int = 0
    private var isInstagramActive = false
    private var lastScrollTime: Long = 0
    private var overlayManager: OverlayManager? = null
    private var isGrayscaleActive = false
    private var isFrictionActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val timeTrackerRunnable = object : Runnable {
        override fun run() {
            if (isInstagramActive) {
                activeTimeMs += 1000

                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
                    .putInt(KEY_REEL_COUNT, reelCount)
                    .apply()

                val isDemoMode = sharedPrefs.getBoolean("DEMO_MODE", false)
                val effectiveTimeMs = if (isDemoMode) activeTimeMs * 60 else activeTimeMs

                val fiveMinutes = 5L * 60 * 1000
                val tenMinutes = 10L * 60 * 1000

                // Grayscale: hard toggle at 5 minutes
                if (effectiveTimeMs >= fiveMinutes && !isGrayscaleActive) {
                    overlayManager?.setSystemGrayscale(true)
                    isGrayscaleActive = true
                    Log.d(TAG, "Grayscale ON at 5 minutes")
                }

                // Friction: activate at 10 minutes
                if (effectiveTimeMs >= tenMinutes) {
                    isFrictionActive = true
                }

                // Dispatch time and reel metrics to the OverlayManager
                overlayManager?.updateMetrics(activeTimeMs, reelCount, isDemoMode)

                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "TethrAccessibilityService Connected")
        overlayManager = OverlayManager(this)

        val intent = Intent(this, TethrForegroundService::class.java)
        startForegroundService(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Handle Window State Changed (Detect active app)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChange(packageName)
        }

        // Only process scroll events if we are in Instagram
        if (packageName != "com.instagram.android") return

        val rootNode = rootInActiveWindow
        var isCommentSectionVisible = false
        if (rootNode != null) {
            isCommentSectionVisible = checkIfCommentSection(rootNode)
        }

        // Handle Scroll (Debounced)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()
            // Debounce: ignore events within 500ms
            if (currentTime - lastScrollTime < 500) {
                return
            }

            if (!isCommentSectionVisible) {
                lastScrollTime = currentTime
                reelCount += 1
                Log.d(TAG, "Reel #$reelCount counted")

                // Update pill immediately with new reel count
                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val isDemoMode = sharedPrefs.getBoolean("DEMO_MODE", false)
                overlayManager?.updateMetrics(activeTimeMs, reelCount, isDemoMode)

                // Friction: inject a counter-scroll to resist the swipe
                if (isFrictionActive) {
                    injectCounterScroll()
                }
            } else {
                Log.d(TAG, "Scrolled in Comment section, ignoring.")
            }
        }
    }

    /**
     * Safe friction: uses AccessibilityService.dispatchGesture() to inject
     * a partial scroll in the OPPOSITE direction after the user swipes.
     * This creates physical resistance without ever blocking touches.
     */
    private fun injectCounterScroll() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        // Swipe upward (counter the user's downward scroll on Reels)
        // Start from the middle, move upward by ~40% of screen height
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.4f
        val endY = screenHeight * 0.75f

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 50, 150))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Counter-scroll injected (friction)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Counter-scroll cancelled")
            }
        }, null)
    }

    private fun handleWindowStateChange(packageName: String) {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()

        if (packageName == "com.instagram.android") {
            if (!isInstagramActive) {
                Log.d(TAG, "Instagram opened")
                isInstagramActive = true

                // Cooldown Reset
                val lastClosedTime = sharedPrefs.getLong(KEY_LAST_CLOSED_TIME, 0)
                if (lastClosedTime > 0 && (currentTime - lastClosedTime) > 10 * 60 * 1000) {
                    // Over 10 minutes away — reset everything
                    Log.d(TAG, "Over 10 minutes passed. Resetting all state.")
                    activeTimeMs = 0
                    reelCount = 0
                    isGrayscaleActive = false
                    isFrictionActive = false
                    sharedPrefs.edit()
                        .putLong(KEY_ACTIVE_TIME_MS, 0)
                        .putInt(KEY_REEL_COUNT, 0)
                        .apply()
                } else {
                    // Resume state
                    activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
                    reelCount = sharedPrefs.getInt(KEY_REEL_COUNT, 0)
                    Log.d(TAG, "Resuming: ${activeTimeMs}ms, $reelCount reels")
                }

                overlayManager?.showOverlay()
                handler.post(timeTrackerRunnable)
            }
        } else {
            if (isInstagramActive) {
                Log.d(TAG, "Instagram closed/backgrounded — cleaning up everything")
                isInstagramActive = false
                handler.removeCallbacks(timeTrackerRunnable)

                // Turn off grayscale immediately when leaving Instagram
                if (isGrayscaleActive) {
                    overlayManager?.setSystemGrayscale(false)
                    isGrayscaleActive = false
                }
                isFrictionActive = false

                sharedPrefs.edit()
                    .putLong(KEY_LAST_CLOSED_TIME, currentTime)
                    .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
                    .putInt(KEY_REEL_COUNT, reelCount)
                    .apply()

                overlayManager?.hideOverlay()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "TethrAccessibilityService Interrupted — cleaning up")
        isInstagramActive = false
        handler.removeCallbacks(timeTrackerRunnable)
        if (isGrayscaleActive) {
            overlayManager?.setSystemGrayscale(false)
            isGrayscaleActive = false
        }
        isFrictionActive = false
        overlayManager?.hideOverlay()
    }

    private fun checkIfCommentSection(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.text != null && (node.text.toString().contains("Add a comment", ignoreCase = true) ||
            node.text.toString().contains("What do you think of this?", ignoreCase = true))) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (checkIfCommentSection(node.getChild(i))) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "TethrAccessibility"
        private const val PREFS_NAME = "TethrPrefs"
        private const val KEY_ACTIVE_TIME_MS = "ACTIVE_TIME_MS"
        private const val KEY_LAST_CLOSED_TIME = "LAST_CLOSED_TIME"
        private const val KEY_REEL_COUNT = "REEL_COUNT"
    }
}
