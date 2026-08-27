Created At: 2026-08-20T18:36:59+05:30
Completed At: 2026-08-20T18:36:59+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 443
Total Bytes: 21935
Showing lines 1 to 443
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
189:         if (IGNORED_PACKAGES.contains(pkg)) return true
190: 
191:         return pkg == "android"
192:             || pkg.contains("systemui", ignoreCase = true)
193:             || pkg.contains("sysui", ignoreCase = true)
194:             || pkg.contains("volume", ignoreCase = true)
195:             || pkg.contains("notification", ignoreCase = true)
196:             || pkg.contains("statusbar", ignoreCase = true)
197:             || pkg.contains("panel", ignoreCase = true)
198:             || pkg == packageName                          // Tethr itself
199:             || pkg.contains("inputmethod", ignoreCase = true)
200:             || pkg.contains(".ime.", ignoreCase = true)
201:             || pkg.contains("keyboard", ignoreCase = true)
202:             || pkg.contains("honeyboard", ignoreCase = true)
203:             || pkg.contains("bixbyvision", ignoreCase = true)
204:             || pkg.contains("accessibility", ignoreCase = true)
205:     }
206: 
207:     // ─────────────────────────────────────────────────────────────────────────
208:     // Window State Change Handling (debounced)
209:     // ─────────────────────────────────────────────────────────────────────────
210: 
211:     private fun handleWindowStateChange(packageName: String) {
212:         currentForegroundPackage = packageName
213:         Log.d(TAG, "Foreground → $packageName")
214: 
215:         if (packageName in TARGET_PACKAGES) {
216:             onTargetAppOpened(packageName)
217:         } else {
218:             // TEMPORARY DEBUG: Show the user what package triggered the exit
219:             android.os.Handler(android.os.Looper.getMainLooper()).post {
220:                 android.widget.Toast.makeText(this, "Tethr hidden by: $packageName", android.widget.Toast.LENGTH_LONG).show()
221:             }
222:             onTargetAppLeft()
223:         }
224:     }
225: 
226:     private fun onTargetAppOpened(packageName: String) {
227:         // Bug C fix — if we're already tracking this package at a valid tier,
228:         // another activity transition within Instagram fired. Skip re-init.
229:         if (currentTier > 0) {
230:             Log.d(TAG, "Re-entry ignored — already active at Tier $currentTier")
231:             return
232:         }
233: 
234:         Log.d(TAG, "Target app opened: $packageName")
235:         toxicWordTriggered = false
236:         triggerTime = sessionRepo.computeTriggerTime()
237:         Log.d(TAG, "TriggerTime: ${triggerTime / 1000}s")
238: 
239:         val (savedCumulative, lastExitTimestamp) = sessionRepo.loadActiveState()
240:         val now = System.currentTimeMillis()
241: 
242:         if (lastExitTimestamp > 0 && (now - lastExitTimestamp) >= SessionRepository.COOLDOWN_MS) {
243:             // ── Fresh session (≥10 min away) ──────────────────────────────
244:             Log.d(TAG, "Cooldown expired — fresh session")
245:             if (savedCumulative > 0) {
246:                 sessionRepo.recordSession(savedCumulative)
247:                 sessionRepo.incrementWeekSessionCount()
248:             }
249:             cumulativeActiveMs = 0L
250:             tier3ExtensionMs = 0L
251:             currentTier = 1
252:             sessionRepo.resetActiveState()
253:             overlayManager.showTier1(0L)
254:         } else {
255:             // ── Resume session (<10 min away) ─────────────────────────────
256:             cumulativeActiveMs = savedCumulative
257:             Log.d(TAG, "Resuming at ${cumulativeActiveMs / 1000}s")
258:             
259:             // Base layer: Always show Tier 1 pill on resume
260:             currentTier = 1
261:             overlayManager.showTier1(cumulativeActiveMs)
262: 
263:             // CRITICAL: Explicitly re-evaluate and re-draw the UI state immediately
264:             if (cumulativeActiveMs >= triggerTime) {
265:                 currentTier = 2
266:                 overlayManager.showTier2()
267:             }
268:             
269:             if (prefs.getBoolean("PENDING_QUIZ", false)) {
270:                 Log.d(TAG, "Trap triggered: PENDING_QUIZ is true")
271:                 currentTier = 3
272:                 overlayManager.showTier3 { onTaskSolved() }
273:             } else if (cumulativeActiveMs >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs) {
274:                 currentTier = 3
275:                 overlayManager.showTier3 { onTaskSolved() }
276:             }
277: 
278:             // Ensure the background timer resumes ticking.
279:             sessionStartTime = System.currentTimeMillis()
280:             mainHandler.removeCallbacks(timeTrackerRunnable)
281:             mainHandler.post(timeTrackerRunnable)
282:         }
283: 
284:         sessionStartTime = System.currentTimeMillis()
285:         mainHandler.removeCallbacks(timeTrackerRunnable)
286:         mainHandler.post(timeTrackerRunnable)
287:     }
288: 
289:     private fun onTargetAppLeft() {
290:         if (currentTier == 0) return  // already idle, nothing to do
291: 
292:         Log.d(TAG, "Left target apps — saving state and hiding overlays")
293:         mainHandler.removeCallbacks(timeTrackerRunnable)
294: 
295:         sessionRepo.saveActiveState(
296:             cumulativeMs = cumulativeActiveMs,
297:             lastExitTimestamp = System.currentTimeMillis()
298:         )
299: 
300:         currentTier = 0
301:         overlayManager.hideAll()
302:     }
303: 
304:     /**
305:      * Bug B fix — called AFTER showTier1() so it can safely add grayscale/task
306:      * on top without fighting hideGrayscale(). Sets currentTier correctly.
307:      */
308:     private fun restoreTierState() {
309:         when {
310:             cumulativeActiveMs < triggerTime -> {
311:                 // Tier 1 already set by caller — nothing to do
312:             }
313:             cumulativeActiveMs < triggerTime + TIER2_DURATION_MS + tier3ExtensionMs -> {
314:                 currentTier = 2
315:                 overlayManager.showTier2()
316:             }
317:             else -> {
318:                 // Step 4: Time-based Escalation Logic
319:                 val prefs = getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
320:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
321:                 
322:                 if (quizzesEnabled && currentTier != 3) {
323:                     currentTier = 3
324:                     mainHandler.post { overlayManager.showTier3 { onTaskSolved() } }
325:                 } else if (!quizzesEnabled && currentTier != 2) {
326:                     // If quizzes are disabled, cap at Tier 2
327:                     currentTier = 2
328:                     mainHandler.post { overlayManager.showTier2() }
329:                 }
330:             }
331:         }
332:     }
333: 
334:     // ─────────────────────────────────────────────────────────────────────────
335:     // Tier Evaluation (every second)
336:     // ─────────────────────────────────────────────────────────────────────────
337: 
338:     private fun evaluateTier() {
339:         val scaledTime = if (sessionRepo.isDemoMode()) cumulativeActiveMs * 60 else cumulativeActiveMs
340:         when {
341:             scaledTime >= triggerTime && currentTier < 2 -> {
342:                 Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
343:                 currentTier = 2
344:                 overlayManager.showTier2()
345:             }
346:             scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
347:                 val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
348:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
349:                 if (quizzesEnabled) {
350:                     Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
351:                     currentTier = 3
352:                     overlayManager.showTier3 { onTaskSolved() }
353:                 }
354:             }
355:         }
356:     }
357: 
358:     private fun onTaskSolved() {
359:         Log.d(TAG, "Task solved — +5 min Tier 2 extension")
360:         tier3ExtensionMs += TIER3_TASK_EXTENSION_MS
361:         currentTier = 2
362:         overlayManager.showTier2()
363:     }
364: 
365:     // ─────────────────────────────────────────────────────────────────────────
366:     // NLP Scanner
367:     // ─────────────────────────────────────────────────────────────────────────
368: 
369:     private fun scanForToxicContent() {
370:         if (toxicWordTriggered) return
371:         val rootNode = rootInActiveWindow ?: return
372:         try {
373:             val sb = StringBuilder()
374:             collectTextFromNode(rootNode, sb)
375:             val text = sb.toString().lowercase()
376:             for (word in toxicWords) {
377:                 if (text.contains(word)) {
378:                     Log.d(TAG, "NLP: '$word' detected → Tier 2")
379:                     toxicWordTriggered = true
380:                     if (currentTier < 2) { currentTier = 2; overlayManager.showTier2() }
381:                     break
382:                 }
383:             }
384:         } catch (e: Exception) {
385:             Log.w(TAG, "NLP exception: ${e.message}")
386:         } finally {
387:             @Suppress("DEPRECATION")
388:             rootNode.recycle()
389:         }
390:     }
391: 
392:     private fun collectTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
393:         if (node == null || depth > MAX_NLP_DEPTH) return
394:         node.text?.let { sb.append(it).append(' ') }
395:         node.contentDescription?.let { sb.append(it).append(' ') }
396:         for (i in 0 until node.childCount) collectTextFromNode(node.getChild(i), sb, depth + 1)
397:     }
398: 
399:     // ─────────────────────────────────────────────────────────────────────────
400:     // Cleanup
401:     // ─────────────────────────────────────────────────────────────────────────
402: 
403:     private fun cleanupOnExit() {
404:         currentForegroundPackage = ""
405:         currentTier = 0
406:         mainHandler.removeCallbacks(timeTrackerRunnable)
407:         if (cumulativeActiveMs > 0) {
408:             sessionRepo.saveActiveState(
409:                 cumulativeMs = cumulativeActiveMs,
410:                 lastExitTimestamp = System.currentTimeMillis()
411:             )
412:         }
413:         overlayManager.hideAll()
414:     }
415: 
416:     // ─────────────────────────────────────────────────────────────────────────
417:     // Constants
418:     // ─────────────────────────────────────────────────────────────────────────
419: 
420:     companion object {
421:         private const val TAG = "TethrService"
422: 
423:         val TARGET_PACKAGES = setOf(
424:             "com.instagram.android",
425:             "com.google.android.youtube",
426:             "com.facebook.katana"
427:         )
428:         
429:         val IGNORED_PACKAGES = setOf(
430:             "com.android.systemui",
431:             "com.bbk.systemui",        // Vivo / Funtouch OS Notification Shade
432:             "com.miui.securitycenter", // For POCO/Xiaomi screenshot UI
433:             "com.google.android.inputmethod.latin"
434:         )
435: 
436:         private const val TICK_MS = 1000L
437:         private const val TIER2_DURATION_MS = 5 * 60_000L
438:         private const val TIER3_TASK_EXTENSION_MS = 5 * 60_000L
439:         private const val NLP_SCAN_INTERVAL_MS = 2000L
440:         private const val MAX_NLP_DEPTH = 8
441:     }
442: }
443: 
The above content shows the entire, complete file contents of the requested file.

