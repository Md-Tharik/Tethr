Created At: 2026-08-27T20:31:29+05:30
Completed At: 2026-08-27T20:31:29+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/TethrAccessibilityService.kt`
Total Lines: 589
Total Bytes: 32087
Showing lines 1 to 589
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
55:     private var popupsSolvedCount = 0
56: 
57:     private val installedKeyboards: Set<String> by lazy {
58:         val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
59:         imm.inputMethodList.map { it.packageName }.toSet()
60:     }
61: 
62:     // ————————————————————————————————————————————————————————————————————————————
63:     // Single source of truth — updated only when a non-transient package change
64:     // is confirmed after the 1000ms debounce.
65:     private var currentForegroundPackage: String = ""
66: 
67:     // ————————————————————————————————————————————————————————————————————————————
68:     private lateinit var overlayManager: OverlayManager
69:     private lateinit var sessionRepo: SessionRepository
70:     private lateinit var prefs: SharedPreferences
71:     private val mainHandler = Handler(Looper.getMainLooper())
72: 
73:     // ————————————————————————————————————————————————————————————————————————————
74:     private val timeTrackerRunnable = object : Runnable {
75:         override fun run() {
76:             // Leak guard — if a target app isn't foreground, stop and clean up
77:             if (currentForegroundPackage !in TARGET_PACKAGES) {
78:                 Log.d(TAG, "Tick leak: $currentForegroundPackage not in whitelist → hideAll()")
79:                 overlayManager.hideAll()
80:                 return  // do NOT re-post
81:             }
82:             cumulativeActiveMs += TICK_MS
83:             evaluateTier()
84:             overlayManager.updateTime(cumulativeActiveMs)
85:             sessionRepo.saveActiveState(cumulativeMs = cumulativeActiveMs, lastExitTimestamp = 0L)
86:             mainHandler.postDelayed(this, TICK_MS)
87:         }
88:     }
89: 
90:     // ────────────────────────────────────────────────────────────────────────────
91:     private val screenOffReceiver = object : BroadcastReceiver() {
92:         override fun onReceive(context: Context, intent: Intent) {
93:             when (intent.action) {
94:                 Intent.ACTION_SCREEN_OFF -> {
95:                     Log.d(TAG, "Screen OFF → handling as app exit")
96:                     if (currentForegroundPackage.isNotEmpty()) {
97:                         currentForegroundPackage = ""
98:                         onTargetAppLeft()
99:                     } else {
100:                         overlayManager.hideAll()
101:                     }
102:                 }
103:                 Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
104:                     mainHandler.postDelayed({
105:                         try {
106:                             val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
107:                             Log.d(TAG, "Screen ON/Unlock → Active Window: $activePkg")
108:                             if (activePkg in TARGET_PACKAGES && currentForegroundPackage != activePkg) {
109:                                 currentForegroundPackage = activePkg
110:                                 onTargetAppOpened(activePkg)
111:                             }
112:                         } catch (e: Exception) {
113:                             Log.e(TAG, "Failed to get active window on screen on: ${e.message}")
114:                         }
115:                     }, 500)
116:                 }
117:             }
118:         }
119:     }
120: 
121:     // ── NLP toxic word list ──────────────────────────────────────────────
122:     private val toxicWords = setOf(
123:         "hate", "ugly", "rage", "stupid", "worst", "disgusting",
124:         "horrible", "trash", "loser", "dumb", "idiot", "kill",
125:         "die", "awful", "repulsive", "pathetic", "worthless",
126:         "scum", "filth", "nasty", "vile", "gross"
127:     )
128:     private var lastNlpScanTime = 0L
129: 
130:     // ────────────────────────────────────────────────────────────────────────────
131:     // Service Lifecycle
132:     // ────────────────────────────────────────────────────────────────────────────
133: 
134:     override fun onServiceConnected() {
135:         super.onServiceConnected()
136:         Log.d(TAG, "TethrAccessibilityService connected")
137:         prefs = getSharedPreferences("tethr_prefs", Context.MODE_PRIVATE)
138:         sessionRepo = SessionRepository(this)
139:         overlayManager = OverlayManager(this)
140:         val screenFilter = IntentFilter().apply {
141:             addAction(Intent.ACTION_SCREEN_OFF)
142:             addAction(Intent.ACTION_SCREEN_ON)
143:             addAction(Intent.ACTION_USER_PRESENT)  // fires after PIN/pattern/fingerprint unlock
144:         }
145:         registerReceiver(screenOffReceiver, screenFilter)
146:         try {
147:             androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, TethrForegroundService::class.java))
148:         } catch (e: Exception) {
149:             Log.e(TAG, "Failed to start foreground service: ${e.message}")
150:         }
151:     }
152: 
153:     override fun onDestroy() {
154:         super.onDestroy()
155:         Log.d(TAG, "Service destroyed")
156:         cleanupOnExit()
157:         try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
158:     }
159: 
160:     override fun onInterrupt() {
161:         Log.d(TAG, "Service interrupted")
162:         cleanupOnExit()
163:     }
164: 
165:     // ————————————————————————————————————————————————————————————————————————————
166:     // Accessibility Events
167:     // ————————————————————————————————————————————————————————————————————————————
168: 
169:     override fun onAccessibilityEvent(event: AccessibilityEvent?) {
170:         if (event == null) return
171:         val pkg = event.packageName?.toString() ?: ""
172: 
173:         when (event.eventType) {
174: 
175:             AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
176:                 val eventPkg = event.packageName?.toString() ?: ""
177:                 if (eventPkg.isEmpty()) return
178:                 if (eventPkg == packageName || eventPkg == "com.example.tethr") return
179: 
180:                 // 1. DIRECT HIT: Trust target apps immediately.
181:                 if (eventPkg in TARGET_PACKAGES) {
182:                     if (currentForegroundPackage != eventPkg) {
183:                         currentForegroundPackage = eventPkg
184:                         Log.d(TAG, "Foreground -> $eventPkg")
185:                         onTargetAppOpened(eventPkg)
186:                     }
187:                     return
188:                 }
189: 
190:                 // 2. LAUNCHER EXIT: Exiting to home screen explicitly
191:                 val isLauncher = eventPkg.contains("launcher", ignoreCase = true)
192:                         || eventPkg.contains("home", ignoreCase = true)
193: 
194:                 if (isLauncher) {
195:                     if (currentForegroundPackage.isNotEmpty()) {
196:                         currentForegroundPackage = ""
197:                         Log.d(TAG, "Foreground -> $eventPkg (Confirmed exit via home)")
198:                         onTargetAppLeft()
199:                     }
200:                     return
201:                 }
202: 
203:                 // 3. SYSTEM UI (The tricky part): Distinguish Notification Shade vs Gesture Swipe
204:                 val isSystemUiPkg = eventPkg == "android" || eventPkg.contains("systemui", ignoreCase = true) || eventPkg.contains("sysui", ignoreCase = true)
205:                 val isOemSystemUi = eventPkg.contains("miui", ignoreCase = true) || eventPkg.contains("coloros", ignoreCase = true) || eventPkg.contains("vivo", ignoreCase = true) || eventPkg.contains("oppo", ignoreCase = true)
206:                 
207:                 val className = event.className?.toString() ?: ""
208:                 val isVolumeOrNotificationClass = className.contains("Volume", ignoreCase = true) || className.contains("Notification", ignoreCase = true) || className.contains("Panel", ignoreCase = true)
209: 
210:                 if (isSystemUiPkg || isOemSystemUi || isVolumeOrNotificationClass) {
211:                     val isRecents = className.contains("Recents", ignoreCase = true) || className.contains("Overview", ignoreCase = true)
212: 
213:                     if (isRecents) {
214:                         // User opened Recent apps. Kill the pill instantly!
215:                         if (currentForegroundPackage.isNotEmpty()) {
216:                             currentForegroundPackage = ""
217:                             Log.d(TAG, "Foreground -> $eventPkg (Confirmed exit via recents)")
218:                             onTargetAppLeft()
219:                         }
220:                         return
221:                     }
222:                     
223:                     // For notification shades, volume sliders, permission dialogs, keep the pill alive!
224:                     return
225:                 }
226: 
227:                 // 4. POPUPS & COMMENTS: Keyboards and dialogs
228:                 val rootPkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
229:                 val isKeyboard = eventPkg.contains("inputmethod", ignoreCase = true)
230:                         || eventPkg.contains(".ime.", ignoreCase = true)
231:                         || eventPkg.contains("keyboard", ignoreCase = true)
232: 
233:                 if (rootPkg in TARGET_PACKAGES || isKeyboard) {
234:                     return
235:                 }
236: 
237:                 // 5. LEGITIMATE EXIT: We left the app for something else (e.g. Chrome).
238:                 if (currentForegroundPackage.isNotEmpty()) {
239:                     currentForegroundPackage = ""
240:                     Log.d(TAG, "Foreground -> $eventPkg (Confirmed exit)")
241:                     onTargetAppLeft()
242:                 }
243:             }
244: 
245:             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
246:                 // NLP Toxic Content Scanning (Only when active)
247: 
248:                 if (pkg in TARGET_PACKAGES
249:                     && currentForegroundPackage in TARGET_PACKAGES
250:                     && currentTier in 1..2) {
251:                     val now = System.currentTimeMillis()
252:                     if (now - lastNlpScanTime >= NLP_SCAN_INTERVAL_MS) {
253:                         lastNlpScanTime = now
254:                         scanForToxicContent()
255:                     }
256:                 }
257:             }
258:         }
259:     }
260: 
261:     /**
262:      * Bug A fix â€” returns true for packages that are transient overlays drawn
263:      * ABOVE the current foreground app (keyboards, system UI, permission dialogs,
264:      * accessibility overlay windows, etc.) that are NOT real foreground app changes.
265:      *
266:      * Filtering rules (ordered cheapest-first):
267:      *  1. "android"          â€” core Android framework dialogs
268:      *  2. "com.android.systemui" â€” status bar, quick-settings, notifications
269:      *  3. Contains "inputmethod", ".ime.", "keyboard", "honeyboard", "bixby"
270:      *     â€” any soft keyboard. There are 50+ keyboard packages; substring match
271:      *     is the only sane cross-device approach.
272:      *  4. Tethr itself       â€” our own overlay windows fire events; ignore them.
273:      *
274:      * Launchers (home screen) are intentionally NOT filtered â€” we DO want to
275:      * detect when the user navigates home so we can call onTargetAppLeft().
276:      */
277:     private fun onTargetAppOpened(packageName: String) {
278:         // Bug C fix â€” if we're already tracking this package at a valid tier,
279:         // another activity transition within Instagram fired. Skip re-init.
280:         if (currentTier > 0) {
281:             Log.d(TAG, "Re-entry ignored â€” already active at Tier $currentTier")
282:             return
283:         }
284: 
285:         Log.d(TAG, "Target app opened: $packageName")
286:         toxicWordTriggered = false
287:         // Use AI-computed value bounded by a hard cap (e.g. 20 min)
288:         val mlComputed = sessionRepo.computeTriggerTime()
289:         triggerTime = minOf(mlComputed, ESCALATION_TRIGGER_MS)
290:         Log.d(TAG, "TriggerTime: ${triggerTime / 1000}s (ML computed: ${mlComputed / 1000}s, capped at ${ESCALATION_TRIGGER_MS / 1000}s)")
291: 
292:         val (savedCumulative, lastExitTimestamp) = sessionRepo.loadActiveState()
293:         val now = System.currentTimeMillis()
294: 
295:         if (lastExitTimestamp > 0 && (now - lastExitTimestamp) >= SessionRepository.COOLDOWN_MS) {
296:             // ── Fresh session (≥10 min away) ─────────────────────────────
297:             Log.d(TAG, "Cooldown expired — fresh session")
298:             if (savedCumulative > 0) {
299:                 sessionRepo.recordSession(savedCumulative)
300:                 sessionRepo.incrementWeekSessionCount()
301:             }
302:             cumulativeActiveMs = 0L
303:             tier3ExtensionMs = 0L
304:             popupsSolvedCount = 0
305:             currentTier = 1
306:             sessionRepo.resetActiveState()
307:             overlayManager.showTier1(0L)
308:         } else {
309:             // ── Resume session (<10 min away) ────────────────────────
310:             cumulativeActiveMs = savedCumulative
311:             Log.d(TAG, "Resuming at ${cumulativeActiveMs / 1000}s")
312:             
313:             // Base layer: Always show Tier 1 pill on resume
314:             currentTier = 1
315:             overlayManager.showTier1(cumulativeActiveMs)
316: 
317:             // CRITICAL: Explicitly re-evaluate and re-draw the UI state immediately
318:             if (cumulativeActiveMs >= triggerTime) {
319:                 currentTier = 2
320:                 overlayManager.showTier2()
321:             }
322:             
323:             if (prefs.getBoolean("PENDING_QUIZ", false)) {
324:                 Log.d(TAG, "Trap triggered: PENDING_QUIZ is true")
325:                 currentTier = 3
326:                 var popupsToSolve = if (cumulativeActiveMs >= 20 * 60_000L) 2 else 1
327:                 fun showQuizSequence() {
328:                     overlayManager.showTier3 {
329:                         popupsToSolve--
330:                         if (popupsToSolve > 0) {
331:                             mainHandler.postDelayed({ showQuizSequence() }, 300)
332:                         } else {
333:                             onTaskSolved()
334:                         }
335:                     }
336:                 }
337:                 showQuizSequence()
338:             } else if (cumulativeActiveMs >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs) {
339:                 currentTier = 3
340:                 var popupsToSolve = if (cumulativeActiveMs >= 20 * 60_000L) 2 else 1
341:                 fun showQuizSequence() {
342:                     overlayManager.showTier3 {
343:                         popupsToSolve--
344:                         if (popupsToSolve > 0) {
345:                             mainHandler.postDelayed({ showQuizSequence() }, 300)
346:                         } else {
347:                             onTaskSolved()
348:                         }
349:                     }
350:                 }
351:                 showQuizSequence()
352:             }
353: 
354:             // Ensure the background timer resumes ticking.
355:             sessionStartTime = System.currentTimeMillis()
356:             mainHandler.removeCallbacks(timeTrackerRunnable)
357:             mainHandler.post(timeTrackerRunnable)
358:         }
359: 
360:         sessionStartTime = System.currentTimeMillis()
361:         mainHandler.removeCallbacks(timeTrackerRunnable)
362:         mainHandler.post(timeTrackerRunnable)
363:     }
364: 
365:     private fun onTargetAppLeft() {
366:         if (currentTier == 0) return  // already idle, nothing to do
367: 
368:         Log.d(TAG, "Left target apps â€” saving state and hiding overlays")
369:         mainHandler.removeCallbacks(timeTrackerRunnable)
370: 
371:         sessionRepo.saveActiveState(
372:             cumulativeMs = cumulativeActiveMs,
373:             lastExitTimestamp = System.currentTimeMillis()
374:         )
375: 
376:         currentTier = 0
377:         overlayManager.hideAll()
378:     }
379: 
380:     /**
381:      * Bug B fix — called AFTER showTier1() so it can safely add grayscale/task
382:      * on top without fighting hideGrayscale(). Sets currentTier correctly.
383:      */
384:     private fun restoreTierState() {
385:         when {
386:             cumulativeActiveMs < triggerTime -> {
387:                 // Tier 1 already set by caller — nothing to do
388:             }
389:             cumulativeActiveMs < triggerTime + TIER2_DURATION_MS + tier3ExtensionMs -> {
390:                 currentTier = 2
391:                 overlayManager.showTier2()
392:             }
393:             else -> {
394:                 // Step 4: Time-based Escalation Logic
395:                 val prefs = getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
396:                 val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
397:                 
398:                 if (quizzesEnabled && currentTier != 3) {
399:                     currentTier = 3
400:                     var popupsToSolve = if (cumulativeActiveMs >= 20 * 60_000L) 2 else 1
401:                     fun showQuizSequence() {
402:                         mainHandler.post { 
403:                             overlayManager.showTier3 {
404:                                 popupsToSolve--
405:                                 if (popupsToSolve > 0) {
406:                                     mainHandler.postDelayed({ showQuizSequence() }, 300)
407:                                 } else {
408:                                     onTaskSolved()
409:                                 }
410:                             }
411:                         }
412:                     }
413:                     showQuizSequence()
414:                 } else if (!quizzesEnabled && currentTier != 2) {
415:                     // If quizzes are disabled, cap at Tier 2
416:                     currentTier = 2
417:                     mainHandler.post { overlayManager.showTier2() }
418:                 }
419:             }
420:         }
421:     }
422: 
423:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
424:     // Tier Evaluation (every second)
425:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
426: 
427:     private fun evaluateTier() {
428:         val isDemo = sessionRepo.isDemoMode()
429:         
430:         if (isDemo) {
431:             // Strict Hackathon Demo Sequence (Bypasses ML triggerTime)
432:             when {
433:                 cumulativeActiveMs >= 15_000L && currentTier < 3 -> {
434:                     val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
435:                     val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
436:                     if (quizzesEnabled) {
437:                         Log.d(TAG, "→ Tier 3 (Demo) at ${cumulativeActiveMs / 1000}s")
438:                         currentTier = 3
439:                         var popupsToSolve = 2 // Hardcoded 2 popups for demo
440:                         fun showQuizSequence() {
441:                             overlayManager.showTier3 {
442:                                 popupsToSolve--
443:                                 if (popupsToSolve > 0) {
444:                                     // 5 seconds between popups in demo mode
445:                                     mainHandler.postDelayed({ showQuizSequence() }, 5000)
446:                                 } else {
447:                                     onTaskSolved()
448:                                 }
449:                             }
450:                         }
451:                         showQuizSequence()
452:                     }
453:                 }
454:                 cumulativeActiveMs >= 10_000L && currentTier < 2 -> {
455:                     Log.d(TAG, "→ Tier 2 (Demo) at ${cumulativeActiveMs / 1000}s")
456:                     currentTier = 2
457:                     overlayManager.showTier2()
458:                 }
459:             }
460:         } else {
461:             // CORE ML LOGIC (Untouched)
462:             val scaledTime = cumulativeActiveMs
463:             when {
464:                 scaledTime >= triggerTime && currentTier < 2 -> {
465:                     Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
466:                     currentTier = 2
467:                     overlayManager.showTier2()
468:                 }
469:                 scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
470:                     val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
471:                     val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
472:                     if (quizzesEnabled) {
473:                         Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
474:                         currentTier = 3
475:                         var popupsToSolve = if (scaledTime >= 20 * 60_000L) 2 else 1
476:                         fun showQuizSequence() {
477:                             overlayManager.showTier3 {
478:                                 popupsToSolve--
479:                                 if (popupsToSolve > 0) {
480:                                     mainHandler.postDelayed({ showQuizSequence() }, 300)
481:                                 } else {
482:                                     onTaskSolved()
483:                                 }
484:                             }
485:                         }
486:                         showQuizSequence()
487:                     }
488:                 }
489:             }
490:         }
491:     }
492: 
493:     private fun onTaskSolved() {
494:         popupsSolvedCount++
495:         
496:         // Shrink the gap by 30 seconds for every solved popup, minimum 30 seconds
497:         val gapReduction = (popupsSolvedCount - 1) * 30_000L
498:         val currentExtensionMs = maxOf(30_000L, TIER3_BASE_EXTENSION_MS - gapReduction)
499:         
500:         Log.d(TAG, "Task solved — extension is ${currentExtensionMs / 1000}s")
501:         val scaledTime = if (sessionRepo.isDemoMode()) cumulativeActiveMs * 60 else cumulativeActiveMs
502:         
503:         // Ensure the next trigger threshold is exactly currentExtensionMs from NOW.
504:         val targetThreshold = scaledTime + currentExtensionMs
505:         val baseThreshold = triggerTime + TIER2_DURATION_MS
506:         tier3ExtensionMs = (targetThreshold - baseThreshold).coerceAtLeast(0)
507:         
508:         currentTier = 2
509:         overlayManager.showTier2()
510:     }
511: 
512:     // ————————————————————————————————————————————————————————————————————————————————————————————————————
513:     // NLP Scanner
514:     // ————————————————————————————————————————————————————————————————————————————————————————————————————
515: 
516:     private fun scanForToxicContent() {
517:         if (toxicWordTriggered) return
518:         val rootNode = rootInActiveWindow ?: return
519:         try {
520:             val sb = StringBuilder()
521:             collectTextFromNode(rootNode, sb)
522:             val text = sb.toString().lowercase()
523:             for (word in toxicWords) {
524:                 if (text.contains(word)) {
525:                     Log.d(TAG, "NLP: '$word' detected → Tier 2")
526:                     toxicWordTriggered = true
527:                     if (currentTier < 2) { currentTier = 2; overlayManager.showTier2() }
528:                     break
529:                 }
530:             }
531:         } catch (e: Exception) {
532:             Log.w(TAG, "NLP exception: ${e.message}")
533:         }
534:     }
535: 
536:     private fun collectTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
537:         if (node == null || depth > MAX_NLP_DEPTH) return
538:         node.text?.let { sb.append(it).append(' ') }
539:         node.contentDescription?.let { sb.append(it).append(' ') }
540:         for (i in 0 until node.childCount) collectTextFromNode(node.getChild(i), sb, depth + 1)
541:     }
542: 
543:     // ————————————————————————————————————————————————————————————————————————————————————————————————————
544:     // Cleanup
545:     // ————————————————————————————————————————————————————————————————————————————————————————————————————
546: 
547:     private fun cleanupOnExit() {
548:         currentForegroundPackage = ""
549:         currentTier = 0
550:         popupsSolvedCount = 0
551:         mainHandler.removeCallbacks(timeTrackerRunnable)
552:         if (cumulativeActiveMs > 0) {
553:             sessionRepo.saveActiveState(
554:                 cumulativeMs = cumulativeActiveMs,
555:                 lastExitTimestamp = System.currentTimeMillis()
556:             )
557:         }
558:         overlayManager.hideAll()
559:     }
560: 
561:     // ————————————————————————————————————————————————————————————————————————————————————————————————————
562:     // Constants
563:     // ————————————————————————————————————————————————————————————————————————————————————————————————————
564: 
565:     companion object {
566:         private const val TAG = "TethrService"
567: 
568:         val TARGET_PACKAGES = setOf(
569:             "com.instagram.android",
570:             "com.facebook.katana"
571:         )
572:         
573:         val IGNORED_PACKAGES = setOf(
574:             "com.android.systemui",
575:             "com.bbk.systemui",        // Vivo / Funtouch OS Notification Shade
576:             "com.miui.securitycenter", // For POCO/Xiaomi screenshot UI
577:             "com.google.android.inputmethod.latin"
578:         )
579: 
580:         private const val TICK_MS = 1000L
581:         private const val ESCALATION_TRIGGER_MS = 20 * 60_000L   // Hard cap at 20 min
582:         private const val TIER2_DURATION_MS = 5 * 60_000L         // quiz fires 5 mins after grayscale kicks in
583:         private const val TIER3_BASE_EXTENSION_MS = 2 * 60_000L + 30_000L  // Initial quiz repeats every 2.5 min
584:         private const val NLP_SCAN_INTERVAL_MS = 2000L
585:         private const val MAX_NLP_DEPTH = 8
586:     }
587: }
588: 
589: 
The above content shows the entire, complete file contents of the requested file.

