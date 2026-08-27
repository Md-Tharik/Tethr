Created At: 2026-08-27T21:55:06+05:30
Completed At: 2026-08-27T21:55:06+05:30
File Path: `file:///C:/Users/tharik/.gemini/antigravity-ide/brain/8530c733-4810-4cad-90d1-79ab33d6871e/scratch/restored_tethr.kt`
Total Lines: 242
Total Bytes: 9309
Showing lines 1 to 242
The following code has been modified to include a line number before every line, in the format: <line_number>: <original_line>. Please note that any changes targeting the original code should remove the line number, colon, and leading space.
1: package com.example.tethr
2: 
3: import android.accessibilityservice.AccessibilityService
4: import android.accessibilityservice.GestureDescription
5: import android.content.Context
6: import android.content.Intent
7: import android.graphics.Path
8: import android.os.Handler
9: import android.os.Looper
10: import android.util.Log
11: import android.view.accessibility.AccessibilityEvent
12: import android.view.accessibility.AccessibilityNodeInfo
13: 
14: class TethrAccessibilityService : AccessibilityService() {
15: 
16:     private var activeTimeMs: Long = 0
17:     private var reelCount: Int = 0
18:     private var isInstagramActive = false
19:     private var lastScrollTime: Long = 0
20:     private var overlayManager: OverlayManager? = null
21:     private var isGrayscaleActive = false
22:     private var isFrictionActive = false
23: 
24:     private val handler = Handler(Looper.getMainLooper())
25:     private val timeTrackerRunnable = object : Runnable {
26:         override fun run() {
27:             if (isInstagramActive) {
28:                 activeTimeMs += 1000
29: 
30:                 val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
31:                 sharedPrefs.edit()
32:                     .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
33:                     .putInt(KEY_REEL_COUNT, reelCount)
34:                     .apply()
35: 
36:                 val isDemoMode = sharedPrefs.getBoolean("DEMO_MODE", false)
37:                 val effectiveTimeMs = if (isDemoMode) activeTimeMs * 60 else activeTimeMs
38: 
39:                 val fiveMinutes = 5L * 60 * 1000
40:                 val tenMinutes = 10L * 60 * 1000
41: 
42:                 // Grayscale: hard toggle at 5 minutes
43:                 if (effectiveTimeMs >= fiveMinutes && !isGrayscaleActive) {
44:                     overlayManager?.setSystemGrayscale(true)
45:                     isGrayscaleActive = true
46:                     Log.d(TAG, "Grayscale ON at 5 minutes")
47:                 }
48: 
49:                 // Friction: activate at 10 minutes
50:                 if (effectiveTimeMs >= tenMinutes) {
51:                     isFrictionActive = true
52:                 }
53: 
54:                 // Dispatch time and reel metrics to the OverlayManager
55:                 overlayManager?.updateMetrics(activeTimeMs, reelCount, isDemoMode)
56: 
57:                 handler.postDelayed(this, 1000)
58:             }
59:         }
60:     }
61: 
62:     override fun onServiceConnected() {
63:         super.onServiceConnected()
64:         Log.d(TAG, "TethrAccessibilityService Connected")
65:         overlayManager = OverlayManager(this)
66: 
67:         val intent = Intent(this, TethrForegroundService::class.java)
68:         startForegroundService(intent)
69:     }
70: 
71:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
72:         if (event == null) return
73:         val packageName = event.packageName?.toString() ?: return
74: 
75:         // Handle Window State Changed (Detect active app)
76:         if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
77:             handleWindowStateChange(packageName)
78:         }
79: 
80:         // Only process scroll events if we are in Instagram
81:         if (packageName != "com.instagram.android") return
82: 
83:         val rootNode = rootInActiveWindow
84:         var isCommentSectionVisible = false
85:         if (rootNode != null) {
86:             isCommentSectionVisible = checkIfCommentSection(rootNode)
87:         }
88: 
89:         // Handle Scroll (Debounced)
90:         if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
91:             val currentTime = System.currentTimeMillis()
92:             // Debounce: ignore events within 500ms
93:             if (currentTime - lastScrollTime < 500) {
94:                 return
95:             }
96: 
97:             if (!isCommentSectionVisible) {
98:                 lastScrollTime = currentTime
99:                 reelCount += 1
100:                 Log.d(TAG, "Reel #$reelCount counted")
101: 
102:                 // Update pill immediately with new reel count
103:                 val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
104:                 val isDemoMode = sharedPrefs.getBoolean("DEMO_MODE", false)
105:                 overlayManager?.updateMetrics(activeTimeMs, reelCount, isDemoMode)
106: 
107:                 // Friction: inject a counter-scroll to resist the swipe
108:                 if (isFrictionActive) {
109:                     injectCounterScroll()
110:                 }
111:             } else {
112:                 Log.d(TAG, "Scrolled in Comment section, ignoring.")
113:             }
114:         }
115:     }
116: 
117:     /**
118:      * Safe friction: uses AccessibilityService.dispatchGesture() to inject
119:      * a partial scroll in the OPPOSITE direction after the user swipes.
120:      * This creates physical resistance without ever blocking touches.
121:      */
122:     private fun injectCounterScroll() {
123:         val displayMetrics = resources.displayMetrics
124:         val screenHeight = displayMetrics.heightPixels
125:         val screenWidth = displayMetrics.widthPixels
126: 
127:         // Swipe upward (counter the user's downward scroll on Reels)
128:         // Start from the middle, move upward by ~40% of screen height
129:         val startX = screenWidth / 2f
130:         val startY = screenHeight * 0.4f
131:         val endY = screenHeight * 0.75f
132: 
133:         val path = Path().apply {
134:             moveTo(startX, startY)
135:             lineTo(startX, endY)
136:         }
137: 
138:         val gesture = GestureDescription.Builder()
139:             .addStroke(GestureDescription.StrokeDescription(path, 50, 150))
140:             .build()
141: 
142:         dispatchGesture(gesture, object : GestureResultCallback() {
143:             override fun onCompleted(gestureDescription: GestureDescription?) {
144:                 Log.d(TAG, "Counter-scroll injected (friction)")
145:             }
146:             override fun onCancelled(gestureDescription: GestureDescription?) {
147:                 Log.d(TAG, "Counter-scroll cancelled")
148:             }
149:         }, null)
150:     }
151: 
152:     private fun handleWindowStateChange(packageName: String) {
153:         val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
154:         val currentTime = System.currentTimeMillis()
155: 
156:         if (packageName == "com.instagram.android") {
157:             if (!isInstagramActive) {
158:                 Log.d(TAG, "Instagram opened")
159:                 isInstagramActive = true
160: 
161:                 // Cooldown Reset
162:                 val lastClosedTime = sharedPrefs.getLong(KEY_LAST_CLOSED_TIME, 0)
163:                 if (lastClosedTime > 0 && (currentTime - lastClosedTime) > 10 * 60 * 1000) {
164:                     // Over 10 minutes away â€” reset everything
165:                     Log.d(TAG, "Over 10 minutes passed. Resetting all state.")
166:                     activeTimeMs = 0
167:                     reelCount = 0
168:                     isGrayscaleActive = false
169:                     isFrictionActive = false
170:                     sharedPrefs.edit()
171:                         .putLong(KEY_ACTIVE_TIME_MS, 0)
172:                         .putInt(KEY_REEL_COUNT, 0)
173:                         .apply()
174:                 } else {
175:                     // Resume state
176:                     activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
177:                     reelCount = sharedPrefs.getInt(KEY_REEL_COUNT, 0)
178:                     Log.d(TAG, "Resuming: ${activeTimeMs}ms, $reelCount reels")
179:                 }
180: 
181:                 overlayManager?.showOverlay()
182:                 handler.post(timeTrackerRunnable)
183:             }
184:         } else {
185:             if (isInstagramActive) {
186:                 Log.d(TAG, "Instagram closed/backgrounded â€” cleaning up everything")
187:                 isInstagramActive = false
188:                 handler.removeCallbacks(timeTrackerRunnable)
189: 
190:                 // Turn off grayscale immediately when leaving Instagram
191:                 if (isGrayscaleActive) {
192:                     overlayManager?.setSystemGrayscale(false)
193:                     isGrayscaleActive = false
194:                 }
195:                 isFrictionActive = false
196: 
197:                 sharedPrefs.edit()
198:                     .putLong(KEY_LAST_CLOSED_TIME, currentTime)
199:                     .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
200:                     .putInt(KEY_REEL_COUNT, reelCount)
201:                     .apply()
202: 
203:                 overlayManager?.hideOverlay()
204:             }
205:         }
206:     }
207: 
208:     override fun onInterrupt() {
209:         Log.d(TAG, "TethrAccessibilityService Interrupted â€” cleaning up")
210:         isInstagramActive = false
211:         handler.removeCallbacks(timeTrackerRunnable)
212:         if (isGrayscaleActive) {
213:             overlayManager?.setSystemGrayscale(false)
214:             isGrayscaleActive = false
215:         }
216:         isFrictionActive = false
217:         overlayManager?.hideOverlay()
218:     }
219: 
220:     private fun checkIfCommentSection(node: AccessibilityNodeInfo?): Boolean {
221:         if (node == null) return false
222:         if (node.text != null && (node.text.toString().contains("Add a comment", ignoreCase = true) ||
223:             node.text.toString().contains("What do you think of this?", ignoreCase = true))) {
224:             return true
225:         }
226:         for (i in 0 until node.childCount) {
227:             if (checkIfCommentSection(node.getChild(i))) {
228:                 return true
229:             }
230:         }
231:         return false
232:     }
233: 
234:     companion object {
235:         private const val TAG = "TethrAccessibility"
236:         private const val PREFS_NAME = "TethrPrefs"
237:         private const val KEY_ACTIVE_TIME_MS = "ACTIVE_TIME_MS"
238:         private const val KEY_LAST_CLOSED_TIME = "LAST_CLOSED_TIME"
239:         private const val KEY_REEL_COUNT = "REEL_COUNT"
240:     }
241: }
242: 
The above content shows the entire, complete file contents of the requested file.

