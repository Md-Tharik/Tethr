Created At: 2026-08-22T12:05:39+05:30
Completed At: 2026-08-22T12:05:39+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 473
Total Bytes: 28614
Showing lines 1 to 100
The following code has been modified to include a line number before every line, in the format: <line_number>: <original_line>. Please note that any changes targeting the original code should remove the line number, colon, and leading space.
1: package com.example.tethr
2: 
3: import android.accessibilityservice.AccessibilityService
4: import android.content.BroadcastReceiver
5: import android.content.Context
6: import android.content.Intent
7: import android.content.IntentFilter
8: import android.content.SharedPreferences
9: import android.os.Handler
10: import android.os.Looper
11: import android.util.Log
12: import android.view.accessibility.AccessibilityEvent
13: import android.view.accessibility.AccessibilityNodeInfo
14: import com.example.tethr.data.SessionRepository
15: 
16: /**
17:  * TethrAccessibilityService â€” core engine of the Escalation Matrix.
18:  *
19:  * Root causes fixed in this revision
20:  * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
21:  * BUG A â€” False exit from keyboard/SystemUI
22:  *   When we removed the packageNames XML filter (needed to detect home-screen
23:  *   exit), we started receiving TYPE_WINDOW_STATE_CHANGED from EVERY system
24:  *   overlay: keyboards, status-bar pull-downs, toasts, permission dialogsâ€¦
25:  *   These packages are NOT in TARGET_PACKAGES â†’ debounce fires â†’ hideAll().
26:  *   The ~5-second delay before the first disappearance matches when Instagram's
27:  *   keyboard first appears (search bar auto-focus etc.).
28:  *   FIX: isTransientSystemPackage() silently drops events from known system
29:  *   overlays, keyboards, and IMEs. Only "real" foreground app changes go through.
30:  *
31:  * BUG B â€” Restored tier immediately overwritten
32:  *   onTargetAppOpened() called restoreTierState() (which correctly set
33:  *   currentTier=2/3 and showed the right overlay), then unconditionally
34:  *   overwrote it with showTier1() + currentTier=1. Result: math quiz shown
35:  *   for ~1 frame, then hideTask() from showTier1() removed it.
36:  *   FIX: restructured so showTier1/currentTier=1 only run for fresh sessions;
37:  *   resume sessions call showTier1 as a base then restoreTierState upgrades it.
38:  *
39:  * BUG C â€” Multiple stacked timers on rapid re-entry
40:  *   If onTargetAppOpened() was called twice (e.g. Instagram fires multiple
41:  *   activity transitions on launch), two timeTrackerRunnable loops would run
42:  *   concurrently, doubling every tick's side effects.
43:  *   FIX: guard at the top of onTargetAppOpened() â€” bail if the session is
44:  *   already running (currentTier > 0 and same package).
45:  */
46: class TethrAccessibilityService : AccessibilityService() {
47: 
48:     // â”€â”€ Core State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
49:     private var cumulativeActiveMs: Long = 0L
50:     private var currentTier = 0             // 0=idle, 1, 2, 3
51:     private var tier3ExtensionMs: Long = 0L
52:     private var triggerTime: Long = 0L
53:     private var toxicWordTriggered = false
54:     private var sessionStartTime: Long = 0L
55: 
56:     private val installedKeyboards: Set<String> by lazy {
57:         val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
58:         imm.inputMethodList.map { it.packageName }.toSet()
59:     }
60: 
61:     // â”€â”€ Package tracking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
62:     // Single source of truth â€” updated only when a non-transient package change
63:     // is confirmed after the 1000ms debounce.
64:     private var currentForegroundPackage: String = ""
65: 
66:     // â”€â”€ Dependencies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
67:     private lateinit var overlayManager: OverlayManager
68:     private lateinit var sessionRepo: SessionRepository
69:     private lateinit var prefs: SharedPreferences
70:     private val mainHandler = Handler(Looper.getMainLooper())
71: 
72: 
73: 
74:     // â”€â”€ 1-second tick â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
75:     private val timeTrackerRunnable = object : Runnable {
76:         override fun run() {
77:             // Leak guard â€” if a target app isn't foreground, stop and clean up
78:             if (currentForegroundPackage !in TARGET_PACKAGES) {
79:                 Log.d(TAG, "Tick leak: $currentForegroundPackage not in whitelist â†’ hideAll()")
80:                 overlayManager.hideAll()
81:                 return  // do NOT re-post
82:             }
83:             cumulativeActiveMs += TICK_MS
84:             evaluateTier()
85:             overlayManager.updateTime(cumulativeActiveMs)
86:             sessionRepo.saveActiveState(cumulativeMs = cumulativeActiveMs, lastExitTimestamp = 0L)
87:             mainHandler.postDelayed(this, TICK_MS)
88:         }
89:     }
90: 
91:     // â”€â”€ Screen-off receiver â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
92:     private val screenOffReceiver = object : BroadcastReceiver() {
93:         override fun onReceive(context: Context, intent: Intent) {
94:             if (intent.action == Intent.ACTION_SCREEN_OFF) {
95:                 Log.d(TAG, "Screen OFF â†’ hideAll()")
96:                 overlayManager.hideAll()
97:             }
98:         }
99:     }
100: 
The above content does NOT show the entire file contents. If you need to view any lines of the file which were not shown to complete your task, call this tool again to view those lines.

