Created At: 2026-08-22T12:08:42+05:30
Completed At: 2026-08-22T12:08:42+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/OverlayManager.kt`
Total Lines: 757
Total Bytes: 35531
Showing lines 1 to 757
The following code has been modified to include a line number before every line, in the format: <line_number>: <original_line>. Please note that any changes targeting the original code should remove the line number, colon, and leading space.
1: package com.example.tethr
2: 
3: import android.annotation.SuppressLint
4: import android.content.Context
5: import android.graphics.*
6: import android.os.Build
7: import android.os.Handler
8: import android.os.Looper
9: import android.util.Log
10: import android.view.*
11: import android.view.MotionEvent
12: import android.provider.Settings
13: import android.widget.EditText
14: import android.widget.FrameLayout
15: import android.widget.Toast
16: import kotlin.math.*
17: import kotlin.random.Random
18: 
19: // ──────────────────────────────────────────────────────────────────────────────
20: // OverlayManager
21: // Central controller for all three escalation tiers drawn via WindowManager.
22: //
23: // Tier 1 — Floating pill (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE)
24: // Tier 2 — Full-screen grayscale ColorMatrix overlay (pass-through, non-blocking)
25: // Tier 3 — Blocking cognitive task modal (FOCUSABLE | TOUCHABLE)
26: // ──────────────────────────────────────────────────────────────────────────────
27: class OverlayManager(private val context: Context) {
28: 
29:     private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
30:     private val mainHandler = Handler(Looper.getMainLooper())
31:     private val prefs = context.getSharedPreferences("tethr_prefs", Context.MODE_PRIVATE)
32: 
33:     // ── View references ──────────────────────────────────────────────────────
34:     private var pillView: PillOverlayView? = null
35:     private var taskView: TaskOverlayView? = null
36: 
37:     // ── Task rotation state ──────────────────────────────────────────────────
38:     private var currentTaskIndex = 0
39:     private var taskExtensionCallback: (() -> Unit)? = null
40: 
41:     private fun baseParams(
42:         w: Int = WindowManager.LayoutParams.MATCH_PARENT,
43:         h: Int = WindowManager.LayoutParams.MATCH_PARENT,
44:         extraFlags: Int = 0,
45:     ): WindowManager.LayoutParams {
46:         val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
47:             WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
48:         else
49:             @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
50: 
51:         return WindowManager.LayoutParams(
52:             w, h, type,
53:             WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
54:                     WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
55:                     WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
56:                     WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
57:                     extraFlags,
58:             PixelFormat.TRANSLUCENT
59:         ).also { it.gravity = Gravity.TOP or Gravity.START }
60:     }
61: 
62:     // ─────────────────────────────────────────────────────────────────────────
63:     // Public API
64:     // ─────────────────────────────────────────────────────────────────────────
65: 
66:     /** Called when Instagram opens — shows the Tier 1 pill. */
67:     fun showTier1(activeTimeMs: Long) {
68:         mainHandler.post {
69:             ensurePill()
70:             pillView?.updateTime(activeTimeMs)
71:             hideTask()
72:         }
73:     }
74: 
75:     /** Called when grayscale trigger fires — keeps pill, activates hardware grayscale. */
76:     fun showTier2() {
77:         mainHandler.post {
78:             ensurePill()
79:             enableHardwareGrayscale(context)
80:             hideTask()
81:         }
82:     }
83: 
84:     /**
85:      * Called when the cognitive loop starts. Shows blocking task modal.
86:      * [onExtensionGranted] is called after the user solves the task, granting
87:      * 5 more minutes of Tier 2 time.
88:      */
89:     fun showTier3(onExtensionGranted: () -> Unit) {
90:         mainHandler.post {
91:             prefs.edit().putBoolean("PENDING_QUIZ", true).apply()
92:             taskExtensionCallback = onExtensionGranted
93:             ensurePill()
94:             enableHardwareGrayscale(context)
95:             showNextTask()
96:         }
97:     }
98: 
99:     /** Updates the pill's displayed time. Safe to call every second. */
100:     fun updateTime(activeTimeMs: Long) {
101:         mainHandler.post { pillView?.updateTime(activeTimeMs) }
102:     }
103: 
104:     /**
105:      * INSTANT CLEANUP — called when:
106:      *   - Instagram is backgrounded / closed
107:      *   - Screen turns off (ACTION_SCREEN_OFF)
108:      *   - Service interrupted / destroyed
109:      */
110:     fun hideAll() {
111:         mainHandler.post {
112:             hidePill()
113:             disableHardwareGrayscale(context)
114:             hideTask()
115:             taskExtensionCallback = null
116:             Log.d(TAG, "hideAll() — all overlays removed & hardware grayscale disabled")
117:         }
118:     }
119: 
120:     // ─────────────────────────────────────────────────────────────────────────
121:     // Hardware Grayscale (Daltonizer via Settings.Secure)
122:     // ─────────────────────────────────────────────────────────────────────────
123: 
124:     private fun enableHardwareGrayscale(context: Context) {
125:         try {
126:             Settings.Secure.putInt(context.contentResolver, "accessibility_display_daltonizer_enabled", 1)
127:             Settings.Secure.putInt(context.contentResolver, "accessibility_display_daltonizer", 0) // 0 = Monochromatic
128:             Log.d(TAG, "Hardware Grayscale ENABLED successfully.")
129:         } catch (e: SecurityException) {
130:             Log.e(TAG, "Missing WRITE_SECURE_SETTINGS permission to enable Hardware Grayscale.")
131:         }
132:     }
133: 
134:     private fun disableHardwareGrayscale(context: Context) {
135:         try {
136:             Settings.Secure.putInt(context.contentResolver, "accessibility_display_daltonizer_enabled", 0)
137:             Log.d(TAG, "Hardware Grayscale DISABLED successfully.")
138:         } catch (e: SecurityException) {
139:             Log.e(TAG, "Missing WRITE_SECURE_SETTINGS permission to disable Hardware Grayscale.")
140:         }
141:     }
142: 
143:     // ─────────────────────────────────────────────────────────────────────────
144:     // Pill (Tier 1)
145:     // ─────────────────────────────────────────────────────────────────────────
146: 
147:     private fun ensurePill() {
148:         if (pillView == null) {
149:             pillView = PillOverlayView(context)
150:         }
151:         if (pillView?.parent == null) {
152:             val params = baseParams(
153:                 w = WindowManager.LayoutParams.MATCH_PARENT,
154:                 h = WindowManager.LayoutParams.WRAP_CONTENT,
155:                 extraFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
156:             )
157:             safeAddView(pillView!!, params)
158:         }
159:     }
160: 
161:     private fun hidePill() {
162:         pillView?.let { safeRemoveView(it); pillView = null }
163:     }
164: 
165:     // ─────────────────────────────────────────────────────────────────────────
166:     // Task modal (Tier 3)
167:     // ─────────────────────────────────────────────────────────────────────────
168: 
169:     private fun showNextTask() {
170:         hideTask()
171:         val taskParams = WindowManager.LayoutParams(
172:             WindowManager.LayoutParams.MATCH_PARENT,
173:             WindowManager.LayoutParams.MATCH_PARENT,
174:             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
175:                 WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
176:             else
177:                 @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
178:             WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
179:                     WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
180:             PixelFormat.TRANSLUCENT
181:         ).also { it.gravity = Gravity.CENTER }
182: 
183:         val task = TaskOverlayView(context, currentTaskIndex) {
184:             // onSolved callback
185:             prefs.edit().putBoolean("PENDING_QUIZ", false).apply()
186:             currentTaskIndex = (currentTaskIndex + 1) % TASK_COUNT
187:             hideTask()
188:             taskExtensionCallback?.invoke()
189:             Log.d(TAG, "Task solved! Next task index: $currentTaskIndex")
190:         }
191:         safeAddView(task, taskParams)
192:         taskView = task
193:     }
194: 
195:     private fun hideTask() {
196:         taskView?.let { safeRemoveView(it); taskView = null }
197:     }
198: 
199:     // ─────────────────────────────────────────────────────────────────────────
200:     // WindowManager safe helpers
201:     // ─────────────────────────────────────────────────────────────────────────
202: 
203:     private fun safeAddView(view: View, params: WindowManager.LayoutParams) {
204:         try {
205:             windowManager.addView(view, params)
206:         } catch (e: Exception) {
207:             Log.e(TAG, "safeAddView failed: ${e.message}")
208:         }
209:     }
210: 
211:     private fun safeRemoveView(view: View) {
212:         try {
213:             windowManager.removeView(view)
214:         } catch (e: IllegalArgumentException) {
215:             Log.w(TAG, "safeRemoveView: view not attached (race): ${e.message}")
216:         } catch (e: Exception) {
217:             Log.e(TAG, "safeRemoveView unexpected error: ${e.message}")
218:         }
219:     }
220: 
221:     companion object {
222:         private const val TAG = "OverlayManager"
223:         private const val TASK_COUNT = 3
224:     }
225: 
226:     // ═════════════════════════════════════════════════════════════════════════
227:     // TIER 1 — Floating Pill View
228:     // ═════════════════════════════════════════════════════════════════════════
229: 
230:     @SuppressLint("ViewConstructor")
231:     private inner class PillOverlayView(ctx: Context) : View(ctx) {
232: 
233:         private var activeTimeMs: Long = 0
234: 
235:         private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
236:             color = Color.parseColor("#E6000000")
237:             style = Paint.Style.FILL
238:         }
239:         private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
240:             color = Color.WHITE
241:             textSize = 38f
242:             textAlign = Paint.Align.CENTER
243:             typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
244:         }
245:         private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
246:             color = Color.parseColor("#99FFFFFF")
247:             textSize = 24f
248:             textAlign = Paint.Align.CENTER
249:         }
250:         private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
251:             color = Color.parseColor("#FFFFFF") // white accent dot
252:             style = Paint.Style.FILL
253:         }
254: 
255:         fun updateTime(ms: Long) {
256:             activeTimeMs = ms
257:             invalidate()
258:         }
259: 
260:         override fun onDraw(canvas: Canvas) {
261:             super.onDraw(canvas)
262: 
263:             val cx = width / 2f
264:             val cy = 120f
265:             val rx = 220f
266:             val ry = 56f
267: 
268:             // Pill background
269:             canvas.drawRoundRect(cx - rx, cy - ry, cx + rx, cy + ry, ry, ry, pillPaint)
270: 
271:             // Accent dot
272:             canvas.drawCircle(cx - rx + 28f, cy, 8f, accentPaint)
273: 
274:             // Time string
275:             val mins = activeTimeMs / 60_000
276:             val secs = (activeTimeMs % 60_000) / 1000
277:             canvas.drawText("Active: %02d:%02d".format(mins, secs), cx + 8f, cy + 13f, timePaint)
278: 
279:             // "Tethr" label sub-text
280:             canvas.drawText("tethr", cx - rx + 28f, cy + 30f, labelPaint)
281:         }
282:     }
283: 
284:     // ═════════════════════════════════════════════════════════════════════════
285:     // TIER 3 — Rotating Cognitive Task Modal
286:     // ═════════════════════════════════════════════════════════════════════════
287: 
288:     @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
289:     private inner class TaskOverlayView(
290:         private val ctx: Context,
291:         private val taskIndex: Int,
292:         private val onSolved: () -> Unit
293:     ) : FrameLayout(ctx) {
294: 
295:         private val mainHandler = Handler(Looper.getMainLooper())
296: 
297:         init {
298:             setBackgroundColor(Color.parseColor("#F0000000"))
299:             buildTask()
300:         }
301: 
302:         private fun buildTask() {
303:             when (taskIndex % 3) {
304:                 0 -> buildMentalMathTask()
305:                 1 -> buildTicTacToeTask()
306:                 2 -> buildHoldButtonTask()
307:             }
308:         }
309: 
310:         // ──────────────────────────────────────────────────────────────────
311:         // Task 1 — Mental Math
312:         // ──────────────────────────────────────────────────────────────────
313: 
314:         @SuppressLint("SetTextI18n")
315:         private fun buildMentalMathTask() {
316:             // Fix 3 — number ranges per spec: n1 in 12..49, n2 in 11..48
317:             val n1 = (12..49).random()
318:             val n2 = (11..48).random()
319:             val correctAnswer = n1 + n2
320: 
321:             // ── Root container (vertical, centred) ───────────────────────
322:             val root = android.widget.LinearLayout(ctx).apply {
323:                 orientation = android.widget.LinearLayout.VERTICAL
324:                 gravity = Gravity.CENTER
325:                 setBackgroundColor(Color.parseColor("#EE000000"))
326:                 layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
327:             }
328: 
329:             // ── Card ────────────────────────────────────────────────────
330:             val card = android.widget.LinearLayout(ctx).apply {
331:                 orientation = android.widget.LinearLayout.VERTICAL
332:                 gravity = Gravity.CENTER
333:                 setBackgroundColor(Color.parseColor("#111111"))
334:                 setPadding(48, 48, 48, 48)
335:             }
336: 
337:             // ── Title ───────────────────────────────────────────────────
338:             val tvTitle = android.widget.TextView(ctx).apply {
339:                 text = "Quick Math"
340:                 textSize = 18f
341:                 setTextColor(Color.parseColor("#AAAAAA"))
342:                 typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
343:                 gravity = Gravity.CENTER
344:                 setPadding(0, 0, 0, 16)
345:             }
346: 
347:             // Fix 3 — question text: white, 24sp bold, always visible
348:             val tvQuestion = android.widget.TextView(ctx).apply {
349:                 text = "$n1 + $n2 = ?"
350:                 textSize = 48f                          // big, readable
351:                 setTextColor(Color.WHITE)               // #FFFFFF explicit
352:                 typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
353:                 gravity = Gravity.CENTER
354:                 visibility = View.VISIBLE
355:                 setPadding(0, 0, 0, 24)
356:             }
357: 
358:             // ── Error text (hidden until wrong answer) ───────────────────
359:             val tvError = android.widget.TextView(ctx).apply {
360:                 text = ""
361:                 textSize = 14f
362:                 setTextColor(Color.parseColor("#FF5252"))  // red per spec
363:                 gravity = Gravity.CENTER
364:                 visibility = View.INVISIBLE
365:                 setPadding(0, 0, 0, 8)
366:             }
367: 
368:             // ── Input field ─────────────────────────────────────────────
369:             // Fix 3 — high-contrast background #2A2A38, white text, grey hint
370:             val editText = EditText(ctx).apply {
371:                 hint = "Type your answer"
372:                 setHintTextColor(Color.parseColor("#888888"))
373:                 setTextColor(Color.WHITE)
374:                 textSize = 22f
375:                 inputType = android.text.InputType.TYPE_CLASS_NUMBER
376:                 setBackgroundColor(Color.parseColor("#2A2A38"))
377:                 setPadding(24, 16, 24, 16)
378:                 layoutParams = android.widget.LinearLayout.LayoutParams(
379:                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
380:                     android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
381:                 )
382:             }
383: 
384:             // ── Submit button ────────────────────────────────────────────
385:             val submitBtn = android.widget.Button(ctx).apply {
386:                 text = "Submit"
387:                 textSize = 18f
388:                 setTextColor(Color.BLACK)
389:                 setBackgroundColor(Color.WHITE)
390:                 layoutParams = android.widget.LinearLayout.LayoutParams(
391:                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
392:                     android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
393:                 ).also { it.topMargin = 16 }
394: 
395:                 setOnClickListener {
396:                     val input = editText.text.toString().trim().toIntOrNull()
397:                     when {
398:                         input == null -> {
399:                             tvError.text = "Enter a number"
400:                             tvError.visibility = View.VISIBLE
401:                         }
402:                         input == correctAnswer -> {
403:                             // Correct — dismiss
404:                             tvError.visibility = View.INVISIBLE
405:                             onSolved()
406:                         }
407:                         else -> {
408:                             // Wrong — show red error, clear field
409:                             editText.text.clear()
410:                             tvError.text = "Wrong! Try again"
411:                             tvError.visibility = View.VISIBLE
412:                         }
413:                     }
414:                 }
415:             }
416: 
417:             // ── Hint ─────────────────────────────────────────────────────
418:             val tvHint = android.widget.TextView(ctx).apply {
419:                 text = "Solve to continue scrolling"
420:                 textSize = 12f
421:                 setTextColor(Color.parseColor("#9CA3AF"))
422:                 gravity = Gravity.CENTER
423:                 setPadding(0, 16, 0, 0)
424:             }
425: 
426:             // ── Assemble ─────────────────────────────────────────────────
427:             card.addView(tvTitle)
428:             card.addView(tvQuestion)
429:             card.addView(tvError)
430:             card.addView(editText)
431:             card.addView(submitBtn)
432:             card.addView(tvHint)
433: 
434:             // Wrap card in a border frame (violet outline)
435:             val borderFrame = FrameLayout(ctx).apply {
436:                 setBackgroundColor(Color.parseColor("#333333"))
437:                 setPadding(2, 2, 2, 2)
438:                 layoutParams = android.widget.LinearLayout.LayoutParams(
439:                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
440:                     android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
441:                 ).also {
442:                     it.marginStart = 40; it.marginEnd = 40
443:                 }
444:             }
445:             borderFrame.addView(card)
446:             root.addView(borderFrame)
447:             addView(root)
448:         }
449: 
450:         // ──────────────────────────────────────────────────────────────────
451:         // Task 2 — Micro Tic-Tac-Toe (vs Minimax bot)
452:         // ──────────────────────────────────────────────────────────────────
453: 
454:         private fun buildTicTacToeTask() {
455:             val tttView = TicTacToeView(ctx) { onSolved() }
456:             addView(tttView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
457:         }
458: 
459:         // ──────────────────────────────────────────────────────────────────
460:         // Task 3 — 10-Second Hold Button
461:         // ──────────────────────────────────────────────────────────────────
462: 
463:         @SuppressLint("ClickableViewAccessibility")
464:         private fun buildHoldButtonTask() {
465:             val holdView = object : View(ctx) {
466:                 private var progress = 0f   // 0.0 to 1.0
467:                 private var isHolding = false
468:                 private val tickMs = 50L
469:                 private val totalMs = 10_000L
470: 
471:                 private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
472:                     color = Color.parseColor("#111111")
473:                     style = Paint.Style.FILL
474:                 }
475:                 private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
476:                     color = Color.parseColor("#2A2A2A")
477:                     style = Paint.Style.STROKE
478:                     strokeWidth = 24f
479:                     strokeCap = Paint.Cap.ROUND
480:                 }
481:                 private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
482:                     color = Color.parseColor("#FFFFFF")
483:                     style = Paint.Style.STROKE
484:                     strokeWidth = 24f
485:                     strokeCap = Paint.Cap.ROUND
486:                 }
487:                 private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
488:                     color = Color.parseColor("#222222")
489:                     style = Paint.Style.FILL
490:                 }
491:                 private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
492:                     color = Color.WHITE
493:                     textSize = 32f
494:                     textAlign = Paint.Align.CENTER
495:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
496:                 }
497:                 private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
498:                     color = Color.parseColor("#AAAAAA")
499:                     textSize = 26f
500:                     textAlign = Paint.Align.CENTER
501:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
502:                 }
503:                 private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
504:                     color = Color.parseColor("#666666")
505:                     textSize = 20f
506:                     textAlign = Paint.Align.CENTER
507:                 }
508: 
509:                 private val tickRunnable = object : Runnable {
510:                     override fun run() {
511:                         if (!isHolding) return
512:                         progress += tickMs.toFloat() / totalMs
513:                         if (progress >= 1f) {
514:                             progress = 1f
515:                             invalidate()
516:                             onSolved()
517:                             return
518:                         }
519:                         invalidate()
520:                         mainHandler.postDelayed(this, tickMs)
521:                     }
522:                 }
523: 
524:                 init {
525:                     setOnTouchListener { _, event ->
526:                         when (event.action) {
527:                             MotionEvent.ACTION_DOWN -> {
528:                                 isHolding = true
529:                                 mainHandler.post(tickRunnable)
530:                                 true
531:                             }
532:                             MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
533:                                 isHolding = false
534:                                 mainHandler.removeCallbacks(tickRunnable)
535:                                 progress = 0f
536:                                 invalidate()
537:                                 true
538:                             }
539:                             else -> false
540:                         }
541:                     }
542:                 }
543: 
544:                 override fun onDraw(canvas: Canvas) {
545:                     val cx = width / 2f
546:                     val cy = height / 2f
547:                     val radius = minOf(cx, cy) * 0.55f
548: 
549:                     // Background card
550:                     canvas.drawRoundRect(cx - radius * 1.4f, cy - radius * 1.7f,
551:                         cx + radius * 1.4f, cy + radius * 1.4f, 32f, 32f, bgPaint)
552: 
553:                     // Title
554:                     canvas.drawText("Hold to Continue", cx, cy - radius * 1.4f, titlePaint)
555: 
556:                     // Arc track
557:                     val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
558:                     canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)
559: 
560:                     // Progress arc
561:                     if (progress > 0f) {
562:                         arcPaint.color = if (progress > 0.8f)
563:                             Color.parseColor("#4CAF50") else Color.parseColor("#FFFFFF")
564:                         canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)
565:                     }
566: 
567:                     // Center button
568:                     btnPaint.color = if (isHolding) Color.parseColor("#333333")
569:                     else Color.parseColor("#222222")
570:                     canvas.drawCircle(cx, cy, radius * 0.72f, btnPaint)
571: 
572:                     // Button text
573:                     val secsLeft = ((1f - progress) * 10f).coerceAtLeast(0f)
574:                     canvas.drawText(if (isHolding) "%.1fs".format(secsLeft) else "Hold", cx, cy + 12f, textPaint)
575: 
576:                     // Sub hint
577:                     canvas.drawText("Release = reset", cx, cy + radius * 1.2f, subPaint)
578:                 }
579:             }
580:             addView(holdView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
581:         }
582:     }
583: 
584:     // ═════════════════════════════════════════════════════════════════════════
585:     // Tic-Tac-Toe View (Minimax AI)
586:     // ═════════════════════════════════════════════════════════════════════════
587: 
588:     @SuppressLint("ViewConstructor")
589:     private inner class TicTacToeView(ctx: Context, private val onSolved: () -> Unit) : View(ctx) {
590: 
591:         private val board = Array(3) { IntArray(3) }  // 0=empty, 1=player(X), 2=bot(O)
592:         private var gameOver = false
593:         private var botThinking = false
594:         private var statusMessage = "Your turn — get 3 in a row"
595: 
596:         // Paints
597:         private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
598:             color = Color.parseColor("#111111"); style = Paint.Style.FILL
599:         }
600:         private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
601:             color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 6f
602:         }
603:         private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
604:             color = Color.parseColor("#FFFFFF"); style = Paint.Style.STROKE
605:             strokeWidth = 12f; strokeCap = Paint.Cap.ROUND
606:         }
607:         private val oPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
608:             color = Color.parseColor("#888888"); style = Paint.Style.STROKE
609:             strokeWidth = 10f
610:         }
611:         private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
612:             color = Color.parseColor("#AAAAAA"); textSize = 28f; textAlign = Paint.Align.CENTER
613:             typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
614:         }
615:         private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
616:             color = Color.parseColor("#888888"); textSize = 22f; textAlign = Paint.Align.CENTER
617:         }
618: 
619:         private var cellSize = 0f
620:         private var gridLeft = 0f
621:         private var gridTop = 0f
622: 
623:         override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
624:             cellSize = minOf(w, h) * 0.22f
625:             gridLeft = w / 2f - cellSize * 1.5f
626:             gridTop = h / 2f - cellSize * 1.5f
627:         }
628: 
629:         @SuppressLint("ClickableViewAccessibility")
630:         override fun onTouchEvent(event: MotionEvent): Boolean {
631:             if (event.action != MotionEvent.ACTION_DOWN || gameOver || botThinking) return true
632:             val col = ((event.x - gridLeft) / cellSize).toInt()
633:             val row = ((event.y - gridTop) / cellSize).toInt()
634:             if (row !in 0..2 || col !in 0..2 || board[row][col] != 0) return true
635: 
636:             board[row][col] = 1 // player move
637:             if (checkWinOrDraw()) return true
638: 
639:             botThinking = true
640:             statusMessage = "Bot is thinking..."
641:             invalidate()
642: 
643:             postDelayed({
644:                 // Bot move via minimax
645:                 val (br, bc) = bestMove()
646:                 if (br != -1) {
647:                     board[br][bc] = 2
648:                     checkWinOrDraw()
649:                 }
650:                 botThinking = false
651:                 if (!gameOver) {
652:                     statusMessage = "Your turn — get 3 in a row"
653:                 }
654:                 invalidate()
655:             }, 400) // 400ms delay for the bot's move
656:             return true
657:         }
658: 
659:         private fun checkWinOrDraw(): Boolean {
660:             val winner = getWinner()
661:             return when {
662:                 winner == 1 -> { statusMessage = "You win! Continuing..."; gameOver = true; invalidate(); postDelayed({ onSolved() }, 800); true }
663:                 winner == 2 -> { statusMessage = "Bot wins — try again"; resetBoard(); true }
664:                 isBoardFull() -> { statusMessage = "Draw! Continuing..."; gameOver = true; invalidate(); postDelayed({ onSolved() }, 800); true }
665:                 else -> false
666:             }
667:         }
668: 
669:         private fun resetBoard() {
670:             for (r in 0..2) for (c in 0..2) board[r][c] = 0
671:             gameOver = false
672:             statusMessage = "Your turn — get 3 in a row"
673:             invalidate()
674:         }
675: 
676:         private fun getWinner(): Int {
677:             for (i in 0..2) {
678:                 if (board[i][0] != 0 && board[i][0] == board[i][1] && board[i][1] == board[i][2]) return board[i][0]
679:                 if (board[0][i] != 0 && board[0][i] == board[1][i] && board[1][i] == board[2][i]) return board[0][i]
680:             }
681:             if (board[0][0] != 0 && board[0][0] == board[1][1] && board[1][1] == board[2][2]) return board[0][0]
682:             if (board[0][2] != 0 && board[0][2] == board[1][1] && board[1][1] == board[2][0]) return board[0][2]
683:             return 0
684:         }
685: 
686:         private fun isBoardFull() = board.all { row -> row.all { it != 0 } }
687: 
688:         // Minimax — always finds optimal move for bot (O=2)
689:         private fun minimax(depth: Int, isMaximizing: Boolean): Int {
690:             val winner = getWinner()
691:             if (winner == 2) return 10 - depth
692:             if (winner == 1) return depth - 10
693:             if (isBoardFull()) return 0
694:             var best = if (isMaximizing) Int.MIN_VALUE else Int.MAX_VALUE
695:             for (r in 0..2) for (c in 0..2) {
696:                 if (board[r][c] == 0) {
697:                     board[r][c] = if (isMaximizing) 2 else 1
698:                     val score = minimax(depth + 1, !isMaximizing)
699:                     board[r][c] = 0
700:                     best = if (isMaximizing) maxOf(best, score) else minOf(best, score)
701:                 }
702:             }
703:             return best
704:         }
705: 
706:         private fun bestMove(): Pair<Int, Int> {
707:             var bestScore = Int.MIN_VALUE
708:             var moveRow = -1; var moveCol = -1
709:             for (r in 0..2) for (c in 0..2) {
710:                 if (board[r][c] == 0) {
711:                     board[r][c] = 2
712:                     val score = minimax(0, false)
713:                     board[r][c] = 0
714:                     if (score > bestScore) { bestScore = score; moveRow = r; moveCol = c }
715:                 }
716:             }
717:             return Pair(moveRow, moveCol)
718:         }
719: 
720:         override fun onDraw(canvas: Canvas) {
721:             val cx = width / 2f
722:             val cy = height / 2f
723: 
724:             // Background card
725:             val cardW = cellSize * 3 + 80f
726:             canvas.drawRoundRect(cx - cardW / 2, cy - cardW / 2 - 80f,
727:                 cx + cardW / 2, cy + cardW / 2 + 60f, 32f, 32f, bgPaint)
728: 
729:             // Title
730:             canvas.drawText("Tic-Tac-Toe", cx, cy - cellSize * 1.5f - 32f, titlePaint)
731: 
732:             // Grid lines
733:             for (i in 1..2) {
734:                 canvas.drawLine(gridLeft + cellSize * i, gridTop, gridLeft + cellSize * i, gridTop + cellSize * 3, linePaint)
735:                 canvas.drawLine(gridLeft, gridTop + cellSize * i, gridLeft + cellSize * 3, gridTop + cellSize * i, linePaint)
736:             }
737: 
738:             // Pieces
739:             val pad = cellSize * 0.2f
740:             for (r in 0..2) for (c in 0..2) {
741:                 val x = gridLeft + c * cellSize
742:                 val y = gridTop + r * cellSize
743:                 when (board[r][c]) {
744:                     1 -> { // X
745:                         canvas.drawLine(x + pad, y + pad, x + cellSize - pad, y + cellSize - pad, xPaint)
746:                         canvas.drawLine(x + cellSize - pad, y + pad, x + pad, y + cellSize - pad, xPaint)
747:                     }
748:                     2 -> canvas.drawCircle(x + cellSize / 2, y + cellSize / 2, cellSize / 2 - pad, oPaint) // O
749:                 }
750:             }
751: 
752:             // Status
753:             canvas.drawText(statusMessage, cx, gridTop + cellSize * 3 + 44f, statusPaint)
754:         }
755:     }
756: }
757: 
The above content shows the entire, complete file contents of the requested file.

