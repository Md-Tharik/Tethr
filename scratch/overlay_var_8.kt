Created At: 2026-08-27T21:50:02+05:30
Completed At: 2026-08-27T21:50:02+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/OverlayManager.kt`
Total Lines: 857
Total Bytes: 50777
Showing lines 1 to 800
Content truncated: showing bytes 0-46080 of 48661. To see more, call this tool again with the same line range and ContentOffset=46080.
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
19: // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
20: // OverlayManager
21: // Central controller for all three escalation tiers drawn via WindowManager.
22: //
23: // Tier 1 â€” Floating pill (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE)
24: // Tier 2 â€” Full-screen grayscale ColorMatrix overlay (pass-through, non-blocking)
25: // Tier 3 â€” Blocking cognitive task modal (FOCUSABLE | TOUCHABLE)
26: // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
27: class OverlayManager(private val context: Context) {
28: 
29:     private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
30:     private val mainHandler = Handler(Looper.getMainLooper())
31:     private val prefs = context.getSharedPreferences("tethr_prefs", Context.MODE_PRIVATE)
32: 
33:     // â”€â”€ View references â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
34:     private var pillView: PillOverlayView? = null
35:     private var taskView: TaskOverlayView? = null
36: 
37:     // â”€â”€ Task rotation state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
62:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
63:     // Public API
64:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
65: 
66:     /** Called when Instagram opens â€” shows the Tier 1 pill. */
67:     fun showTier1(activeTimeMs: Long) {
68:         mainHandler.post {
69:             ensurePill()
70:             pillView?.updateTime(activeTimeMs)
71:             hideTask()
72:         }
73:     }
74: 
75:     /** Called when grayscale trigger fires â€” keeps pill, activates hardware grayscale. */
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
105:      * INSTANT CLEANUP â€” called when:
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
116:             Log.d(TAG, "hideAll() â€” all overlays removed & hardware grayscale disabled")
117:         }
118:     }
119: 
120:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
121:     // Hardware Grayscale (Daltonizer via Settings.Secure)
122:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
143:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
144:     // Pill (Tier 1)
145:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
165:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
166:     // Task modal (Tier 3)
167:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
199:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
200:     // WindowManager safe helpers
201:     // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
226:     // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
227:     // TIER 1 â€” Floating Pill View
228:     // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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
284:     // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
285:     // TIER 3 â€” Rotating Cognitive Task Modal
286:     // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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
310:         // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
311:         // Task 1 â€” Mental Math
312:         // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
313: 
314:         @SuppressLint("SetTextI18n")
315:         private fun buildMentalMathTask() {
316:             // Fix 3 â€” number ranges per spec: n1 in 12..49, n2 in 11..48
317:             val n1 = (12..49).random()
318:             val n2 = (11..48).random()
319:             val correctAnswer = n1 + n2
320: 
321:             // â”€â”€ Root container (vertical, centred) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
322:             val root = android.widget.LinearLayout(ctx).apply {
323:                 orientation = android.widget.LinearLayout.VERTICAL
324:                 gravity = Gravity.CENTER
325:                 setBackgroundColor(Color.parseColor("#EE000000"))
326:                 layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
327:             }
328: 
329:             // â”€â”€ Card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
330:             val card = android.widget.LinearLayout(ctx).apply {
331:                 orientation = android.widget.LinearLayout.VERTICAL
332:                 gravity = Gravity.CENTER
333:                 setBackgroundColor(Color.parseColor("#111111"))
334:                 setPadding(48, 48, 48, 48)
335:             }
336: 
337:             // â”€â”€ Title â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
338:             val tvTitle = android.widget.TextView(ctx).apply {
339:                 text = "Quick Math"
340:                 textSize = 18f
341:                 setTextColor(Color.parseColor("#AAAAAA"))
342:                 typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
343:                 gravity = Gravity.CENTER
344:                 setPadding(0, 0, 0, 16)
345:             }
346: 
347:             // Fix 3 â€” question text: white, 24sp bold, always visible
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
358:             // â”€â”€ Error text (hidden until wrong answer) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
359:             val tvError = android.widget.TextView(ctx).apply {
360:                 text = ""
361:                 textSize = 14f
362:                 setTextColor(Color.parseColor("#FF5252"))  // red per spec
363:                 gravity = Gravity.CENTER
364:                 visibility = View.INVISIBLE
365:                 setPadding(0, 0, 0, 8)
366:             }
367: 
368:             // â”€â”€ Input field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
369:             // Fix 3 â€” high-contrast background #2A2A38, white text, grey hint
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
384:             // â”€â”€ Submit button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
403:                             // Correct â€” dismiss
404:                             tvError.visibility = View.INVISIBLE
405:                             onSolved()
406:                         }
407:                         else -> {
408:                             // Wrong â€” show red error, clear field
409:                             editText.text.clear()
410:                             tvError.text = "Wrong! Try again"
411:                             tvError.visibility = View.VISIBLE
412:                         }
413:                     }
414:                 }
415:             }
416: 
417:             // â”€â”€ Hint â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
418:             val tvHint = android.widget.TextView(ctx).apply {
419:                 text = "Solve to continue scrolling"
420:                 textSize = 12f
421:                 setTextColor(Color.parseColor("#9CA3AF"))
422:                 gravity = Gravity.CENTER
423:                 setPadding(0, 16, 0, 0)
424:             }
425: 
426:             // â”€â”€ Assemble â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
450:         // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
451:         // Task 2 â€” Micro Tic-Tac-Toe (vs Minimax bot)
452:         // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
453: 
454:         private fun buildTicTacToeTask() {
455:             val tttView = TicTacToeView(ctx) { onSolved() }
456:             addView(tttView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
457:         }
458: 
459:         // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
460:         // Task 3 â€” 10-Second Hold Button
461:         // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
462: 
463:         @SuppressLint("ClickableViewAccessibility")
464:         private fun buildHoldButtonTask() {
465:             val holdView = object : View(ctx) {
466:                 private var progress = 0f   // 0.0 to 1.0
467:                 private var isHolding = false
468:                 private val tickMs = 50L
469:                 private val totalMs = 10_000L
470:                 
471:                 private val quotes = listOf(
472:                     "Time is gold.",
473:                     "Your focus is your reality.",
474:                     "Breathe in, breathe out.",
475:                     "Be present in this moment.",
476:                     "Disconnect to reconnect.",
477:                     "Mind over pixels.",
478:                     "Look up, the world is waiting.",
479:                     "Scroll less, live more.",
480:                     "Reclaim your attention.",
481:                     "You are in control.",
482:                     "Peace is found offline.",
483:                     "Silence the noise.",
484:                     "Stop, breathe, think.",
485:                     "Your time is precious.",
486:                     "Protect your peace.",
487:                     "Every second counts.",
488:                     "Escape the infinite scroll.",
489:                     "Real life happens offline.",
490:                     "Value your time.",
491:                     "Stay grounded.",
492:                     "Focus on what matters.",
493:                     "Don't let algorithms win.",
494:                     "Life is outside the screen.",
495:                     "Take back your mind.",
496:                     "Be intentional today.",
497:                     "Notice the world around you.",
498:                     "You have a choice.",
499:                     "Unplug for a moment.",
500:                     "Cultivate stillness.",
501:                     "Your attention is your power.",
502:                     "Find joy in the real world.",
503:                     "Step away from the feed.",
504:                     "Break the loop.",
505:                     "Embrace the silence.",
506:                     "Free your mind.",
507:                     "Choose reality.",
508:                     "Slow down.",
509:                     "Observe your thoughts.",
510:                     "Digital minimalism.",
511:                     "Less scrolling, more living.",
512:                     "Master your habits.",
513:                     "Recharge yourself, not just your phone.",
514:                     "Seek real connections.",
515:                     "The present moment is all we have.",
516:                     "Don't trade your time for nothing.",
517:                     "Awaken to the real world.",
518:                     "Choose peace over stimulation.",
519:                     "Mindful tech usage.",
520:                     "You are not your feed.",
521:                     "Time spent scrolling is time lost."
522:                 )
523:                 private var currentQuote = quotes.random()
524: 
525:                 private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
526:                     color = Color.parseColor("#111111")
527:                     style = Paint.Style.FILL
528:                 }
529:                 private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
530:                     color = Color.parseColor("#2A2A2A")
531:                     style = Paint.Style.STROKE
532:                     strokeWidth = 24f
533:                     strokeCap = Paint.Cap.ROUND
534:                 }
535:                 private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
536:                     color = Color.parseColor("#FFFFFF")
537:                     style = Paint.Style.STROKE
538:                     strokeWidth = 24f
539:                     strokeCap = Paint.Cap.ROUND
540:                 }
541:                 private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
542:                     color = Color.parseColor("#222222")
543:                     style = Paint.Style.FILL
544:                 }
545:                 private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
546:                     color = Color.WHITE
547:                     textSize = 32f
548:                     textAlign = Paint.Align.CENTER
549:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
550:                 }
551:                 private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
552:                     color = Color.parseColor("#AAAAAA")
553:                     textSize = 26f
554:                     textAlign = Paint.Align.CENTER
555:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
556:                 }
557:                 private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
558:                     color = Color.parseColor("#666666")
559:                     textSize = 20f
560:                     textAlign = Paint.Align.CENTER
561:                 }
562:                 private val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
563:                     color = Color.parseColor("#EEEEEE")
564:                     textSize = 32f
565:                     textAlign = Paint.Align.CENTER
566:                     typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
567:                 }
568: 
569:                 private val tickRunnable = object : Runnable {
570:                     override fun run() {
571:                         if (!isHolding) return
572:                         progress += tickMs.toFloat() / totalMs
573:                         if (progress >= 1f) {
574:                             progress = 1f
575:                             invalidate()
576:                             onSolved()
577:                             return
578:                         }
579:                         invalidate()
580:                         mainHandler.postDelayed(this, tickMs)
581:                     }
582:                 }
583: 
584:                 init {
585:                     setOnTouchListener { _, event ->
586:                         when (event.action) {
587:                             MotionEvent.ACTION_DOWN -> {
588:                                 currentQuote = quotes.random()
589:                                 isHolding = true
590:                                 mainHandler.post(tickRunnable)
591:                                 true
592:                             }
593:                             MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
594:                                 isHolding = false
595:                                 mainHandler.removeCallbacks(tickRunnable)
596:                                 progress = 0f
597:                                 invalidate()
598:                                 true
599:                             }
600:                             else -> false
601:                         }
602:                     }
603:                 }
604: 
605:                 override fun onDraw(canvas: Canvas) {
606:                     val cx = width / 2f
607:                     val cy = height / 2f
608:                     val radius = minOf(cx, cy) * 0.55f
609: 
610:                     // Background card
611:                     canvas.drawRoundRect(cx - radius * 1.4f, cy - radius * 1.7f,
612:                         cx + radius * 1.4f, cy + radius * 1.5f, 32f, 32f, bgPaint)
613: 
614:                     // Title
615:                     canvas.drawText("Breathe to Continue", cx, cy - radius * 1.4f, titlePaint)
616: 
617:                     // Quote with pulsing animation
618:                     if (isHolding) {
619:                         val pulse = (155 + 100 * Math.sin(System.currentTimeMillis() / 250.0)).toInt().coerceIn(0, 255)
620:                         quotePaint.alpha = pulse
621:                         
622:                         val floatOffset = if (progress < 0.1f) {
623:                             (1f - (progress * 10f)) * 15f
624:                         } else 0f
625:                         
626:                         canvas.drawText(currentQuote, cx, cy - radius * 1.15f + floatOffset, quotePaint)
627:                     }
628: 
629:                     // Arc track
630:                     val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
631:                     canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)
632: 
633:                     // Progress arc
634:                     if (progress > 0f) {
635:                         arcPaint.color = if (progress > 0.8f)
636:                             Color.parseColor("#4CAF50") else Color.parseColor("#FFFFFF")
637:                         canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)
638:                     }
639: 
640:                     // Breathing Exercise Logic
641:                     val phaseText = when {
642:                         !isHolding -> "Hold"
643:                         progress < 0.4f -> "Inhale..."
644:                         progress < 0.6f -> "Hold..."
645:                         else -> "Exhale..."
646:                     }
647:                     
648:                     val breatheScale = when {
649:                         !isHolding -> 0.4f
650:                         progress < 0.4f -> 0.4f + (0.6f * (progress / 0.4f)) // 0.4 to 1.0
651:                         progress < 0.6f -> 1.0f // hold at 1.0
652:                         else -> 1.0f - (0.6f * ((progress - 0.6f) / 0.4f)) // 1.0 to 0.4
653:                     }
654: 
655:                     // Center breathing circle
656:                     val currentRadius = radius * 0.95f * breatheScale
657:                     btnPaint.color = if (isHolding) Color.parseColor("#333333") else Color.parseColor("#222222")
658:                     canvas.drawCircle(cx, cy, currentRadius, btnPaint)
659: 
660:                     // Button text
661:                     val secsLeft = ((1f - progress) * 10f).coerceAtLeast(0f)
662:                     
663:                     // Slightly adjust text size if it's very small
664:                     textPaint.textSize = 32f
665:                     canvas.drawText(phaseText, cx, cy - 10f, textPaint)
666:                     
667:                     if (isHolding) {
668:                         subPaint.textSize = 28f
669:                         canvas.drawText(String.format("%.1fs", secsLeft), cx, cy + 30f, subPaint)
670:                         subPaint.textSize = 20f
671:                     } else {
672:                         canvas.drawText("10 Secs", cx, cy + 30f, subPaint)
673:                     }
674: 
675:                     // Sub hint
676:                     canvas.drawText("Release = reset", cx, cy + radius * 1.3f, subPaint)
677:                 }
678:             }
679:             addView(holdView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
680:         }
681:     }
682: 
683:     // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
684:     // Tic-Tac-Toe View (Minimax AI)
685:     // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
686: 
687:     @SuppressLint("ViewConstructor")
688:     private inner class TicTacToeView(ctx: Context, private val onSolved: () -> Unit) : View(ctx) {
689: 
690:         private val board = Array(3) { IntArray(3) }  // 0=empty, 1=player(X), 2=bot(O)
691:         private var gameOver = false
692:         private var botThinking = false
693:         private var statusMessage = "Your turn â€” get 3 in a row"
694: 
695:         // Paints
696:         private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
697:             color = Color.parseColor("#111111"); style = Paint.Style.FILL
698:         }
699:         private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
700:             color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 6f
701:         }
702:         private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
703:             color = Color.parseColor("#FFFFFF"); style = Paint.Style.STROKE
704:             strokeWidth = 12f; strokeCap = Paint.Cap.ROUND
705:         }
706:         private val oPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
707:             color = Color.parseColor("#888888"); style = Paint.Style.STROKE
708:             strokeWidth = 10f
709:         }
710:         private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
711:             color = Color.parseColor("#AAAAAA"); textSize = 28f; textAlign = Paint.Align.CENTER
712:             typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
713:         }
714:         private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
715:             color = Color.parseColor("#888888"); textSize = 22f; textAlign = Paint.Align.CENTER
716:         }
717: 
718:         private var cellSize = 0f
719:         private var gridLeft = 0f
720:         priv
<truncated 1205 bytes>

NOTE: The output was truncated because it was too long. Use a more targeted query or a smaller range to get the information you need.
