package com.example.tethr

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle

/**
 * A completely transparent, instant-finish Activity.
 * Used exclusively as a hack for Vivo/Funtouch OS to force the Android WindowManager 
 * to perform a full compositor layer rebuild. This bypasses the OS-level shadow-ban 
 * on accessibility overlays after the notification shade is pulled down.
 */
class DummyRelayoutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make window completely transparent
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        
        // Finish instantly without any animation
        finish()
        overridePendingTransition(0, 0)
    }
}
