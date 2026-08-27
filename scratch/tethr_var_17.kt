@"
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
import org.tensorflow.lite.task.text.nlclassifier.NLClassifier

class TethrAccessibilityService : AccessibilityService() {

    private var activeTimeMs: Long = 0
    private var reelCount: Int = 0
    private var isInstagramActive = false
    private var lastScrollTime: Long = 0
    private var overlayManager: OverlayManager? = null
    private var isGrayscaleActive = false
    private var isFrictionActive = false
    private var nlClassifier: NLClassifier? = null

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
                    overlayManager?.showTier2()
                    isGrayscaleActive = true
                    Log.d(TAG, "Grayscale ON at 5 minutes")
                }

                // Friction: activate at 10 minutes
                if (effectiveTimeMs >= tenMinutes) {
                    isFrictionActive = true
                }

                // Dispatch time and reel metrics to the OverlayManager
                overlayManager?.updateTime(activeTimeMs)

                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "TethrAccessibilityService Connected")
        overlayManager = OverlayManager(this)

        try {
            val options = NLClassifier.NLClassifierOptions.builder().build()
            nlClassifier = NLClassifier.createFromFileAndOptions(this, "text_classification_v2.tflite", options)
            Log.d(TAG, "NLClassifier loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NLClassifier", e)
        }

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

        // NLP Sentiment Analysis on keystrokes
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (isInstagramActive) {
                val text = event.text?.joinToString(" ")
                if (!text.isNullOrEmpty()) {
                    val results = nlClassifier?.classify(text)
                    val negScore = results?.find { it.label == "Negative" }?.score ?: 0f
                    if (negScore > 0.8f) {
                        Log.d(TAG, "Negative sentiment detected (" + negScore + "). Triggering Tier 2 Grayscale!")
                        overlayManager?.showTier2()
                        isGrayscaleActive = true
                    }
                }
            }
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
                Log.d(TAG, "Reel #" + reelCount + " counted")

                // Update pill immediately with new reel count
                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                overlayManager?.updateTime(activeTimeMs)

                // Friction: inject a counter-scroll to resist the swipe
                if (isFrictionActive) {
                    injectCounterScroll()
                }
            } else {
                Log.d(TAG, "Scrolled in Comment section, ignoring.")
            }
        }
    }

    private fun injectCounterScroll() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

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

        // Ignore known system and transient packages that pop up over Instagram
        if (packageName.contains("inputmethod", ignoreCase = true) ||
            packageName.contains("keyboard", ignoreCase = true) ||
            packageName == "com.samsung.android.honeyboard" ||
            packageName == "com.touchtype.swiftkey" ||
            packageName.contains("tethr", ignoreCase = true) ||
            packageName == "com.android.systemui") {
            return
        }

        if (packageName == "com.instagram.android") {
            if (!isInstagramActive) {
                Log.d(TAG, "Instagram opened")
                isInstagramActive = true

                // Cooldown Reset
                val lastClosedTime = sharedPrefs.getLong(KEY_LAST_CLOSED_TIME, 0)
                if (lastClosedTime > 0 && (currentTime - lastClosedTime) > 10 * 60 * 1000) {
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
                    activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
                    reelCount = sharedPrefs.getInt(KEY_REEL_COUNT, 0)
                    Log.d(TAG, "Resuming session")
                }

                overlayManager?.showTier1(activeTimeMs)
                handler.post(timeTrackerRunnable)
            }
        } else {
            if (isInstagramActive) {
                Log.d(TAG, "Instagram closed/backgrounded - cleaning up everything")
                isInstagramActive = false
                handler.removeCallbacks(timeTrackerRunnable)

                // Turn off grayscale immediately when leaving Instagram
                if (isGrayscaleActive) {
                    isGrayscaleActive = false
                }
                isFrictionActive = false

                sharedPrefs.edit()
                    .putLong(KEY_LAST_CLOSED_TIME, currentTime)
                    .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
                    .putInt(KEY_REEL_COUNT, reelCount)
                    .apply()

                overlayManager?.hideAll()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "TethrAccessibilityService Interrupted - cleaning up")
        isInstagramActive = false
        handler.removeCallbacks(timeTrackerRunnable)
        if (isGrayscaleActive) {
            isGrayscaleActive = false
        }
        isFrictionActive = false
        overlayManager?.hideAll()
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
"@ | Out-File -FilePath "app/src/main/java/com/example/tethr/TethrAccessibilityService.kt" -Encoding UTF8
