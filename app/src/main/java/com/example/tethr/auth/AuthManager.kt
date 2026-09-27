package com.example.tethr.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.example.tethr.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import com.example.tethr.data.Supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuthManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("TethrAuth", Context.MODE_PRIVATE)
    private val credentialManager = CredentialManager.create(context)
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkExistingAuth()
        CoroutineScope(Dispatchers.IO).launch {
            Supabase.client.auth.sessionStatus.collect { status ->
                val user = Supabase.client.auth.currentUserOrNull()
                if (user != null) {
                    val meta = user.userMetadata
                    _authState.value = AuthState.Authenticated(
                        user.email ?: "",
                        meta?.get("full_name")?.toString()?.removeSurrounding("\"") ?: "User",
                        meta?.get("avatar_url")?.toString()?.removeSurrounding("\"")
                    )
                } else {
                    _authState.value = AuthState.Unauthenticated
                }
            }
        }
    }

    private fun checkExistingAuth() {
        val user = Supabase.client.auth.currentUserOrNull()
        if (user != null) {
            val meta = user.userMetadata
            _authState.value = AuthState.Authenticated(
                user.email ?: "",
                meta?.get("full_name")?.toString()?.removeSurrounding("\"") ?: "User",
                meta?.get("avatar_url")?.toString()?.removeSurrounding("\"")
            )
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    suspend fun signInWithGoogle(): String? {
        try {
            val rawNonce = java.util.UUID.randomUUID().toString()
            val bytes = rawNonce.toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val hashedNonce = digest.joinToString("") { "%02x".format(it) }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.WEB_CLIENT_ID)
                .setNonce(hashedNonce)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                // Supabase Auth Integration
                val token = googleIdTokenCredential.idToken
                Supabase.client.auth.signInWith(IDToken) {
                    idToken = token
                    provider = Google
                }
                
                val user = Supabase.client.auth.currentUserOrNull()
                if (user != null) {
                    val meta = user.userMetadata
                    _authState.value = AuthState.Authenticated(
                        user.email ?: "",
                        meta?.get("full_name")?.toString()?.removeSurrounding("\"") ?: "User",
                        meta?.get("avatar_url")?.toString()?.removeSurrounding("\"")
                    )
                }
                return null
            }
        } catch (e: GetCredentialException) {
            val type = e.javaClass.simpleName
            android.util.Log.e("AuthManager", "Sign in failed: ${e.message}")
            return "Fail: $type - ${e.message}"
        } catch (e: Exception) {
            android.util.Log.e("AuthManager", "Unexpected error: ${e.message}")
            return "Error: ${e.message}"
        }
        return "Unknown error"
    }
    
    fun signOut() {
        prefs.edit().clear().apply()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Supabase.client.auth.signOut()
            } catch (e: Exception) {
                Log.e("AuthManager", "Sign out error", e)
            }
        }
        _authState.value = AuthState.Unauthenticated
    }
}

sealed class AuthState {
    object Loading : AuthState()
    object Unauthenticated : AuthState()
    data class Authenticated(val email: String, val name: String, val profilePicUrl: String?) : AuthState()
}
