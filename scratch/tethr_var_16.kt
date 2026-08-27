Created At: 2026-08-27T22:04:22+05:30
Completed At: 2026-08-27T22:04:22+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 279
Total Bytes: 10826
Showing lines 1 to 279
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
91:         // NLP Sentiment Analysis on keystrokes
92:         if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
93:             if (isInstagramActive) {
94:                 val text = event.text?.joinToString(" ")
95:                 if (!text.isNullOrEmpty()) {
96:                     val results = nlClassifier?.classify(text)
97:                     val negScore = results?.find { it.label == "Negative" }?.score ?: 0f
98:                     if (negScore > 0.8f) {
99:                         Log.d(TAG, "Negative sentiment detected ($negScore). Triggering Tier 2 Grayscale!")
100:                         overlayManager?.showTier2()
101:                     }
102:                 }
103:             }
104:         }
105: 
106:         // Only process scroll events if we are actually emitting from Instagram
107:         if (packageName != "com.instagram.android") return
108: 
109:         val rootNode = rootInActiveWindow
110:         var isCommentSectionVisible = false
111:         if (rootNode != null) {
112:             isCommentSectionVisible = checkIfCommentSection(rootNode)
113:         }
114: 
115:         // Handle Scroll (Debounced)
116:         if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
117:             val currentTime = System.currentTimeMillis()
118:             // Debounce: ignore events within 500ms
119:             if (currentTime - lastScrollTime < 500) {
120:                 return
121:             }
122: 
123:             if (!isCommentSectionVisible) {
124:                 lastScrollTime = currentTime
125:                 reelCount += 1
126:                 Log.d(TAG, "Reel #$reelCount counted")
127: 
128:                 // Update pill immediately with new reel count
129:                 val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
130:                 overlayManager?.updateTime(activeTimeMs)
131: 
132:                 // Friction: inject a counter-scroll to resist the swipe
133:                 if (isFrictionActive) {
134:                     injectCounterScroll()
135:                 }
136:             } else {
137:                 Log.d(TAG, "Scrolled in Comment section, ignoring.")
138:             }
139:         }
140: 
141: 
142:     }
143: 
144:     /**
145:      * Safe friction: uses AccessibilityService.dispatchGesture() to inject
146:      * a partial scroll in the OPPOSITE direction after the user swipes.
147:      * This creates physical resistance without ever blocking touches.
148:      */
149:     private fun injectCounterScroll() {
150:         val displayMetrics = resources.displayMetrics
151:         val screenHeight = displayMetrics.heightPixels
152:         val screenWidth = displayMetrics.widthPixels
153: 
154:         // Swipe upward (counter the user's downward scroll on Reels)
155:         // Start from the middle, move upward by ~40% of screen height
156:         val startX = screenWidth / 2f
157:         val startY = screenHeight * 0.4f
158:         val endY = screenHeight * 0.75f
159: 
160:         val path = Path().apply {
161:             moveTo(startX, startY)
162:             lineTo(startX, endY)
163:         }
164: 
165:         val gesture = GestureDescription.Builder()
166:             .addStroke(GestureDescription.StrokeDescription(path, 50, 150))
167:             .build()
168: 
169:         dispatchGesture(gesture, object : GestureResultCallback() {
170:             override fun onCompleted(gestureDescription: GestureDescription?) {
171:                 Log.d(TAG, "Counter-scroll injected (friction)")
172:             }
173:             override fun onCancelled(gestureDescription: GestureDescription?) {
174:                 Log.d(TAG, "Counter-scroll cancelled")
175:             }
176:         }, null)
177:     }
178: 
179:     private fun handleWindowStateChange(packageName: String) {
180:         // Ignore keyboards, SystemUI, and our own app's overlays so we don't accidentally close the Instagram session
181:         if (packageName.contains("inputmethod", ignoreCase = true) ||
182:             packageName.contains("keyboard", ignoreCase = true) ||
183:             packageName == "com.samsung.android.honeyboard" ||
184:             packageName == "com.touchtype.swiftkey" ||
185:             packageName == "ai.tethr.app" ||
186:             packageName == "com.example.tethr" ||
187:             packageName == "com.android.systemui"
188:         ) {
189:             return
190:         }
191: 
192:         val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
193:         val currentTime = System.currentTimeMillis()
194: 
195:         if (packageName == "com.instagram.android") {
196:             if (!isInstagramActive) {
197:                 Log.d(TAG, "Instagram opened")
198:                 isInstagramActive = true
199: 
200:                 // Cooldown Reset
201:                 val lastClosedTime = sharedPrefs.getLong(KEY_LAST_CLOSED_TIME, 0)
202:                 if (lastClosedTime > 0 && (currentTime - lastClosedTime) > 10 * 60 * 1000) {
203:                     // Over 10 minutes away â€” reset everything
204:                     Log.d(TAG, "Over 10 minutes passed. Resetting all state.")
205:                     activeTimeMs = 0
206:                     reelCount = 0
207:                     isGrayscaleActive = false
208:                     isFrictionActive = false
209:                     sharedPrefs.edit()
210:                         .putLong(KEY_ACTIVE_TIME_MS, 0)
211:                         .putInt(KEY_REEL_COUNT, 0)
212:                         .apply()
213:                 } else {
214:                     // Resume state
215:                     activeTimeMs = sharedPrefs.getLong(KEY_ACTIVE_TIME_MS, 0)
216:                     reelCount = sharedPrefs.getInt(KEY_REEL_COUNT, 0)
217:                     Log.d(TAG, "Resuming: ${activeTimeMs}ms, $reelCount reels")
218:                 }
219: 
220:                 overlayManager?.showTier1(activeTimeMs)
221:                 handler.post(timeTrackerRunnable)
222:             }
223:         } else {
224:             if (isInstagramActive) {
225:                 Log.d(TAG, "Instagram closed/backgrounded â€” cleaning up everything")
226:                 isInstagramActive = false
227:                 handler.removeCallbacks(timeTrackerRunnable)
228: 
229:                 // Turn off grayscale immediately when leaving Instagram
230:                 if (isGrayscaleActive) {
231:                     isGrayscaleActive = false
232:                 }
233:                 isFrictionActive = false
234: 
235:                 sharedPrefs.edit()
236:                     .putLong(KEY_LAST_CLOSED_TIME, currentTime)
237:                     .putLong(KEY_ACTIVE_TIME_MS, activeTimeMs)
238:                     .putInt(KEY_REEL_COUNT, reelCount)
239:                     .apply()
240: 
241:                 overlayManager?.hideAll()
242:             }
243:         }
244:     }
245: 
246:     override fun onInterrupt() {
247:         Log.d(TAG, "TethrAccessibilityService Interrupted â€” cleaning up")
248:         isInstagramActive = false
249:         handler.removeCallbacks(timeTrackerRunnable)
250:         if (isGrayscaleActive) {
251:             isGrayscaleActive = false
252:         }
253:         isFrictionActive = false
254:         overlayManager?.hideAll()
255:     }
256: 
257:     private fun checkIfCommentSection(node: AccessibilityNodeInfo?): Boolean {
258:         if (node == null) return false
259:         if (node.text != null && (node.text.toString().contains("Add a comment", ignoreCase = true) ||
260:             node.text.toString().contains("What do you think of this?", ignoreCase = true))) {
261:             return true
262:         }
263:         for (i in 0 until node.childCount) {
264:             if (checkIfCommentSection(node.getChild(i))) {
265:                 return true
266:             }
267:         }
268:         return false
269:     }
270: 
271:     companion object {
272:         private const val TAG = "TethrAccessibility"
273:         private const val PREFS_NAME = "TethrPrefs"
274:         private const val KEY_ACTIVE_TIME_MS = "ACTIVE_TIME_MS"
275:         private const val KEY_LAST_CLOSED_TIME = "LAST_CLOSED_TIME"
276:         private const val KEY_REEL_COUNT = "REEL_COUNT"
277:     }
278: }
279: 
The above content shows the entire, complete file contents of the requested file.

