package com.example.tethr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: PillOverlayView? = null
    private var frictionView: View? = null

    fun showOverlay() {
        if (overlayView == null) {
            overlayView = PillOverlayView(context)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager.addView(overlayView, params)
        }
    }

    fun updateMetrics(activeTimeMs: Long, isDemoMode: Boolean) {
        val effectiveTimeMs = if (isDemoMode) activeTimeMs * 60 else activeTimeMs
        overlayView?.updateState(effectiveTimeMs)
    }

    fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                Log.e("TethrOverlay", "Error removing overlay: ${e.message}")
            }
            overlayView = null
        }
        hideMathQuiz()
    }

    fun showMathQuiz() {
        // Run on main thread just in case
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (frictionView != null) return@post
            
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FA000000")) // very dark solid
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
            }
            
            val title = android.widget.TextView(context).apply {
                text = "Cognitive Check"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
            }
            
            val num1 = (10..99).random()
            val num2 = (10..99).random()
            val answer = num1 + num2
            
            val question = android.widget.TextView(context).apply {
                text = "$num1 + $num2 = ?"
                setTextColor(Color.WHITE)
                textSize = 36f
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            
            val input = android.widget.EditText(context).apply {
                setTextColor(Color.WHITE)
                textSize = 24f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                setPadding(32, 32, 32, 32)
            }
            
            val btn = android.widget.Button(context).apply {
                text = "Submit to Continue"
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setPadding(32, 32, 32, 32)
                setOnClickListener {
                    if (input.text.toString().trim() == answer.toString()) {
                        hideMathQuiz()
                    } else {
                        input.error = "Incorrect"
                    }
                }
            }
            
            val spacer = android.widget.Space(context)
            spacer.layoutParams = android.widget.LinearLayout.LayoutParams(1, 32)
            
            layout.addView(title)
            layout.addView(question)
            layout.addView(input)
            layout.addView(spacer)
            layout.addView(btn)
            
            frictionView = layout
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                0, // 0 flags means it can receive focus (keyboard) and blocks touches behind it
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager.addView(frictionView, params)
            } catch (e: Exception) {
                Log.e("TethrOverlay", "Error showing math quiz: ${e.message}")
            }
        }
    }

    fun hideMathQuiz() {
        if (frictionView != null) {
            try { windowManager.removeView(frictionView) } catch (e: Exception) {}
            frictionView = null
        }
    }

    fun setSystemGrayscale(enabled: Boolean) {
        try {
            val contentResolver = context.contentResolver
            if (enabled) {
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 1)
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0) // 0 = Grayscale
                Log.d("TethrOverlay", "System Grayscale ON")
            } else {
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)
                Log.d("TethrOverlay", "System Grayscale OFF")
            }
        } catch (e: SecurityException) {
            Log.e("TethrOverlay", "Missing WRITE_SECURE_SETTINGS permission. Run: adb shell pm grant com.example.tethr android.permission.WRITE_SECURE_SETTINGS")
        }
    }

    /**
     * Simple pill overlay that shows active time and reel count.
     * No borders, no gradual washout, no touch interception.
     */
    private inner class PillOverlayView(context: Context) : View(context) {
        private var activeTimeMs: Long = 0

        private val paintPill = Paint().apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val paintText = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        fun updateState(timeMs: Long) {
            this.activeTimeMs = timeMs
            invalidate()
        }

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Only draw the pill — nothing else. No borders, no gray wash.
            val cx = width / 2f
            val cy = 100f
            val rx = 160f
            val ry = 50f

            val minutes = activeTimeMs / 60000
            val seconds = (activeTimeMs % 60000) / 1000
            val timeString = String.format("%02d:%02d", minutes, seconds)

            canvas.drawRoundRect(cx - rx, cy - ry, cx + rx, cy + ry, ry, ry, paintPill)
            canvas.drawText(timeString, cx, cy + 12f, paintText)
        }
    }
}

