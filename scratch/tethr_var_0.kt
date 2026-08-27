Created At: 2026-08-20T17:45:57+05:30
Completed At: 2026-08-20T17:45:57+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 439
Total Bytes: 21735
Showing lines 1 to 439
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
17:  * TethrAccessibilityService — core engine of the Escalation Matrix.
18:  *
19:  * Root causes fixed in this revision
20:  * ────────────────────────────────────────────────────────────────────────────
21:  * BUG A — False exit from keyboard/SystemUI
22:  *   When we removed the packageNames XML filter (needed to detect home-screen
23:  *   exit), we started receiving TYPE_WINDOW_STATE_CHANGED from EVERY system
24:  *   overlay: keyboards, status-bar pull-downs, toasts, permission dialogs…
25:  *   These packages are NOT in TARGET_PACKAGES → debounce fires → hideAll().
26:  *   The ~5-second delay before the first disappearance matches when Instagram's
27:  *   keyboard first appears (search bar auto-focus etc.).
28:  *   FIX: isTransientSystemPackage() silently drops events from known system
29:  *   overlays, keyboards, and IMEs. Only "real" foreground app changes go through.
30:  *
31:  * BUG B — Restored tier immediately overwritten
32:  *   onTargetAppOpened() called restoreTierState() (which correctly set
33:  *   currentTier=2/3 and showed the right overlay), then unconditionally
34:  *   overwrote it with showTier1() + currentTier=1. Result: math quiz shown
35:  *   for ~1 frame, then hideTask() from showTier1() removed it.
36:  *   FIX: restructured so showTier1/currentTier=1 only run for fresh sessions;
37:  *   resume sessions call showTier1 as a base then restoreTierState upgrades it.
38:  *
39:  * BUG C — Multiple stacked timers on rapid re-entry
40:  *   If onTargetAppOpened() was called twice (e.g. Instagram fires multiple
41:  *   activity transitions on launch), two timeTrackerRunnable loops would run
42:  *   concurrently, doubling every tick's side effects.
43:  *   FIX: guard at the top of onTargetAppOpened() — bail if the session is
44:  *   already running (currentTier > 0 and same package).
45:  */
46: class TethrAccessibilityService : AccessibilityService() {
47: 
48:     // ── Core State ───────────────────────────────────────────────────────────
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
61:     // ── Package tracking ─────────────────────────────────────────────────────
62:     // Single source of truth — updated only when a non-transient package change
63:     // is confirmed after the 1000ms debounce.
64:     private var currentForegroundPackage: String = ""
65: 
66:     // ── Dependencies ─────────────────────────────────────────────────────────
67:     private lateinit var overlayManager: OverlayManager
68:     private lateinit var sessionRepo: SessionRepository
69:     private lateinit var prefs: SharedPreferences
70:     private val mainHandler = Handler(Looper.getMainLooper())
71: 
72: 
73: 
74:     // ── 1-second tick ────────────────────────────────────────────────────────
75:     private val timeTrackerRunnable = object : Runnable {
76:         override fun run() {
77:             // Leak guard — if a target app isn't foreground, stop and clean up
78:             if (currentForegroundPackage !in TARGET_PACKAGES) {
79:                 Log.d(TAG, "Tick leak: $currentForegroundPackage not in whitelist → hideAll()")
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
91:     // ── Screen-off receiver ──────────────────────────────────────────────────
92:     private val screenOffReceiver = object : BroadcastReceiver() {
93:         override fun onReceive(context: Context, intent: Intent) {
94:             if (intent.action == Intent.ACTION_SCREEN_OFF) {
95:                 Log.d(TAG, "Screen OFF → hideAll()")
96:                 overlayManager.hideAll()
97:             }
98:         }
99:     }
100: 
101:     // ── NLP toxic word list ──────────────────────────────────────────────────
102:     private val toxicWords = setOf(
103:         "hate", "ugly", "rage", "stupid", "worst", "disgusting",
104:         "horrible", "trash", "loser", "dumb", "idiot", "kill",
105:         "die", "awful", "repulsive", "pathetic", "worthless",
106:         "scum", "filth", "nasty", "vile", "gross"
107:     )
108:     private var lastNlpScanTime = 0L
109: 
110:     // ─────────────────────────────────────────────────────────────────────────
111:     // Service Lifecycle
112:     // ─────────────────────────────────────────────────────────────────────────
113: 
114:     override fun onServiceConnected() {
115:         super.onServiceConnected()
116:         Log.d(TAG, "TethrAccessibilityService connected")
117:         prefs = getSharedPreferences("tethr_prefs", Context.MODE_PRIVATE)
118:         sessionRepo = SessionRepository(this)
119:         overlayManager = OverlayManager(this)
120:         registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
121:         startForegroundService(Intent(this, TethrForegroundService::class.java))
122:     }
123: 
124:     override fun onDestroy() {
125:         super.onDestroy()
126:         Log.d(TAG, "Service destroyed")
127:         cleanupOnExit()
128:         try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
129:     }
130: 
131:     override fun onInterrupt() {
132:         Log.d(TAG, "Service interrupted")
133:         cleanupOnExit()
134:     }
135: 
136:     // ─────────────────────────────────────────────────────────────────────────
137:     // Accessibility Events
138:     // ─────────────────────────────────────────────────────────────────────────
139: 
140:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
141:         if (event == null) return
142:         val pkg = event.packageName?.toString() ?: ""
143: 
144:         when (event.eventType) {
145: 
146:             AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
147:                 // Bug A fix: skip keyboards, system UI, and other transient overlays.
148:                 // They are NOT foreground app changes and must never trigger hideAll().
149:                 if (isTransientSystemPackage(pkg)) return
150: 
151:                 // Instant synchronous processing of package changes (0ms delay)
152:                 if (pkg.isNotEmpty() && pkg != currentForegroundPackage) {
153:                     handleWindowStateChange(pkg)
154:                 }
155:             }
156: 
157:             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
158:                 if (pkg in TARGET_PACKAGES
159:                     && currentForegroundPackage in TARGET_PACKAGES
160:                     && currentTier in 1..2) {
161:                     val now = System.currentTimeMillis()
162:                     if (now - lastNlpScanTime >= NLP_SCAN_INTERVAL_MS) {
163:                         lastNlpScanTime = now
164:                         scanForToxicContent()
165:                     }
166:                 }
167:             }
168:         }
169:     }
170: 
171:     /**
172:      * Bug A fix — returns true for packages that are transient overlays drawn
173:      * ABOVE the current foreground app (keyboards, system UI, permission dialogs,
174:      * accessibility overlay windows, etc.) that are NOT real foreground app changes.
175:      *
176:      * Filtering rules (ordered cheapest-first):
177:      *  1. "android"          — core Android framework dialogs
178:      *  2. "com.android.systemui" — status bar, quick-settings, notifications
179:      *  3. Contains "inputmethod", ".ime.", "keyboard", "honeyboard", "bixby"
180:      *     — any soft keyboard. There are 50+ keyboard packages; substring match
181:      *     is the only sane cross-device approach.
182:      *  4. Tethr itself       — our own overlay windows fire events; ignore them.
183:      *
184:      * Launchers (home screen) are intentionally NOT filtered — we DO want to
185:      * detect when the user navigates home so we can call onTargetAppLeft().
186:      */
187:     private fun isTransientSystemPackage(pkg: String): Boolean {
188:         if (installedKeyboards.contains(pkg)) return true
189: 
190:         return pkg == "android"
191:             || pkg == "com.android.systemui"
192:             || pkg == "com.bbk.systemui"                   // Funtouch OS shade
193:             || pkg == "com.vivo.upslide"                   // Funtouch OS upslide
194:             || pkg == packageName                          // Tethr itself
195:             || pkg.contains("inputmethod", ignoreCase = true)
196:             || pkg.contains(".ime.", ignoreCase = true)
197:             || pkg.contains("keyboard", ignoreCase = true)
198:             || pkg.contains("honeyboard", ignoreCase = true)
199:             || pkg.contains("bixbyvision", ignoreCase = true)
200:             || pkg.contains("accessibility", ignoreCase = true)
201:     }
202: 
203:     // ─────────────────────────────────────────────────────────────────────────
204:     // Window State Change Handling (debounced)
205:     // ─────────────────────────────────────────────────────────────────────────
206: 
207:     private fun handleWindowStateChange(packageName: String) {
208:         currentForegroundPackage = packageName
209:         Log.d(TAG, "Foreground → $packageName")
210: 
211:         if (packageName in TARGET_PACKAGES) {
212:             onTargetAppOpened(packageName)
213:         } else {
214:             // TEMPORARY DEBUG: Show the user what package triggered the exit
215:             android.os.Handler(android.os.Looper.getMainLooper()).post {
216:                 android.widget.Toast.makeText(this, "Tethr hidden by: $packageName", android.widget.Toast.LENGTH_LONG).show()
217:             }
218:             onTargetAppLeft()
219:         }
220:     }
221: 
222:     private fun onTargetAppOpened(packageName: String) {
223:         // Bug C fix — if we're already tracking this package at a valid tier,
224:         // another activity transition within Instagram fired. Skip re-init.
225:         if (currentTier > 0) {
226:             Log.d(TAG, "Re-entry ignored — already active at Tier $currentTier")
227:             return
228:         }
229: 
230:         Log.d(TAG, "Target app opened: $packageName")
231:         toxicWordTriggered = false
232:         triggerTime = sessionRepo.computeTriggerTime()
233:         Log.d(TAG, "TriggerTime: ${triggerTime / 1000}s")
234: 
235:         val (savedCumulative, lastExitTimestamp) = sessionRepo.loadActiveState()
236:         val now = System.currentTimeMillis()
237: 
238:         if (lastExitTimestamp > 0 && (now - lastExitTimestamp) >= SessionRepository.COOLDOWN_MS) {
239:             // ── Fresh session (≥10 min away) ──────────────────────────────
240:             Log.d(TAG, "Cooldown expired — fresh session")
241:             if (savedCumulative > 0) {
242:                 sessionRepo.recordSession(savedCumulative)
243:                 sessionRepo.incrementWeekSessionCount()
244:             }
245:             cumulativeActiveMs = 0L
246:             tier3ExtensionMs = 0L
247:             currentTier = 1
248:             sessionRepo.resetActiveState()
249:             overlayManager.showTier1(0L)
250:         } else {
251:             // ── Resume session (<10 min away) ─────────────────────────────
252:             cumulativeActiveMs = savedCumulative
253:             Log.d(TAG, "Resuming at ${cumulativeActiveMs / 1000}s")
254:             
255:             // Base layer: Always show Tier 1 pill on resume
256:             currentTier = 1
257:             overlayManager.showTier1(cumulativeActiveMs)
258: 
259:             // CRITICAL: Explicitly re-evaluate and re-draw the UI state immediately
260:             if (cumulativeActiveMs >= triggerTime) {
261:                 currentTier = 2
262:                 overlayManager.showTier2()
263:             }
264:             
265:             if (prefs.getBoolean("PENDING_QUIZ", false)) {
266:                 Log.d(TAG, "Trap triggered: PENDING_QUIZ is true")
267:                 currentTier = 3
268:                 overlayManager.showTier3 { onTaskSolved() }
269:             } else if (cumulativeActiveMs >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs) {
270:                 currentTier = 3
271:                 overlayManager.showTier3 { onTaskSolved() }
272:             }
273: 
274:             // Ensure the background timer resumes ticking.
275:             sessionStartTime = System.currentTimeMillis()
276:             mainHandler.removeCallbacks(timeTrackerRunnable)
277:             mainHandler.post(timeTrackerRunnable)
278:         }
279: 
280:         sessionStartTime = System.currentTimeMillis()
281:         mainHandler.removeCallbacks(timeTrackerRunnable)
282:         mainHandler.post(timeTrackerRunnable)
283:     }
284: 
285:     private fun onTargetAppLeft() {
286:         if (currentTier == 0) return  // already idle, nothing to do
287: 
288:         Log.d(TAG, "Left target apps — saving state and hiding overlays")
289:         mainHandler.removeCallbacks(timeTrackerRunnable)
290: 
291:         sessionRepo.saveActiveState(
292:             cumulativeMs = cumulativeActiveMs,
293:             lastExitTimestamp = System.currentTimeMillis()
294:         )
295: 
296:         currentTier = 0
297:         overlayManager.hideAll()
298:     }
299: 
300:     /**
301:      * Bug B fix — called AFTER showTier1() so it can safely add grayscale/task
302:      * on top without fighting hideGrayscale(). Sets currentTier correctly.
303:      */
304:     private fun restoreTierState() {
305:         when {
306:             cumulativeActiveMs < triggerTime -> {
307:                 // Tier 1 already set by caller — nothing to do
308:             }
309:             cumulativeActiveMs < triggerTime + TIER2_DURATION_MS + tier3ExtensionMs -> {
310:                 currentTier = 2
311:                 overlayManager.showTier2()
312:             }
313:             else -> {
314:                 // Step 4: Time-based Escalation Logic
315:                 val prefs = getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
316:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
317:                 
318:                 if (quizzesEnabled && currentTier != 3) {
319:                     currentTier = 3
320:                     mainHandler.post { overlayManager.showTier3 { onTaskSolved() } }
321:                 } else if (!quizzesEnabled && currentTier != 2) {
322:                     // If quizzes are disabled, cap at Tier 2
323:                     currentTier = 2
324:                     mainHandler.post { overlayManager.showTier2() }
325:                 }
326:             }
327:         }
328:     }
329: 
330:     // ─────────────────────────────────────────────────────────────────────────
331:     // Tier Evaluation (every second)
332:     // ─────────────────────────────────────────────────────────────────────────
333: 
334:     private fun evaluateTier() {
335:         val scaledTime = if (sessionRepo.isDemoMode()) cumulativeActiveMs * 60 else cumulativeActiveMs
336:         when {
337:             scaledTime >= triggerTime && currentTier < 2 -> {
338:                 Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
339:                 currentTier = 2
340:                 overlayManager.showTier2()
341:             }
342:             scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
343:                 val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
344:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
345:                 if (quizzesEnabled) {
346:                     Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
347:                     currentTier = 3
348:                     overlayManager.showTier3 { onTaskSolved() }
349:                 }
350:             }
351:         }
352:     }
353: 
354:     private fun onTaskSolved() {
355:         Log.d(TAG, "Task solved — +5 min Tier 2 extension")
356:         tier3ExtensionMs += TIER3_TASK_EXTENSION_MS
357:         currentTier = 2
358:         overlayManager.showTier2()
359:     }
360: 
361:     // ─────────────────────────────────────────────────────────────────────────
362:     // NLP Scanner
363:     // ─────────────────────────────────────────────────────────────────────────
364: 
365:     private fun scanForToxicContent() {
366:         if (toxicWordTriggered) return
367:         val rootNode = rootInActiveWindow ?: return
368:         try {
369:             val sb = StringBuilder()
370:             collectTextFromNode(rootNode, sb)
371:             val text = sb.toString().lowercase()
372:             for (word in toxicWords) {
373:                 if (text.contains(word)) {
374:                     Log.d(TAG, "NLP: '$word' detected → Tier 2")
375:                     toxicWordTriggered = true
376:                     if (currentTier < 2) { currentTier = 2; overlayManager.showTier2() }
377:                     break
378:                 }
379:             }
380:         } catch (e: Exception) {
381:             Log.w(TAG, "NLP exception: ${e.message}")
382:         } finally {
383:             @Suppress("DEPRECATION")
384:             rootNode.recycle()
385:         }
386:     }
387: 
388:     private fun collectTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
389:         if (node == null || depth > MAX_NLP_DEPTH) return
390:         node.text?.let { sb.append(it).append(' ') }
391:         node.contentDescription?.let { sb.append(it).append(' ') }
392:         for (i in 0 until node.childCount) collectTextFromNode(node.getChild(i), sb, depth + 1)
393:     }
394: 
395:     // ─────────────────────────────────────────────────────────────────────────
396:     // Cleanup
397:     // ─────────────────────────────────────────────────────────────────────────
398: 
399:     private fun cleanupOnExit() {
400:         currentForegroundPackage = ""
401:         currentTier = 0
402:         mainHandler.removeCallbacks(timeTrackerRunnable)
403:         if (cumulativeActiveMs > 0) {
404:             sessionRepo.saveActiveState(
405:                 cumulativeMs = cumulativeActiveMs,
406:                 lastExitTimestamp = System.currentTimeMillis()
407:             )
408:         }
409:         overlayManager.hideAll()
410:     }
411: 
412:     // ─────────────────────────────────────────────────────────────────────────
413:     // Constants
414:     // ─────────────────────────────────────────────────────────────────────────
415: 
416:     companion object {
417:         private const val TAG = "TethrService"
418: 
419:         val TARGET_PACKAGES = setOf(
420:             "com.instagram.android",
421:             "com.google.android.youtube",
422:             "com.facebook.katana"
423:         )
424:         
425:         val IGNORED_PACKAGES = setOf(
426:             "com.android.systemui",
427:             "com.bbk.systemui",        // Vivo / Funtouch OS Notification Shade
428:             "com.miui.securitycenter", // For POCO/Xiaomi screenshot UI
429:             "com.google.android.inputmethod.latin"
430:         )
431: 
432:         private const val TICK_MS = 1000L
433:         private const val TIER2_DURATION_MS = 5 * 60_000L
434:         private const val TIER3_TASK_EXTENSION_MS = 5 * 60_000L
435:         private const val NLP_SCAN_INTERVAL_MS = 2000L
436:         private const val MAX_NLP_DEPTH = 8
437:     }
438: }
439: 
The above content shows the entire, complete file contents of the requested file.

