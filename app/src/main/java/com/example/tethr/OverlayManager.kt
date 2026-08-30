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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: PillOverlayView? = null
    
    private var barrierView: ComposeView? = null
    private var lifecycleOwner: ComposeOverlayLifecycleOwner? = null

    val isBarrierShowing: Boolean
        get() = barrierView != null

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
        hideBarrier()
    }

    fun showBarrier(barrierIndex: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (barrierView != null) return@post
            
            lifecycleOwner = ComposeOverlayLifecycleOwner().apply { onCreate(); onStart(); onResume() }
            
            val composeView = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val barrierType = (barrierIndex - 1) % 3
                    when (barrierType) {
                        0 -> MathBarrierUI(onUnlock = { hideBarrier() })
                        1 -> XoBarrierUI(onUnlock = { hideBarrier() })
                        2 -> BreathingBarrierUI(onUnlock = { hideBarrier() })
                    }
                }
            }
            
            lifecycleOwner?.attachToView(composeView)
            
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
                windowManager.addView(composeView, params)
                barrierView = composeView
            } catch (e: Exception) {
                Log.e("TethrOverlay", "Error showing barrier: \${e.message}")
            }
        }
    }

    fun hideBarrier() {
        if (barrierView != null) {
            try {
                windowManager.removeView(barrierView)
                lifecycleOwner?.onPause()
                lifecycleOwner?.onStop()
                lifecycleOwner?.onDestroy()
            } catch (e: Exception) {}
            barrierView = null
            lifecycleOwner = null
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
    private class PillOverlayView(context: Context) : View(context) {
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
            val rx = 220f
            val ry = 50f

            val minutes = activeTimeMs / 60000
            val seconds = (activeTimeMs % 60000) / 1000
            val timeString = String.format("• Active: %02d:%02d", minutes, seconds)

            canvas.drawRoundRect(cx - rx, cy - ry, cx + rx, cy + ry, ry, ry, paintPill)
            canvas.drawText(timeString, cx, cy + 12f, paintText)
        }
    }
}

