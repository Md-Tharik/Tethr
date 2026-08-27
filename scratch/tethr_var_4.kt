Created At: 2026-08-20T19:20:56+05:30
Completed At: 2026-08-20T19:20:56+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 464
Total Bytes: 23427
Showing lines 1 to 464
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
155:                     // ONLY ignore system windows if they don't belong to our target apps!
156:                     // (Activity transitions from Recents can sometimes momentarily report as system windows)
157:                     if (pkg !in TARGET_PACKAGES) {
158:                         return
159:                     }
160:                 }
161: 
162:                 // Instant synchronous processing of package changes (0ms delay)
163:                 if (pkg.isNotEmpty() && pkg != currentForegroundPackage) {
164:                     handleWindowStateChange(pkg)
165:                 }
166:             }
167: 
168:             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
169:                 // FALLBACK: If we missed the window state change (e.g., resuming from Recents tab),
170:                 // but we see content updating in a target app, assume it is now in the foreground!
171:                 if (pkg in TARGET_PACKAGES && pkg != currentForegroundPackage) {
172:                     val windowType = event.source?.window?.type
173:                     // Make sure it's actually the app window updating, not a background process
174:                     if (windowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || windowType == null) {
175:                         Log.d(TAG, "Fallback: Detected $pkg via content change (missed state change)")
176:                         handleWindowStateChange(pkg)
177:                     }
178:                 }
179: 
180:                 if (pkg in TARGET_PACKAGES
181:                     && currentForegroundPackage in TARGET_PACKAGES
182:                     && currentTier in 1..2) {
183:                     val now = System.currentTimeMillis()
184:                     if (now - lastNlpScanTime >= NLP_SCAN_INTERVAL_MS) {
185:                         lastNlpScanTime = now
186:                         scanForToxicContent()
187:                     }
188:                 }
189:             }
190:         }
191:     }
192: 
193:     /**
194:      * Bug A fix — returns true for packages that are transient overlays drawn
195:      * ABOVE the current foreground app (keyboards, system UI, permission dialogs,
196:      * accessibility overlay windows, etc.) that are NOT real foreground app changes.
197:      *
198:      * Filtering rules (ordered cheapest-first):
199:      *  1. "android"          — core Android framework dialogs
200:      *  2. "com.android.systemui" — status bar, quick-settings, notifications
201:      *  3. Contains "inputmethod", ".ime.", "keyboard", "honeyboard", "bixby"
202:      *     — any soft keyboard. There are 50+ keyboard packages; substring match
203:      *     is the only sane cross-device approach.
204:      *  4. Tethr itself       — our own overlay windows fire events; ignore them.
205:      *
206:      * Launchers (home screen) are intentionally NOT filtered — we DO want to
207:      * detect when the user navigates home so we can call onTargetAppLeft().
208:      */
209:     private fun isTransientSystemPackage(pkg: String): Boolean {
210:         if (installedKeyboards.contains(pkg)) return true
211:         if (IGNORED_PACKAGES.contains(pkg)) return true
212: 
213:         return pkg == "android"
214:             || pkg.contains("systemui", ignoreCase = true)
215:             || pkg.contains("sysui", ignoreCase = true)
216:             || pkg.contains("volume", ignoreCase = true)
217:             || pkg.contains("notification", ignoreCase = true)
218:             || pkg.contains("statusbar", ignoreCase = true)
219:             || pkg.contains("panel", ignoreCase = true)
220:             || pkg == packageName                          // Tethr itself
221:             || pkg.contains("inputmethod", ignoreCase = true)
222:             || pkg.contains(".ime.", ignoreCase = true)
223:             || pkg.contains("keyboard", ignoreCase = true)
224:             || pkg.contains("honeyboard", ignoreCase = true)
225:             || pkg.contains("bixbyvision", ignoreCase = true)
226:             || pkg.contains("accessibility", ignoreCase = true)
227:     }
228: 
229:     // ─────────────────────────────────────────────────────────────────────────
230:     // Window State Change Handling (debounced)
231:     // ─────────────────────────────────────────────────────────────────────────
232: 
233:     private fun handleWindowStateChange(packageName: String) {
234:         if (packageName in TARGET_PACKAGES) {
235:             currentForegroundPackage = packageName
236:             Log.d(TAG, "Foreground → $packageName")
237:             onTargetAppOpened(packageName)
238:         } else {
239:             currentForegroundPackage = packageName
240:             Log.d(TAG, "Foreground → $packageName")
241: 
242:             // TEMPORARY DEBUG: Show the user what package triggered the exit
243:             android.os.Handler(android.os.Looper.getMainLooper()).post {
244:                 android.widget.Toast.makeText(this, "Tethr hidden by: $packageName", android.widget.Toast.LENGTH_LONG).show()
245:             }
246:             onTargetAppLeft()
247:         }
248:     }
249: 
250:     private fun onTargetAppOpened(packageName: String) {
251:         // Bug C fix — if we're already tracking this package at a valid tier,
252:         // another activity transition within Instagram fired. Skip re-init.
253:         if (currentTier > 0) {
254:             Log.d(TAG, "Re-entry ignored — already active at Tier $currentTier")
255:             return
256:         }
257: 
258:         Log.d(TAG, "Target app opened: $packageName")
259:         toxicWordTriggered = false
260:         triggerTime = sessionRepo.computeTriggerTime()
261:         Log.d(TAG, "TriggerTime: ${triggerTime / 1000}s")
262: 
263:         val (savedCumulative, lastExitTimestamp) = sessionRepo.loadActiveState()
264:         val now = System.currentTimeMillis()
265: 
266:         if (lastExitTimestamp > 0 && (now - lastExitTimestamp) >= SessionRepository.COOLDOWN_MS) {
267:             // ── Fresh session (≥10 min away) ──────────────────────────────
268:             Log.d(TAG, "Cooldown expired — fresh session")
269:             if (savedCumulative > 0) {
270:                 sessionRepo.recordSession(savedCumulative)
271:                 sessionRepo.incrementWeekSessionCount()
272:             }
273:             cumulativeActiveMs = 0L
274:             tier3ExtensionMs = 0L
275:             currentTier = 1
276:             sessionRepo.resetActiveState()
277:             overlayManager.showTier1(0L)
278:         } else {
279:             // ── Resume session (<10 min away) ─────────────────────────────
280:             cumulativeActiveMs = savedCumulative
281:             Log.d(TAG, "Resuming at ${cumulativeActiveMs / 1000}s")
282:             
283:             // Base layer: Always show Tier 1 pill on resume
284:             currentTier = 1
285:             overlayManager.showTier1(cumulativeActiveMs)
286: 
287:             // CRITICAL: Explicitly re-evaluate and re-draw the UI state immediately
288:             if (cumulativeActiveMs >= triggerTime) {
289:                 currentTier = 2
290:                 overlayManager.showTier2()
291:             }
292:             
293:             if (prefs.getBoolean("PENDING_QUIZ", false)) {
294:                 Log.d(TAG, "Trap triggered: PENDING_QUIZ is true")
295:                 currentTier = 3
296:                 overlayManager.showTier3 { onTaskSolved() }
297:             } else if (cumulativeActiveMs >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs) {
298:                 currentTier = 3
299:                 overlayManager.showTier3 { onTaskSolved() }
300:             }
301: 
302:             // Ensure the background timer resumes ticking.
303:             sessionStartTime = System.currentTimeMillis()
304:             mainHandler.removeCallbacks(timeTrackerRunnable)
305:             mainHandler.post(timeTrackerRunnable)
306:         }
307: 
308:         sessionStartTime = System.currentTimeMillis()
309:         mainHandler.removeCallbacks(timeTrackerRunnable)
310:         mainHandler.post(timeTrackerRunnable)
311:     }
312: 
313:     private fun onTargetAppLeft() {
314:         if (currentTier == 0) return  // already idle, nothing to do
315: 
316:         Log.d(TAG, "Left target apps — saving state and hiding overlays")
317:         mainHandler.removeCallbacks(timeTrackerRunnable)
318: 
319:         sessionRepo.saveActiveState(
320:             cumulativeMs = cumulativeActiveMs,
321:             lastExitTimestamp = System.currentTimeMillis()
322:         )
323: 
324:         currentTier = 0
325:         overlayManager.hideAll()
326:     }
327: 
328:     /**
329:      * Bug B fix — called AFTER showTier1() so it can safely add grayscale/task
330:      * on top without fighting hideGrayscale(). Sets currentTier correctly.
331:      */
332:     private fun restoreTierState() {
333:         when {
334:             cumulativeActiveMs < triggerTime -> {
335:                 // Tier 1 already set by caller — nothing to do
336:             }
337:             cumulativeActiveMs < triggerTime + TIER2_DURATION_MS + tier3ExtensionMs -> {
338:                 currentTier = 2
339:                 overlayManager.showTier2()
340:             }
341:             else -> {
342:                 // Step 4: Time-based Escalation Logic
343:                 val prefs = getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
344:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
345:                 
346:                 if (quizzesEnabled && currentTier != 3) {
347:                     currentTier = 3
348:                     mainHandler.post { overlayManager.showTier3 { onTaskSolved() } }
349:                 } else if (!quizzesEnabled && currentTier != 2) {
350:                     // If quizzes are disabled, cap at Tier 2
351:                     currentTier = 2
352:                     mainHandler.post { overlayManager.showTier2() }
353:                 }
354:             }
355:         }
356:     }
357: 
358:     // ─────────────────────────────────────────────────────────────────────────
359:     // Tier Evaluation (every second)
360:     // ─────────────────────────────────────────────────────────────────────────
361: 
362:     private fun evaluateTier() {
363:         val scaledTime = if (sessionRepo.isDemoMode()) cumulativeActiveMs * 60 else cumulativeActiveMs
364:         when {
365:             scaledTime >= triggerTime && currentTier < 2 -> {
366:                 Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
367:                 currentTier = 2
368:                 overlayManager.showTier2()
369:             }
370:             scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
371:                 val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
372:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
373:                 if (quizzesEnabled) {
374:                     Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
375:                     currentTier = 3
376:                     overlayManager.showTier3 { onTaskSolved() }
377:                 }
378:             }
379:         }
380:     }
381: 
382:     private fun onTaskSolved() {
383:         Log.d(TAG, "Task solved — +5 min Tier 2 extension")
384:         tier3ExtensionMs += TIER3_TASK_EXTENSION_MS
385:         currentTier = 2
386:         overlayManager.showTier2()
387:     }
388: 
389:     // ─────────────────────────────────────────────────────────────────────────
390:     // NLP Scanner
391:     // ─────────────────────────────────────────────────────────────────────────
392: 
393:     private fun scanForToxicContent() {
394:         if (toxicWordTriggered) return
395:         val rootNode = rootInActiveWindow ?: return
396:         try {
397:             val sb = StringBuilder()
398:             collectTextFromNode(rootNode, sb)
399:             val text = sb.toString().lowercase()
400:             for (word in toxicWords) {
401:                 if (text.contains(word)) {
402:                     Log.d(TAG, "NLP: '$word' detected → Tier 2")
403:                     toxicWordTriggered = true
404:                     if (currentTier < 2) { currentTier = 2; overlayManager.showTier2() }
405:                     break
406:                 }
407:             }
408:         } catch (e: Exception) {
409:             Log.w(TAG, "NLP exception: ${e.message}")
410:         }
411:     }
412: 
413:     private fun collectTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
414:         if (node == null || depth > MAX_NLP_DEPTH) return
415:         node.text?.let { sb.append(it).append(' ') }
416:         node.contentDescription?.let { sb.append(it).append(' ') }
417:         for (i in 0 until node.childCount) collectTextFromNode(node.getChild(i), sb, depth + 1)
418:     }
419: 
420:     // ─────────────────────────────────────────────────────────────────────────
421:     // Cleanup
422:     // ─────────────────────────────────────────────────────────────────────────
423: 
424:     private fun cleanupOnExit() {
425:         currentForegroundPackage = ""
426:         currentTier = 0
427:         mainHandler.removeCallbacks(timeTrackerRunnable)
428:         if (cumulativeActiveMs > 0) {
429:             sessionRepo.saveActiveState(
430:                 cumulativeMs = cumulativeActiveMs,
431:                 lastExitTimestamp = System.currentTimeMillis()
432:             )
433:         }
434:         overlayManager.hideAll()
435:     }
436: 
437:     // ─────────────────────────────────────────────────────────────────────────
438:     // Constants
439:     // ─────────────────────────────────────────────────────────────────────────
440: 
441:     companion object {
442:         private const val TAG = "TethrService"
443: 
444:         val TARGET_PACKAGES = setOf(
445:             "com.instagram.android",
446:             "com.google.android.youtube",
447:             "com.facebook.katana"
448:         )
449:         
450:         val IGNORED_PACKAGES = setOf(
451:             "com.android.systemui",
452:             "com.bbk.systemui",        // Vivo / Funtouch OS Notification Shade
453:             "com.miui.securitycenter", // For POCO/Xiaomi screenshot UI
454:             "com.google.android.inputmethod.latin"
455:         )
456: 
457:         private const val TICK_MS = 1000L
458:         private const val TIER2_DURATION_MS = 5 * 60_000L
459:         private const val TIER3_TASK_EXTENSION_MS = 5 * 60_000L
460:         private const val NLP_SCAN_INTERVAL_MS = 2000L
461:         private const val MAX_NLP_DEPTH = 8
462:     }
463: }
464: 
The above content shows the entire, complete file contents of the requested file.

