package com.example.tethr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.tethr.theme.TethrTheme

import com.example.tethr.billing.BillingManager
import com.example.tethr.billing.SupporterStore

class MainActivity : ComponentActivity() {
  private lateinit var supporterStore: SupporterStore
  private lateinit var billingManager: BillingManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    supporterStore = SupporterStore(this)
    billingManager = BillingManager(this, supporterStore)

    enableEdgeToEdge()
    setContent {
      TethrTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation(billingManager, supporterStore) } }
    }
  }
}
