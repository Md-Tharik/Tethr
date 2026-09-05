package com.example.tethr.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "supporter_prefs")

class SupporterStore(private val context: Context) {

    companion object {
        val IS_SUPPORTER = booleanPreferencesKey("is_supporter")
        val TIMER_PILL_BG = stringPreferencesKey("timer_pill_bg")
        val TIMER_PILL_IMAGE_URI = stringPreferencesKey("timer_pill_image_uri")
        val APP_LOGO = stringPreferencesKey("app_logo")
    }

    val isSupporter: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[IS_SUPPORTER] ?: false
    }

    suspend fun setSupporter(isSupporter: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_SUPPORTER] = isSupporter
        }
    }

    val timerPillBg: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TIMER_PILL_BG] ?: "default"
    }

    val timerPillImageUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[TIMER_PILL_IMAGE_URI]
    }

    suspend fun setTimerPillBg(bg: String) {
        context.dataStore.edit { prefs ->
            prefs[TIMER_PILL_BG] = bg
        }
    }

    suspend fun setTimerPillImageUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[TIMER_PILL_IMAGE_URI] = uri
            } else {
                prefs.remove(TIMER_PILL_IMAGE_URI)
            }
        }
    }

    val appLogo: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[APP_LOGO] ?: "default"
    }

    suspend fun setAppLogo(logo: String) {
        context.dataStore.edit { prefs ->
            prefs[APP_LOGO] = logo
        }
    }
}
