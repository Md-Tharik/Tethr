Created At: 2026-08-27T21:49:16+05:30
Completed At: 2026-08-27T21:49:16+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 263
Total Bytes: 10156
Showing lines 1 to 263
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
13: import android.widget.Toast
14: import org.tensorflow.lite.task.text.nlclassifier.NLClassifier
15: 
16: class TethrAccessibilityService : AccessibilityService() {
17: 
18:     private var activeTimeMs: Long = 0
19:     private var reelCount: Int = 0
20:     private var isInstagramActive = false
21:     private var lastScrollTime: Long = 0
22:     private var overlayManager: OverlayManager? = null
23:     private var isGrayscaleActive = false
24:     private var isFrictionActive = false
25:     private var nlClassifier: NLClassifier? = null
26: 
27:     private val handler = Handler(Looper.getMainLooper())
28:     private val timeTrackerRunnable = object : Runnable {
29:         override fun run() {
30:             if (isInstagramActive) {
31:                 activeTimeMs += 1000
32: 
33:                 val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
34:                 sharedPrefs.edit()
35:                     .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
36:                     .putInt(KEY_REEL_COUNT, reelCount)
37:                     .apply()
38: 
39:                 val isDemoMode = sharedPrefs.getBoolean("DEMO_MODE", false)
40:                 val effectiveTimeMs = if (isDemoMode) activeTimeMs * 60 else activeTimeMs
41: 
42:                 val fiveMinutes = 5L * 60 * 1000
43:                 val tenMinutes = 10L * 60 * 1000
44: 
45:                 // Grayscale: hard toggle at 5 minutes
46:                 if (effectiveTimeMs >= fiveMinutes && !isGrayscaleActive) {
47:                     overlayManager?.showTier2()
48:                     isGrayscaleActive = true
49:                     Log.d(TAG, "Grayscale ON at 5 minutes")
50:                 }
51: 
52:                 // Friction: activate at 10 minutes
53:                 if (effectiveTimeMs >= tenMinutes) {
54:                     isFrictionActive = true
55:                 }
56: 
57:                 // Dispatch time and reel metrics to the OverlayManager
58:                 overlayManager?.updateTime(activeTimeMs)
59: 
60:                 handler.postDelayed(this, 1000)
61:             }
62:         }
63:     }
64: 
65:     override fun onServiceConnected() {
66:         super.onServiceConnected()
67:         Log.d(TAG, "TethrAccessibilityService Connected")
68:         overlayManager = OverlayManager(this)
69: 
70:         try {
71:             val options = NLClassifier.NLClassifierOptions.builder().build()
72:             nlClassifier = NLClassifier.createFromFileAndOptions(this, "text_classification_v2.tflite", options)
73:             Log.d(TAG, "NLClassifier loaded successfully")
74:         } catch (e: Exception) {
75:             Log.e(TAG, "Failed to load NLClassifier", e)
76:         }
77: 
78:         val intent = Intent(this, TethrForegroundService::class.java)
79:         startForegroundService(intent)
80:     }
81: 
82:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
83:         if (event == null) return
84:         val packageName = event.packageName?.toString() ?: return
85: 
86:         // Handle Window State Changed (Detect active app)
87:         if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
88:             handleWindowStateChange(packageName)
89:         }
90: 
91:         // Only process scroll events if we are in Instagram
92:         if (packageName != "com.instagram.android") return
93: 
94:         val rootNode = rootInActiveWindow
95:         var isCommentSectionVisible = false
96:         if (rootNode != null) {
97:             isCommentSectionVisible = checkIfCommentSection(rootNode)
98:         }
99: 
100:         // Handle Scroll (Debounced)
101:         if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
102:             val currentTime = System.currentTimeMillis()
103:             // Debounce: ignore events within 500ms
104:             if (currentTime - lastScrollTime < 500) {
105:                 return
106:             }
107: 
108:             if (!isCommentSectionVisible) {
109:                 lastScrollTime = currentTime
110:                 reelCount += 1
111:                 Log.d(TAG, "Reel #$reelCount counted")
112: 
113:                 // Update pill immediately with new reel count
114:                 val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
115:                 overlayManager?.updateTime(activeTimeMs)
116: 
117:                 // Friction: inject a counter-scroll to resist the swipe
118:                 if (isFrictionActive) {
119:                     injectCounterScroll()
120:                 }
121:             } else {
122:                 Log.d(TAG, "Scrolled in Comment section, ignoring.")
123:             }
124:         }
125: 
126:         // NLP Sentiment Analysis on keystrokes
127:         if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
128:             val text = event.text?.joinToString(" ")
129:             if (!text.isNullOrEmpty()) {
130:                 val results = nlClassifier?.classify(text)
131:                 val negScore = results?.find { it.label == "Negative" }?.score ?: 0f
132:                 if (negScore > 0.8f) {
133:                     Log.d(TAG, "Negative sentiment detected ($negScore). Triggering Tier 2 Grayscale!")
134:                     overlayManager?.showTier2()
135:                 }
136:             }
137:         }
138:     }
139: 
140:     /**
141:      * Safe friction: uses AccessibilityService.dispatchGesture() to inject
142:      * a partial scroll in the OPPOSITE direction after the user swipes.
143:      * This creates physical resistance without ever blocking touches.
144:      */
145:     private fun injectCounterScroll() {
146:         val displayMetrics = resources.displayMetrics
147:         val screenHeight = displayMetrics.heightPixels
148:         val screenWidth = displayMetrics.widthPixels
149: 
150:         // Swipe upward (counter the user's downward scroll on Reels)
151:         // Start from the middle, move upward by ~40% of screen height
152:         val startX = screenWidth / 2f
153:         val startY = screenHeight * 0.4f
154:         val endY = screenHeight * 0.75f
155: 
156:         val path = Path().apply {
157:             moveTo(startX, startY)
158:             lineTo(startX, endY)
159:         }
160: 
161:         val gesture = GestureDescription.Builder()
162:             .addStroke(GestureDescription.StrokeDescription(path, 50, 150))
163:             .build()
164: 
165:         dispatchGesture(gesture, object : GestureResultCallback() {
166:             override fun onCompleted(gestureDescription: GestureDescription?) {
167:                 Log.d(TAG, "Counter-scroll injected (friction)")
168:             }
169:             override fun onCancelled(gestureDescription: GestureDescription?) {
170:                 Log.d(TAG, "Counter-scroll cancelled")
171:             }
172:         }, null)
173:     }
174: 
175:     private fun handleWindowStateChange(packageName: String) {
176:         val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
177:         val currentTime = System.currentTimeMillis()
178: 
179:         if (packageName == "com.instagram.android") {
180:             if (!isInstagramActive) {
181:                 Log.d(TAG, "Instagram opened")
182:                 isInstagramActive = true
183: 
184:                 // Cooldown Reset
185:                 val lastClosedTime = sharedPrefs.getLong(KEY_LAST_CLOSED_TIME, 0)
186:                 if (lastClosedTime > 0 && (currentTime - lastClosedTime) > 10 * 60 * 1000) {
187:                     // Over 10 minutes away â€” reset everything
188:                     Log.d(TAG, "Over 10 minutes passed. Resetting all state.")
189:                     activeTimeMs = 0
190:                     reelCount = 0
191:                     isGrayscaleActive = false
192:                     isFrictionActive = false
193:                     sharedPrefs.edit()
194:                         .putLong(KEY_ACTIVE_TIME_MS, 0)
195:                         .putInt(KEY_REEL_COUNT, 0)
196:                         .apply()
197:                 } else {
198:                     // Resume state
199:                     activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
200:                     reelCount = sharedPrefs.getInt(KEY_REEL_COUNT, 0)
201:                     Log.d(TAG, "Resuming: ${activeTimeMs}ms, $reelCount reels")
202:                 }
203: 
204:                 overlayManager?.showTier1(activeTimeMs)
205:                 handler.post(timeTrackerRunnable)
206:             }
207:         } else {
208:             if (isInstagramActive) {
209:                 Log.d(TAG, "Instagram closed/backgrounded â€” cleaning up everything")
210:                 isInstagramActive = false
211:                 handler.removeCallbacks(timeTrackerRunnable)
212: 
213:                 // Turn off grayscale immediately when leaving Instagram
214:                 if (isGrayscaleActive) {
215:                     isGrayscaleActive = false
216:                 }
217:                 isFrictionActive = false
218: 
219:                 sharedPrefs.edit()
220:                     .putLong(KEY_LAST_CLOSED_TIME, currentTime)
221:                     .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
222:                     .putInt(KEY_REEL_COUNT, reelCount)
223:                     .apply()
224: 
225:                 overlayManager?.hideAll()
226:             }
227:         }
228:     }
229: 
230:     override fun onInterrupt() {
231:         Log.d(TAG, "TethrAccessibilityService Interrupted â€” cleaning up")
232:         isInstagramActive = false
233:         handler.removeCallbacks(timeTrackerRunnable)
234:         if (isGrayscaleActive) {
235:             isGrayscaleActive = false
236:         }
237:         isFrictionActive = false
238:         overlayManager?.hideAll()
239:     }
240: 
241:     private fun checkIfCommentSection(node: AccessibilityNodeInfo?): Boolean {
242:         if (node == null) return false
243:         if (node.text != null && (node.text.toString().contains("Add a comment", ignoreCase = true) ||
244:             node.text.toString().contains("What do you think of this?", ignoreCase = true))) {
245:             return true
246:         }
247:         for (i in 0 until node.childCount) {
248:             if (checkIfCommentSection(node.getChild(i))) {
249:                 return true
250:             }
251:         }
252:         return false
253:     }
254: 
255:     companion object {
256:         private const val TAG = "TethrAccessibility"
257:         private const val PREFS_NAME = "TethrPrefs"
258:         private const val KEY_ACTIVE_TIME_MS = "ACTIVE_TIME_MS"
259:         private const val KEY_LAST_CLOSED_TIME = "LAST_CLOSED_TIME"
260:         private const val KEY_REEL_COUNT = "REEL_COUNT"
261:     }
262: }
263: 
The above content shows the entire, complete file contents of the requested file.

