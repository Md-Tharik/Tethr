package com.example.tethr

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.tethr.ui.main.MainScreen

import com.example.tethr.billing.BillingManager
import com.example.tethr.billing.SupporterStore
import com.example.tethr.ui.supporter.SupporterScreen

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.example.tethr.auth.AuthManager
import com.example.tethr.auth.AuthState
import com.example.tethr.ui.auth.LoginScreen
import com.example.tethr.ui.onboarding.OnboardingScreen
import androidx.compose.ui.platform.LocalContext
import android.content.Context

@Composable
fun MainNavigation(billingManager: BillingManager, supporterStore: SupporterStore, authManager: AuthManager) {
  val authState by authManager.authState.collectAsState()
  
  val context = LocalContext.current
  val prefs = context.getSharedPreferences("TethrPrefs", Context.MODE_PRIVATE)
  val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)

  // Decide the initial route based on synchronous initial state
  val initialRoute = if (authManager.authState.value is AuthState.Authenticated) {
      if (hasSeenOnboarding) Main else Onboarding
  } else {
      Login
  }
  val backStack = rememberNavBackStack(initialRoute)

  // Listen to auth state changes to force navigation to login when signed out
  LaunchedEffect(authState) {
      if (authState is AuthState.Unauthenticated && backStack.lastOrNull() != Login) {
          backStack.clear()
          backStack.add(Login)
      } else if (authState is AuthState.Authenticated && backStack.lastOrNull() == Login) {
          backStack.clear()
          backStack.add(Main)
      }
  }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Login> {
          LoginScreen(
            authManager = authManager,
            onLoginSuccess = {
                backStack.clear()
                if (hasSeenOnboarding) {
                    backStack.add(Main)
                } else {
                    backStack.add(Onboarding)
                }
            }
          )
        }
        entry<Main> {
          MainScreen(
              onItemClick = { navKey -> backStack.add(navKey) },
              modifier = Modifier.safeDrawingPadding().padding(16.dp),
              authManager = authManager,
              supporterStore = supporterStore
          )
        }
        entry<SupporterScreenKey> {
          SupporterScreen(
            onBack = { backStack.removeLastOrNull() },
            billingManager = billingManager,
            supporterStore = supporterStore
          )
        }
        entry<Onboarding> {
          OnboardingScreen(
            onFinish = {
                prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                backStack.clear()
                backStack.add(Main)
            }
          )
        }
      },
  )
}
