Created At: 2026-08-20T17:45:32+05:30
Completed At: 2026-08-20T17:45:32+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/OverlayManager.kt`
Total Lines: 748
Total Bytes: 35285
Showing lines 1 to 748
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
251:             color = Color.parseColor("#7C3AED") // violet accent
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
298:             setBackgroundColor(Color.parseColor("#F0050510"))
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
325:                 setBackgroundColor(Color.parseColor("#EE050510"))
326:                 layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
327:             }
328: 
329:             // ── Card ────────────────────────────────────────────────────
330:             val card = android.widget.LinearLayout(ctx).apply {
331:                 orientation = android.widget.LinearLayout.VERTICAL
332:                 gravity = Gravity.CENTER
333:                 setBackgroundColor(Color.parseColor("#1A1040"))
334:                 // 2dp violet border via a background drawable would need XML;
335:                 // simplest zero-dep approach: wrap in a slightly-larger FrameLayout
336:                 setPadding(48, 48, 48, 48)
337:             }
338: 
339:             // ── Title ───────────────────────────────────────────────────
340:             val tvTitle = android.widget.TextView(ctx).apply {
341:                 text = "Quick Math"
342:                 textSize = 18f
343:                 setTextColor(Color.parseColor("#A78BFA"))
344:                 typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
345:                 gravity = Gravity.CENTER
346:                 setPadding(0, 0, 0, 16)
347:             }
348: 
349:             // Fix 3 — question text: white, 24sp bold, always visible
350:             val tvQuestion = android.widget.TextView(ctx).apply {
351:                 text = "$n1 + $n2 = ?"
352:                 textSize = 48f                          // big, readable
353:                 setTextColor(Color.WHITE)               // #FFFFFF explicit
354:                 typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
355:                 gravity = Gravity.CENTER
356:                 visibility = View.VISIBLE
357:                 setPadding(0, 0, 0, 24)
358:             }
359: 
360:             // ── Error text (hidden until wrong answer) ───────────────────
361:             val tvError = android.widget.TextView(ctx).apply {
362:                 text = ""
363:                 textSize = 14f
364:                 setTextColor(Color.parseColor("#FF5252"))  // red per spec
365:                 gravity = Gravity.CENTER
366:                 visibility = View.INVISIBLE
367:                 setPadding(0, 0, 0, 8)
368:             }
369: 
370:             // ── Input field ─────────────────────────────────────────────
371:             // Fix 3 — high-contrast background #2A2A38, white text, grey hint
372:             val editText = EditText(ctx).apply {
373:                 hint = "Type your answer"
374:                 setHintTextColor(Color.parseColor("#888888"))
375:                 setTextColor(Color.WHITE)
376:                 textSize = 22f
377:                 inputType = android.text.InputType.TYPE_CLASS_NUMBER
378:                 setBackgroundColor(Color.parseColor("#2A2A38"))
379:                 setPadding(24, 16, 24, 16)
380:                 layoutParams = android.widget.LinearLayout.LayoutParams(
381:                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
382:                     android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
383:                 )
384:             }
385: 
386:             // ── Submit button ────────────────────────────────────────────
387:             val submitBtn = android.widget.Button(ctx).apply {
388:                 text = "Submit"
389:                 textSize = 18f
390:                 setTextColor(Color.WHITE)
391:                 setBackgroundColor(Color.parseColor("#7C3AED"))
392:                 layoutParams = android.widget.LinearLayout.LayoutParams(
393:                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
394:                     android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
395:                 ).also { it.topMargin = 16 }
396: 
397:                 setOnClickListener {
398:                     val input = editText.text.toString().trim().toIntOrNull()
399:                     when {
400:                         input == null -> {
401:                             tvError.text = "Enter a number"
402:                             tvError.visibility = View.VISIBLE
403:                         }
404:                         input == correctAnswer -> {
405:                             // Correct — dismiss
406:                             tvError.visibility = View.INVISIBLE
407:                             onSolved()
408:                         }
409:                         else -> {
410:                             // Wrong — show red error, clear field
411:                             editText.text.clear()
412:                             tvError.text = "Wrong! Try again"
413:                             tvError.visibility = View.VISIBLE
414:                         }
415:                     }
416:                 }
417:             }
418: 
419:             // ── Hint ─────────────────────────────────────────────────────
420:             val tvHint = android.widget.TextView(ctx).apply {
421:                 text = "Solve to continue scrolling"
422:                 textSize = 12f
423:                 setTextColor(Color.parseColor("#9CA3AF"))
424:                 gravity = Gravity.CENTER
425:                 setPadding(0, 16, 0, 0)
426:             }
427: 
428:             // ── Assemble ─────────────────────────────────────────────────
429:             card.addView(tvTitle)
430:             card.addView(tvQuestion)
431:             card.addView(tvError)
432:             card.addView(editText)
433:             card.addView(submitBtn)
434:             card.addView(tvHint)
435: 
436:             // Wrap card in a border frame (violet outline)
437:             val borderFrame = FrameLayout(ctx).apply {
438:                 setBackgroundColor(Color.parseColor("#7C3AED"))
439:                 setPadding(2, 2, 2, 2)
440:                 layoutParams = android.widget.LinearLayout.LayoutParams(
441:                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
442:                     android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
443:                 ).also {
444:                     it.marginStart = 40; it.marginEnd = 40
445:                 }
446:             }
447:             borderFrame.addView(card)
448:             root.addView(borderFrame)
449:             addView(root)
450:         }
451: 
452:         // ──────────────────────────────────────────────────────────────────
453:         // Task 2 — Micro Tic-Tac-Toe (vs Minimax bot)
454:         // ──────────────────────────────────────────────────────────────────
455: 
456:         private fun buildTicTacToeTask() {
457:             val tttView = TicTacToeView(ctx) { onSolved() }
458:             addView(tttView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
459:         }
460: 
461:         // ──────────────────────────────────────────────────────────────────
462:         // Task 3 — 10-Second Hold Button
463:         // ──────────────────────────────────────────────────────────────────
464: 
465:         @SuppressLint("ClickableViewAccessibility")
466:         private fun buildHoldButtonTask() {
467:             val holdView = object : View(ctx) {
468:                 private var progress = 0f   // 0.0 to 1.0
469:                 private var isHolding = false
470:                 private val tickMs = 50L
471:                 private val totalMs = 10_000L
472: 
473:                 private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
474:                     color = Color.parseColor("#1A1040")
475:                     style = Paint.Style.FILL
476:                 }
477:                 private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
478:                     color = Color.parseColor("#2D1B69")
479:                     style = Paint.Style.STROKE
480:                     strokeWidth = 24f
481:                     strokeCap = Paint.Cap.ROUND
482:                 }
483:                 private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
484:                     color = Color.parseColor("#7C3AED")
485:                     style = Paint.Style.STROKE
486:                     strokeWidth = 24f
487:                     strokeCap = Paint.Cap.ROUND
488:                 }
489:                 private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
490:                     color = Color.parseColor("#7C3AED")
491:                     style = Paint.Style.FILL
492:                 }
493:                 private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
494:                     color = Color.WHITE
495:                     textSize = 32f
496:                     textAlign = Paint.Align.CENTER
497:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
498:                 }
499:                 private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
500:                     color = Color.parseColor("#A78BFA")
501:                     textSize = 26f
502:                     textAlign = Paint.Align.CENTER
503:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
504:                 }
505:                 private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
506:                     color = Color.parseColor("#9CA3AF")
507:                     textSize = 20f
508:                     textAlign = Paint.Align.CENTER
509:                 }
510: 
511:                 private val tickRunnable = object : Runnable {
512:                     override fun run() {
513:                         if (!isHolding) return
514:                         progress += tickMs.toFloat() / totalMs
515:                         if (progress >= 1f) {
516:                             progress = 1f
517:                             invalidate()
518:                             onSolved()
519:                             return
520:                         }
521:                         invalidate()
522:                         mainHandler.postDelayed(this, tickMs)
523:                     }
524:                 }
525: 
526:                 init {
527:                     setOnTouchListener { _, event ->
528:                         when (event.action) {
529:                             MotionEvent.ACTION_DOWN -> {
530:                                 isHolding = true
531:                                 mainHandler.post(tickRunnable)
532:                                 true
533:                             }
534:                             MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
535:                                 isHolding = false
536:                                 mainHandler.removeCallbacks(tickRunnable)
537:                                 progress = 0f
538:                                 invalidate()
539:                                 true
540:                             }
541:                             else -> false
542:                         }
543:                     }
544:                 }
545: 
546:                 override fun onDraw(canvas: Canvas) {
547:                     val cx = width / 2f
548:                     val cy = height / 2f
549:                     val radius = minOf(cx, cy) * 0.55f
550: 
551:                     // Background card
552:                     canvas.drawRoundRect(cx - radius * 1.4f, cy - radius * 1.7f,
553:                         cx + radius * 1.4f, cy + radius * 1.4f, 32f, 32f, bgPaint)
554: 
555:                     // Title
556:                     canvas.drawText("Hold to Continue", cx, cy - radius * 1.4f, titlePaint)
557: 
558:                     // Arc track
559:                     val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
560:                     canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)
561: 
562:                     // Progress arc
563:                     if (progress > 0f) {
564:                         arcPaint.color = if (progress > 0.8f)
565:                             Color.parseColor("#10B981") else Color.parseColor("#7C3AED")
566:                         canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)
567:                     }
568: 
569:                     // Center button
570:                     btnPaint.color = if (isHolding) Color.parseColor("#5B21B6")
571:                     else Color.parseColor("#7C3AED")
572:                     canvas.drawCircle(cx, cy, radius * 0.72f, btnPaint)
573: 
574:                     // Button text
575:                     val secsLeft = ((1f - progress) * 10f).coerceAtLeast(0f)
576:                     canvas.drawText(if (isHolding) "%.1fs".format(secsLeft) else "Hold", cx, cy + 12f, textPaint)
577: 
578:                     // Sub hint
579:                     canvas.drawText("Release = reset", cx, cy + radius * 1.2f, subPaint)
580:                 }
581:             }
582:             addView(holdView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
583:         }
584:     }
585: 
586:     // ═════════════════════════════════════════════════════════════════════════
587:     // Tic-Tac-Toe View (Minimax AI)
588:     // ═════════════════════════════════════════════════════════════════════════
589: 
590:     @SuppressLint("ViewConstructor")
591:     private inner class TicTacToeView(ctx: Context, private val onSolved: () -> Unit) : View(ctx) {
592: 
593:         private val board = Array(3) { IntArray(3) }  // 0=empty, 1=player(X), 2=bot(O)
594:         private var gameOver = false
595:         private var statusMessage = "Your turn — get 3 in a row"
596: 
597:         // Paints
598:         private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
599:             color = Color.parseColor("#1A1040"); style = Paint.Style.FILL
600:         }
601:         private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
602:             color = Color.parseColor("#4C1D95"); style = Paint.Style.STROKE; strokeWidth = 6f
603:         }
604:         private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
605:             color = Color.parseColor("#7C3AED"); style = Paint.Style.STROKE
606:             strokeWidth = 12f; strokeCap = Paint.Cap.ROUND
607:         }
608:         private val oPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
609:             color = Color.parseColor("#06B6D4"); style = Paint.Style.STROKE
610:             strokeWidth = 10f
611:         }
612:         private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
613:             color = Color.parseColor("#A78BFA"); textSize = 28f; textAlign = Paint.Align.CENTER
614:             typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
615:         }
616:         private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
617:             color = Color.parseColor("#D1D5DB"); textSize = 22f; textAlign = Paint.Align.CENTER
618:         }
619: 
620:         private var cellSize = 0f
621:         private var gridLeft = 0f
622:         private var gridTop = 0f
623: 
624:         override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
625:             cellSize = minOf(w, h) * 0.22f
626:             gridLeft = w / 2f - cellSize * 1.5f
627:             gridTop = h / 2f - cellSize * 1.5f
628:         }
629: 
630:         @SuppressLint("ClickableViewAccessibility")
631:         override fun onTouchEvent(event: MotionEvent): Boolean {
632:             if (event.action != MotionEvent.ACTION_DOWN || gameOver) return true
633:             val col = ((event.x - gridLeft) / cellSize).toInt()
634:             val row = ((event.y - gridTop) / cellSize).toInt()
635:             if (row !in 0..2 || col !in 0..2 || board[row][col] != 0) return true
636: 
637:             board[row][col] = 1 // player move
638:             if (checkWinOrDraw()) return true
639: 
640:             // Bot move via minimax
641:             val (br, bc) = bestMove()
642:             if (br != -1) {
643:                 board[br][bc] = 2
644:                 checkWinOrDraw()
645:             }
646:             invalidate()
647:             return true
648:         }
649: 
650:         private fun checkWinOrDraw(): Boolean {
651:             val winner = getWinner()
652:             return when {
653:                 winner == 1 -> { statusMessage = "You win! Continuing..."; gameOver = true; invalidate(); postDelayed({ onSolved() }, 800); true }
654:                 winner == 2 -> { statusMessage = "Bot wins — try again"; resetBoard(); true }
655:                 isBoardFull() -> { statusMessage = "Draw! Continuing..."; gameOver = true; invalidate(); postDelayed({ onSolved() }, 800); true }
656:                 else -> false
657:             }
658:         }
659: 
660:         private fun resetBoard() {
661:             for (r in 0..2) for (c in 0..2) board[r][c] = 0
662:             gameOver = false
663:             statusMessage = "Your turn — get 3 in a row"
664:             invalidate()
665:         }
666: 
667:         private fun getWinner(): Int {
668:             for (i in 0..2) {
669:                 if (board[i][0] != 0 && board[i][0] == board[i][1] && board[i][1] == board[i][2]) return board[i][0]
670:                 if (board[0][i] != 0 && board[0][i] == board[1][i] && board[1][i] == board[2][i]) return board[0][i]
671:             }
672:             if (board[0][0] != 0 && board[0][0] == board[1][1] && board[1][1] == board[2][2]) return board[0][0]
673:             if (board[0][2] != 0 && board[0][2] == board[1][1] && board[1][1] == board[2][0]) return board[0][2]
674:             return 0
675:         }
676: 
677:         private fun isBoardFull() = board.all { row -> row.all { it != 0 } }
678: 
679:         // Minimax — always finds optimal move for bot (O=2)
680:         private fun minimax(depth: Int, isMaximizing: Boolean): Int {
681:             val winner = getWinner()
682:             if (winner == 2) return 10 - depth
683:             if (winner == 1) return depth - 10
684:             if (isBoardFull()) return 0
685:             var best = if (isMaximizing) Int.MIN_VALUE else Int.MAX_VALUE
686:             for (r in 0..2) for (c in 0..2) {
687:                 if (board[r][c] == 0) {
688:                     board[r][c] = if (isMaximizing) 2 else 1
689:                     val score = minimax(depth + 1, !isMaximizing)
690:                     board[r][c] = 0
691:                     best = if (isMaximizing) maxOf(best, score) else minOf(best, score)
692:                 }
693:             }
694:             return best
695:         }
696: 
697:         private fun bestMove(): Pair<Int, Int> {
698:             var bestScore = Int.MIN_VALUE
699:             var moveRow = -1; var moveCol = -1
700:             for (r in 0..2) for (c in 0..2) {
701:                 if (board[r][c] == 0) {
702:                     board[r][c] = 2
703:                     val score = minimax(0, false)
704:                     board[r][c] = 0
705:                     if (score > bestScore) { bestScore = score; moveRow = r; moveCol = c }
706:                 }
707:             }
708:             return Pair(moveRow, moveCol)
709:         }
710: 
711:         override fun onDraw(canvas: Canvas) {
712:             val cx = width / 2f
713:             val cy = height / 2f
714: 
715:             // Background card
716:             val cardW = cellSize * 3 + 80f
717:             canvas.drawRoundRect(cx - cardW / 2, cy - cardW / 2 - 80f,
718:                 cx + cardW / 2, cy + cardW / 2 + 60f, 32f, 32f, bgPaint)
719: 
720:             // Title
721:             canvas.drawText("Tic-Tac-Toe", cx, cy - cellSize * 1.5f - 32f, titlePaint)
722: 
723:             // Grid lines
724:             for (i in 1..2) {
725:                 canvas.drawLine(gridLeft + cellSize * i, gridTop, gridLeft + cellSize * i, gridTop + cellSize * 3, linePaint)
726:                 canvas.drawLine(gridLeft, gridTop + cellSize * i, gridLeft + cellSize * 3, gridTop + cellSize * i, linePaint)
727:             }
728: 
729:             // Pieces
730:             val pad = cellSize * 0.2f
731:             for (r in 0..2) for (c in 0..2) {
732:                 val x = gridLeft + c * cellSize
733:                 val y = gridTop + r * cellSize
734:                 when (board[r][c]) {
735:                     1 -> { // X
736:                         canvas.drawLine(x + pad, y + pad, x + cellSize - pad, y + cellSize - pad, xPaint)
737:                         canvas.drawLine(x + cellSize - pad, y + pad, x + pad, y + cellSize - pad, xPaint)
738:                     }
739:                     2 -> canvas.drawCircle(x + cellSize / 2, y + cellSize / 2, cellSize / 2 - pad, oPaint) // O
740:                 }
741:             }
742: 
743:             // Status
744:             canvas.drawText(statusMessage, cx, gridTop + cellSize * 3 + 44f, statusPaint)
745:         }
746:     }
747: }
748: 
The above content shows the entire, complete file contents of the requested file.

