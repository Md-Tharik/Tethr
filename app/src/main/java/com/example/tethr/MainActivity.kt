package com.example.tethr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.tethr.theme.TethrTheme

import com.example.tethr.billing.BillingManager
import com.example.tethr.billing.SupporterStore
import com.example.tethr.update.AppUpdateHelper
import com.google.android.play.core.install.model.AppUpdateType

class MainActivity : ComponentActivity() {
  private lateinit var supporterStore: SupporterStore
  private lateinit var billingManager: BillingManager
  private lateinit var appUpdateHelper: AppUpdateHelper

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    supporterStore = SupporterStore(this)
    billingManager = BillingManager(this, supporterStore)
    
    appUpdateHelper = AppUpdateHelper(this)
    appUpdateHelper.registerListener()
    appUpdateHelper.checkAndStartUpdate()

    enableEdgeToEdge()
    setContent {
      TethrTheme { 
        val snackbarHostState = remember { SnackbarHostState() }
        val updateDownloaded by appUpdateHelper.updateDownloaded.collectAsState()
        
        LaunchedEffect(updateDownloaded) {
            if (updateDownloaded) {
                val result = snackbarHostState.showSnackbar(
                    message = "An update has just been downloaded.",
                    actionLabel = "RESTART",
                    duration = SnackbarDuration.Indefinite
                )
                if (result == SnackbarResult.ActionPerformed) {
                    appUpdateHelper.completeUpdate()
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { 
                MainNavigation(billingManager, supporterStore) 
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
      }
    }
  }

  override fun onResume() {
      super.onResume()
      appUpdateHelper.onResume()
  }

  override fun onDestroy() {
      super.onDestroy()
      appUpdateHelper.unregisterListener()
  }
}
