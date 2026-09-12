package com.example.tethr

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.example.tethr.billing.SupporterStore

class TethrAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var currentTimerPillBg = "default"
    private var currentTimerPillImageUri: String? = null

    private var activeTimeMs: Long = 0
    private var isTargetAppActive = false
    private var overlayManager: OverlayManager? = null
    private var isGrayscaleActive = false
    private var popupsShown = 0
    private var lastDemoModeState = false

    private val installedKeyboards: Set<String> by lazy {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.inputMethodList.map { it.packageName }.toSet()
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (installedKeyboards.contains(pkg)) return true

        return pkg == "android"
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
            if (isTargetAppActive) {
                // Self-healing failsafe: Catch race conditions where events are missed but the app is gone
                val activeRootPkg = rootInActiveWindow?.packageName?.toString()
                if (activeRootPkg != null) {
                    val isLauncher = activeRootPkg.contains("launcher", ignoreCase = true) || 
                                     activeRootPkg == "com.miui.home" || 
                                     activeRootPkg == "com.sec.android.app.launcher" ||
                                     activeRootPkg.contains("bbk", ignoreCase = true) ||
                                     activeRootPkg == "com.google.android.apps.nexuslauncher"
                    
                    if (isLauncher) {
                        Log.d(TAG, "Self-healing: Detected Launcher on screen. Cleaning up.")
                        forceCleanup()
                        return
                    }
                    
                    // Also self-heal if they somehow ended up in another normal app (e.g. Chrome)
                    if (!TARGET_PACKAGES.contains(activeRootPkg) && !isTransientSystemPackage(activeRootPkg) && !activeRootPkg.contains("systemui", ignoreCase = true)) {
                        Log.d(TAG, "Self-healing: Detected another app ($activeRootPkg). Cleaning up.")
                        forceCleanup()
                        return
                    }
                }

                handler.postDelayed(this, 1000) // Ensure the loop continues
                
                if (overlayManager?.isBarrierShowing == true) {
                    return // Pause time tracking while a barrier is showing
                }

                activeTimeMs += 1000

                val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
                    .apply()

                val repo = com.example.tethr.data.SessionRepository(this@TethrAccessibilityService)
                val isDemoMode = repo.isDemoMode()
                
                if (isDemoMode && !lastDemoModeState) {
                    activeTimeMs = 0
                    isGrayscaleActive = false
                    popupsShown = 0
                    overlayManager?.setSystemGrayscale(false)
                    overlayManager?.hideBarrier()
                    Log.d(TAG, "Demo mode toggled ON, reset active timer to 0")
                }
                lastDemoModeState = isDemoMode
                
                // Get dynamic thresholds based on usage history (punishment factor)
                val grayscaleThresholdMs = if (isDemoMode) 15_000L else repo.computeTriggerTime()
                
                // 1. Grayscale Check
                if (activeTimeMs >= grayscaleThresholdMs && !isGrayscaleActive) {
                    overlayManager?.setSystemGrayscale(true)
                    isGrayscaleActive = true
                    Log.d(TAG, "Grayscale ON at threshold")
                } else if (activeTimeMs < grayscaleThresholdMs && isGrayscaleActive) {
                    overlayManager?.setSystemGrayscale(false)
                    isGrayscaleActive = false
                    Log.d(TAG, "Grayscale OFF because active time is now below threshold (e.g. demo mode disabled)")
                }

                // 2. Popup Check
                if (activeTimeMs >= grayscaleThresholdMs) {
                    val timeSinceGrayscale = activeTimeMs - grayscaleThresholdMs
                    val expectedPopups = if (isDemoMode) {
                        (timeSinceGrayscale / 5_000L).toInt()
                    } else {
                        when {
                            timeSinceGrayscale < 300_000L -> 0 // Before 5 mins
                            timeSinceGrayscale < 450_000L -> 1 // 5 mins -> 1st popup
                            timeSinceGrayscale < 510_000L -> 2 // +2.5 mins -> 2nd popup
                            timeSinceGrayscale < 540_000L -> 3 // +1 min -> 3rd popup
                            else -> 4 + ((timeSinceGrayscale - 540_000L) / 30_000L).toInt() // +30s repeating
                        }
                    }
                    
                    if (expectedPopups > popupsShown) {
                        popupsShown++ // strict increment to ensure sequence
                        overlayManager?.showBarrier(popupsShown)
                        Log.d(TAG, "Showing barrier popup #$popupsShown")
                    }
                } else if (activeTimeMs < grayscaleThresholdMs && popupsShown > 0) {
                    overlayManager?.hideBarrier()
                    popupsShown = 0
                    Log.d(TAG, "Hiding barriers because active time is now below threshold")
                }

                // Dispatch time metrics to the OverlayManager
                val effectiveTimeMs = if (isDemoMode) activeTimeMs * 60 else activeTimeMs
                overlayManager?.updateMetrics(activeTimeMs, isDemoMode, currentTimerPillBg, currentTimerPillImageUri)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "TethrAccessibilityService Connected")
        overlayManager = OverlayManager(this)

        val supporterStore = SupporterStore(this)
        serviceScope.launch {
            supporterStore.timerPillBg.collect { bg ->
                currentTimerPillBg = bg
            }
        }
        serviceScope.launch {
            supporterStore.timerPillImageUri.collect { uri ->
                currentTimerPillImageUri = uri
            }
        }

        val intent = Intent(this, TethrForegroundService::class.java)
        startForegroundService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Handle Window State Changed (Detect active app)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChange(event, packageName)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (TARGET_PACKAGES.contains(packageName) && !isTargetAppActive) {
                // Avoid reviving the pill during an exit animation to the homescreen by strictly checking the root active window
                val activeRootPkg = rootInActiveWindow?.packageName?.toString()
                if (activeRootPkg == packageName) {
                    // Wait 300ms to allow any rapid exit animations to finish settling.
                    // If the user rapidly closed the app, the root window will change within this 300ms.
                    handler.postDelayed({
                        val doubleCheckPkg = rootInActiveWindow?.packageName?.toString()
                        if (doubleCheckPkg == packageName && !isTargetAppActive) {
                            Log.d(TAG, "Recovered active state via content change for $packageName")
                            handleWindowStateChange(event, packageName)
                        }
                    }, 300)
                }
            }
        }
    }

    private fun forceCleanup() {
        if (!isTargetAppActive) return
        Log.d(TAG, "Target app closed/backgrounded — cleaning up everything")
        isTargetAppActive = false
        handler.removeCallbacks(timeTrackerRunnable)

        val currentTime = System.currentTimeMillis()
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putLong(KEY_LAST_CLOSED_TIME, currentTime)
            .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
            .putBoolean(KEY_GRAYSCALE_ACTIVE, isGrayscaleActive)
            .putInt(KEY_POPUPS_SHOWN, popupsShown)
            .putBoolean(KEY_WAS_BARRIER_SHOWING, overlayManager?.isBarrierShowing == true)
            .apply()

        if (isGrayscaleActive) {
            overlayManager?.setSystemGrayscale(false)
        }
        
        isGrayscaleActive = false
        popupsShown = 0

        overlayManager?.hideOverlay()
    }

    private fun handleWindowStateChange(event: AccessibilityEvent, packageName: String) {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()

        if (TARGET_PACKAGES.contains(packageName)) {
            if (!isTargetAppActive) {
                Log.d(TAG, "Target app opened")
                isTargetAppActive = true

                // Cooldown Reset
                val lastClosedTime = sharedPrefs.getLong(KEY_LAST_CLOSED_TIME, 0)
                if (lastClosedTime > 0 && (currentTime - lastClosedTime) > 10 * 60 * 1000) {
                    // Over 10 minutes away — reset everything
                    Log.d(TAG, "Over 10 minutes passed. Resetting all state.")
                    
                    val finalActiveTime = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
                    if (finalActiveTime > 0) {
                        val repo = com.example.tethr.data.SessionRepository(this@TethrAccessibilityService)
                        repo.recordSession(finalActiveTime)
                    }
                    
                    activeTimeMs = 0
                    isGrayscaleActive = false
                    popupsShown = 0
                    sharedPrefs.edit()
                        .putLong(KEY_ACTIVE_TIME_MS, 0)
                        .putBoolean(KEY_GRAYSCALE_ACTIVE, false)
                        .putInt(KEY_POPUPS_SHOWN, 0)
                        .apply()
                } else {
                    // Resume state
                    activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
                    isGrayscaleActive = sharedPrefs.getBoolean(KEY_GRAYSCALE_ACTIVE, false)
                    popupsShown = sharedPrefs.getInt(KEY_POPUPS_SHOWN, 0)
                    val wasBarrierShowing = sharedPrefs.getBoolean(KEY_WAS_BARRIER_SHOWING, false)
                    Log.d(TAG, "Resuming: ${activeTimeMs}ms")
                    
                    if (isGrayscaleActive) {
                        overlayManager?.setSystemGrayscale(true)
                    }
                    if (wasBarrierShowing && popupsShown > 0) {
                        overlayManager?.showBarrier(popupsShown)
                    }
                }

                overlayManager?.showOverlay()
                handler.post(timeTrackerRunnable)
            }
        } else if (!isTransientSystemPackage(packageName)) {
            val isSystemUI = packageName.contains("systemui", ignoreCase = true) || packageName.contains("sysui", ignoreCase = true)
            
            if (isSystemUI) {
                val className = event.className?.toString() ?: ""
                var isNotificationOrVolume = className.contains("Notification", ignoreCase = true)
                        || className.contains("StatusBar", ignoreCase = true)
                        || className.contains("Volume", ignoreCase = true)
                        || className.contains("panel", ignoreCase = true)

                // Physics-based fallback: Notification shades drop from the top (y=0). Gestures are at the bottom.
                try {
                    val node = event.source
                    if (node != null) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.top <= 100) {
                            isNotificationOrVolume = true
                        }
                    }
                } catch (e: Exception) {}

                if (isNotificationOrVolume) {
                    return // Keep overlay alive for notifications
                } else {
                    Log.d(TAG, "SystemUI gesture swipe detected. Hiding instantly.")
                    forceCleanup()
                    return
                }
            }

            val isLauncher = packageName.contains("launcher", ignoreCase = true) || 
                             packageName == "com.miui.home" || 
                             packageName == "com.sec.android.app.launcher" ||
                             packageName.contains("bbk", ignoreCase = true) ||
                             packageName == "com.google.android.apps.nexuslauncher"

            // Check if target app is actually still physically visible underneath (active)
            if (!isLauncher) {
                if (getVisibleTargetPackage() != null) {
                    Log.d(TAG, "Ignored false exit to $packageName; Target app is still visible")
                    return
                }
            }

            forceCleanup()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "TethrAccessibilityService Interrupted — cleaning up")
        forceCleanup()
    }

    private fun getVisibleTargetPackage(): String? {
        try {
            val activeRootPkg = rootInActiveWindow?.packageName?.toString()
            if (TARGET_PACKAGES.contains(activeRootPkg)) {
                return activeRootPkg
            }
        } catch (e: Exception) {}

        try {
            val currentWindows = windows
            if (currentWindows != null) {
                for (window in currentWindows) {
                    val rootPkg = try { window.root?.packageName?.toString() } catch (e: Exception) { null }
                    if (TARGET_PACKAGES.contains(rootPkg) && window.isActive) {
                        return rootPkg
                    }
                }
            }
        } catch (e: Exception) {}
        
        return null
    }

    companion object {
        val TARGET_PACKAGES = setOf(
            "com.instagram.android", // Instagram
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok (some regions)
            "com.facebook.katana" // Facebook
        )
        private const val TAG = "TethrAccessibility"
        private const val PREFS_NAME = "TethrPrefs"
        private const val KEY_ACTIVE_TIME_MS = "ACTIVE_TIME_MS"
        private const val KEY_LAST_CLOSED_TIME = "LAST_CLOSED_TIME"
        private const val KEY_GRAYSCALE_ACTIVE = "GRAYSCALE_ACTIVE"
        private const val KEY_POPUPS_SHOWN = "POPUPS_SHOWN"
        private const val KEY_WAS_BARRIER_SHOWING = "WAS_BARRIER_SHOWING"
    }
}

