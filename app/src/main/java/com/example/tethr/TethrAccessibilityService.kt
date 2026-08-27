package com.example.tethr

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TethrAccessibilityService : AccessibilityService() {

    private var activeTimeMs: Long = 0
    private var isInstagramActive = false
    private var overlayManager: OverlayManager? = null
    private var isGrayscaleActive = false
    private var popupsShown = 0

    private val installedKeyboards: Set<String> by lazy {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.inputMethodList.map { it.packageName }.toSet()
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (installedKeyboards.contains(pkg)) return true

        return pkg == "android"
            || pkg.contains("systemui", ignoreCase = true)
            || pkg.contains("sysui", ignoreCase = true)
            || pkg.contains("volume", ignoreCase = true)
            || pkg.contains("notification", ignoreCase = true)
            || pkg.contains("statusbar", ignoreCase = true)
            || pkg.contains("panel", ignoreCase = true)
            || pkg == packageName                          // Tethr itself
            || pkg.contains("inputmethod", ignoreCase = true)
            || pkg.contains(".ime.", ignoreCase = true)
            || pkg.contains("keyboard", ignoreCase = true)
            || pkg.contains("honeyboard", ignoreCase = true)
            || pkg.contains("bixbyvision", ignoreCase = true)
            || pkg.contains("accessibility", ignoreCase = true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeTrackerRunnable = object : Runnable {
        override fun run() {
            if (isInstagramActive) {
                activeTimeMs += 1000

                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
                    .apply()

                val isDemoMode = sharedPrefs.getBoolean("DEMO_MODE", false)
                
                // Get user-configured thresholds for normal mode
                val tier2StartMins = sharedPrefs.getInt("TIER2_START", 5)
                val tier3StartMins = sharedPrefs.getInt("TIER3_START", 15)
                
                val grayscaleThresholdMs = if (isDemoMode) 15_000L else tier2StartMins * 60_000L
                val firstPopupThresholdMs = if (isDemoMode) 20_000L else tier3StartMins * 60_000L
                val popupIntervalMs = if (isDemoMode) 5_000L else 5L * 60_000L // 5 seconds in demo, 5 mins in normal
                
                // 1. Grayscale Check
                if (activeTimeMs >= grayscaleThresholdMs && !isGrayscaleActive) {
                    overlayManager?.setSystemGrayscale(true)
                    isGrayscaleActive = true
                    Log.d(TAG, "Grayscale ON at threshold")
                }

                // 2. Popup Check
                if (activeTimeMs >= firstPopupThresholdMs) {
                    val timeSinceFirstPopup = activeTimeMs - firstPopupThresholdMs
                    val expectedPopups = (timeSinceFirstPopup / popupIntervalMs).toInt() + 1
                    
                    if (expectedPopups > popupsShown) {
                        popupsShown = expectedPopups
                        overlayManager?.showMathQuiz()
                        Log.d(TAG, "Showing Math Quiz popup #$popupsShown")
                    }
                }

                // Dispatch time metrics to the OverlayManager
                val effectiveTimeMs = if (isDemoMode) activeTimeMs * 60 else activeTimeMs
                overlayManager?.updateMetrics(activeTimeMs, isDemoMode)

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
                    isGrayscaleActive = false
                    popupsShown = 0
                    sharedPrefs.edit()
                        .putLong(KEY_ACTIVE_TIME_MS, 0)
                        .apply()
                } else {
                    // Resume state
                    activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
                    Log.d(TAG, "Resuming: ${activeTimeMs}ms")
                }

                overlayManager?.showOverlay()
                handler.post(timeTrackerRunnable)
            }
        } else if (!isTransientSystemPackage(packageName)) {
            // Identify if the user explicitly went to the home screen (Launcher).
            // We want the pill to vanish INSTANTLY on home screen presses, without checking physical windows.
            val isLauncher = packageName.contains("launcher", ignoreCase = true) || 
                             packageName == "com.miui.home" || 
                             packageName == "com.sec.android.app.launcher" ||
                             packageName.contains("bbk", ignoreCase = true)
                             
            // If it is NOT an explicit launcher exit, we check if Instagram is actually still physically visible underneath
            if (!isLauncher) {
                if (getVisibleTargetPackage() != null) {
                    Log.d(TAG, "Ignored false exit to $packageName; Instagram is still visible")
                    return
                }
            }

            if (isInstagramActive) {
                Log.d(TAG, "Instagram closed/backgrounded — cleaning up everything")
                isInstagramActive = false
                handler.removeCallbacks(timeTrackerRunnable)

                // Turn off grayscale immediately when leaving Instagram
                if (isGrayscaleActive) {
                    overlayManager?.setSystemGrayscale(false)
                    isGrayscaleActive = false
                }
                popupsShown = 0

                sharedPrefs.edit()
                    .putLong(KEY_LAST_CLOSED_TIME, currentTime)
                    .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
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
        overlayManager?.hideOverlay()
    }

    private fun getVisibleTargetPackage(): String? {
        try {
            val activeRootPkg = rootInActiveWindow?.packageName?.toString()
            if (activeRootPkg == "com.instagram.android") {
                return activeRootPkg
            }
        } catch (e: Exception) {}

        try {
            val currentWindows = windows
            if (currentWindows != null) {
                for (window in currentWindows) {
                    val rootPkg = try { window.root?.packageName?.toString() } catch (e: Exception) { null }
                    if (rootPkg == "com.instagram.android") {
                        return rootPkg
                    }
                }
            }
        } catch (e: Exception) {}
        
        return null
    }

    companion object {
        private const val TAG = "TethrAccessibility"
        private const val PREFS_NAME = "TethrPrefs"
        private const val KEY_ACTIVE_TIME_MS = "ACTIVE_TIME_MS"
        private const val KEY_LAST_CLOSED_TIME = "LAST_CLOSED_TIME"
    }
}

