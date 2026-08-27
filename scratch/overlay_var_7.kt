Created At: 2026-08-27T21:18:45+05:30
Completed At: 2026-08-27T21:18:45+05:30
File Path: `file:///c:/Users/tharik/OneDrive/Documents/tethr/app/src/main/java/com/example/tethr/OverlayManager.kt`
Total Lines: 857
Total Bytes: 50777
Showing lines 1 to 60
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
The above content does NOT show the entire file contents. If you need to view any lines of the file which were not shown to complete your task, call this tool again to view those lines.

