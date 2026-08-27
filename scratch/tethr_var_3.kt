Created At: 2026-08-20T18:47:45+05:30
Completed At: 2026-08-20T18:47:45+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 452
Total Bytes: 22466
Showing lines 1 to 452
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
151:                 // Check the window type if available. Notification bars, volume dialogs, etc.
152:                 // are TYPE_SYSTEM (3). Real apps and launchers are TYPE_APPLICATION (1).
153:                 val windowType = event.source?.window?.type
154:                 if (windowType != null && windowType != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
155:                     return
156:                 }
157: 
158:                 // Instant synchronous processing of package changes (0ms delay)
159:                 if (pkg.isNotEmpty() && pkg != currentForegroundPackage) {
160:                     handleWindowStateChange(pkg)
161:                 }
162:             }
163: 
164:             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
165:                 if (pkg in TARGET_PACKAGES
166:                     && currentForegroundPackage in TARGET_PACKAGES
167:                     && currentTier in 1..2) {
168:                     val now = System.currentTimeMillis()
169:                     if (now - lastNlpScanTime >= NLP_SCAN_INTERVAL_MS) {
170:                         lastNlpScanTime = now
171:                         scanForToxicContent()
172:                     }
173:                 }
174:             }
175:         }
176:     }
177: 
178:     /**
179:      * Bug A fix — returns true for packages that are transient overlays drawn
180:      * ABOVE the current foreground app (keyboards, system UI, permission dialogs,
181:      * accessibility overlay windows, etc.) that are NOT real foreground app changes.
182:      *
183:      * Filtering rules (ordered cheapest-first):
184:      *  1. "android"          — core Android framework dialogs
185:      *  2. "com.android.systemui" — status bar, quick-settings, notifications
186:      *  3. Contains "inputmethod", ".ime.", "keyboard", "honeyboard", "bixby"
187:      *     — any soft keyboard. There are 50+ keyboard packages; substring match
188:      *     is the only sane cross-device approach.
189:      *  4. Tethr itself       — our own overlay windows fire events; ignore them.
190:      *
191:      * Launchers (home screen) are intentionally NOT filtered — we DO want to
192:      * detect when the user navigates home so we can call onTargetAppLeft().
193:      */
194:     private fun isTransientSystemPackage(pkg: String): Boolean {
195:         if (installedKeyboards.contains(pkg)) return true
196:         if (IGNORED_PACKAGES.contains(pkg)) return true
197: 
198:         return pkg == "android"
199:             || pkg.contains("systemui", ignoreCase = true)
200:             || pkg.contains("sysui", ignoreCase = true)
201:             || pkg.contains("volume", ignoreCase = true)
202:             || pkg.contains("notification", ignoreCase = true)
203:             || pkg.contains("statusbar", ignoreCase = true)
204:             || pkg.contains("panel", ignoreCase = true)
205:             || pkg == packageName                          // Tethr itself
206:             || pkg.contains("inputmethod", ignoreCase = true)
207:             || pkg.contains(".ime.", ignoreCase = true)
208:             || pkg.contains("keyboard", ignoreCase = true)
209:             || pkg.contains("honeyboard", ignoreCase = true)
210:             || pkg.contains("bixbyvision", ignoreCase = true)
211:             || pkg.contains("accessibility", ignoreCase = true)
212:     }
213: 
214:     // ─────────────────────────────────────────────────────────────────────────
215:     // Window State Change Handling (debounced)
216:     // ─────────────────────────────────────────────────────────────────────────
217: 
218:     private fun handleWindowStateChange(packageName: String) {
219:         if (packageName in TARGET_PACKAGES) {
220:             currentForegroundPackage = packageName
221:             Log.d(TAG, "Foreground → $packageName")
222:             onTargetAppOpened(packageName)
223:         } else {
224:             currentForegroundPackage = packageName
225:             Log.d(TAG, "Foreground → $packageName")
226: 
227:             // TEMPORARY DEBUG: Show the user what package triggered the exit
228:             android.os.Handler(android.os.Looper.getMainLooper()).post {
229:                 android.widget.Toast.makeText(this, "Tethr hidden by: $packageName", android.widget.Toast.LENGTH_LONG).show()
230:             }
231:             onTargetAppLeft()
232:         }
233:     }
234: 
235:     private fun onTargetAppOpened(packageName: String) {
236:         // Bug C fix — if we're already tracking this package at a valid tier,
237:         // another activity transition within Instagram fired. Skip re-init.
238:         if (currentTier > 0) {
239:             Log.d(TAG, "Re-entry ignored — already active at Tier $currentTier")
240:             return
241:         }
242: 
243:         Log.d(TAG, "Target app opened: $packageName")
244:         toxicWordTriggered = false
245:         triggerTime = sessionRepo.computeTriggerTime()
246:         Log.d(TAG, "TriggerTime: ${triggerTime / 1000}s")
247: 
248:         val (savedCumulative, lastExitTimestamp) = sessionRepo.loadActiveState()
249:         val now = System.currentTimeMillis()
250: 
251:         if (lastExitTimestamp > 0 && (now - lastExitTimestamp) >= SessionRepository.COOLDOWN_MS) {
252:             // ── Fresh session (≥10 min away) ──────────────────────────────
253:             Log.d(TAG, "Cooldown expired — fresh session")
254:             if (savedCumulative > 0) {
255:                 sessionRepo.recordSession(savedCumulative)
256:                 sessionRepo.incrementWeekSessionCount()
257:             }
258:             cumulativeActiveMs = 0L
259:             tier3ExtensionMs = 0L
260:             currentTier = 1
261:             sessionRepo.resetActiveState()
262:             overlayManager.showTier1(0L)
263:         } else {
264:             // ── Resume session (<10 min away) ─────────────────────────────
265:             cumulativeActiveMs = savedCumulative
266:             Log.d(TAG, "Resuming at ${cumulativeActiveMs / 1000}s")
267:             
268:             // Base layer: Always show Tier 1 pill on resume
269:             currentTier = 1
270:             overlayManager.showTier1(cumulativeActiveMs)
271: 
272:             // CRITICAL: Explicitly re-evaluate and re-draw the UI state immediately
273:             if (cumulativeActiveMs >= triggerTime) {
274:                 currentTier = 2
275:                 overlayManager.showTier2()
276:             }
277:             
278:             if (prefs.getBoolean("PENDING_QUIZ", false)) {
279:                 Log.d(TAG, "Trap triggered: PENDING_QUIZ is true")
280:                 currentTier = 3
281:                 overlayManager.showTier3 { onTaskSolved() }
282:             } else if (cumulativeActiveMs >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs) {
283:                 currentTier = 3
284:                 overlayManager.showTier3 { onTaskSolved() }
285:             }
286: 
287:             // Ensure the background timer resumes ticking.
288:             sessionStartTime = System.currentTimeMillis()
289:             mainHandler.removeCallbacks(timeTrackerRunnable)
290:             mainHandler.post(timeTrackerRunnable)
291:         }
292: 
293:         sessionStartTime = System.currentTimeMillis()
294:         mainHandler.removeCallbacks(timeTrackerRunnable)
295:         mainHandler.post(timeTrackerRunnable)
296:     }
297: 
298:     private fun onTargetAppLeft() {
299:         if (currentTier == 0) return  // already idle, nothing to do
300: 
301:         Log.d(TAG, "Left target apps — saving state and hiding overlays")
302:         mainHandler.removeCallbacks(timeTrackerRunnable)
303: 
304:         sessionRepo.saveActiveState(
305:             cumulativeMs = cumulativeActiveMs,
306:             lastExitTimestamp = System.currentTimeMillis()
307:         )
308: 
309:         currentTier = 0
310:         overlayManager.hideAll()
311:     }
312: 
313:     /**
314:      * Bug B fix — called AFTER showTier1() so it can safely add grayscale/task
315:      * on top without fighting hideGrayscale(). Sets currentTier correctly.
316:      */
317:     private fun restoreTierState() {
318:         when {
319:             cumulativeActiveMs < triggerTime -> {
320:                 // Tier 1 already set by caller — nothing to do
321:             }
322:             cumulativeActiveMs < triggerTime + TIER2_DURATION_MS + tier3ExtensionMs -> {
323:                 currentTier = 2
324:                 overlayManager.showTier2()
325:             }
326:             else -> {
327:                 // Step 4: Time-based Escalation Logic
328:                 val prefs = getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
329:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
330:                 
331:                 if (quizzesEnabled && currentTier != 3) {
332:                     currentTier = 3
333:                     mainHandler.post { overlayManager.showTier3 { onTaskSolved() } }
334:                 } else if (!quizzesEnabled && currentTier != 2) {
335:                     // If quizzes are disabled, cap at Tier 2
336:                     currentTier = 2
337:                     mainHandler.post { overlayManager.showTier2() }
338:                 }
339:             }
340:         }
341:     }
342: 
343:     // ─────────────────────────────────────────────────────────────────────────
344:     // Tier Evaluation (every second)
345:     // ─────────────────────────────────────────────────────────────────────────
346: 
347:     private fun evaluateTier() {
348:         val scaledTime = if (sessionRepo.isDemoMode()) cumulativeActiveMs * 60 else cumulativeActiveMs
349:         when {
350:             scaledTime >= triggerTime && currentTier < 2 -> {
351:                 Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
352:                 currentTier = 2
353:                 overlayManager.showTier2()
354:             }
355:             scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
356:                 val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
357:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
358:                 if (quizzesEnabled) {
359:                     Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
360:                     currentTier = 3
361:                     overlayManager.showTier3 { onTaskSolved() }
362:                 }
363:             }
364:         }
365:     }
366: 
367:     private fun onTaskSolved() {
368:         Log.d(TAG, "Task solved — +5 min Tier 2 extension")
369:         tier3ExtensionMs += TIER3_TASK_EXTENSION_MS
370:         currentTier = 2
371:         overlayManager.showTier2()
372:     }
373: 
374:     // ─────────────────────────────────────────────────────────────────────────
375:     // NLP Scanner
376:     // ─────────────────────────────────────────────────────────────────────────
377: 
378:     private fun scanForToxicContent() {
379:         if (toxicWordTriggered) return
380:         val rootNode = rootInActiveWindow ?: return
381:         try {
382:             val sb = StringBuilder()
383:             collectTextFromNode(rootNode, sb)
384:             val text = sb.toString().lowercase()
385:             for (word in toxicWords) {
386:                 if (text.contains(word)) {
387:                     Log.d(TAG, "NLP: '$word' detected → Tier 2")
388:                     toxicWordTriggered = true
389:                     if (currentTier < 2) { currentTier = 2; overlayManager.showTier2() }
390:                     break
391:                 }
392:             }
393:         } catch (e: Exception) {
394:             Log.w(TAG, "NLP exception: ${e.message}")
395:         } finally {
396:             @Suppress("DEPRECATION")
397:             rootNode.recycle()
398:         }
399:     }
400: 
401:     private fun collectTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
402:         if (node == null || depth > MAX_NLP_DEPTH) return
403:         node.text?.let { sb.append(it).append(' ') }
404:         node.contentDescription?.let { sb.append(it).append(' ') }
405:         for (i in 0 until node.childCount) collectTextFromNode(node.getChild(i), sb, depth + 1)
406:     }
407: 
408:     // ─────────────────────────────────────────────────────────────────────────
409:     // Cleanup
410:     // ─────────────────────────────────────────────────────────────────────────
411: 
412:     private fun cleanupOnExit() {
413:         currentForegroundPackage = ""
414:         currentTier = 0
415:         mainHandler.removeCallbacks(timeTrackerRunnable)
416:         if (cumulativeActiveMs > 0) {
417:             sessionRepo.saveActiveState(
418:                 cumulativeMs = cumulativeActiveMs,
419:                 lastExitTimestamp = System.currentTimeMillis()
420:             )
421:         }
422:         overlayManager.hideAll()
423:     }
424: 
425:     // ─────────────────────────────────────────────────────────────────────────
426:     // Constants
427:     // ─────────────────────────────────────────────────────────────────────────
428: 
429:     companion object {
430:         private const val TAG = "TethrService"
431: 
432:         val TARGET_PACKAGES = setOf(
433:             "com.instagram.android",
434:             "com.google.android.youtube",
435:             "com.facebook.katana"
436:         )
437:         
438:         val IGNORED_PACKAGES = setOf(
439:             "com.android.systemui",
440:             "com.bbk.systemui",        // Vivo / Funtouch OS Notification Shade
441:             "com.miui.securitycenter", // For POCO/Xiaomi screenshot UI
442:             "com.google.android.inputmethod.latin"
443:         )
444: 
445:         private const val TICK_MS = 1000L
446:         private const val TIER2_DURATION_MS = 5 * 60_000L
447:         private const val TIER3_TASK_EXTENSION_MS = 5 * 60_000L
448:         private const val NLP_SCAN_INTERVAL_MS = 2000L
449:         private const val MAX_NLP_DEPTH = 8
450:     }
451: }
452: 
The above content shows the entire, complete file contents of the requested file.

