package com.devassistant.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dev_settings")

/**
 * Holds user-configurable settings: the Anthropic API key (entered by the user, never bundled
 * in the app), wake-word toggle, and confirmation preferences for high-risk actions.
 */
class SettingsStore(private val context: Context) {

    companion object {
        private val API_KEY = stringPreferencesKey("anthropic_api_key")
        private val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        private val REQUIRE_CONFIRMATION_FOR_HIGH_RISK = booleanPreferencesKey("confirm_high_risk")
    }

    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[WAKE_WORD_ENABLED] ?: true }
    val confirmHighRisk: Flow<Boolean> = context.dataStore.data.map { it[REQUIRE_CONFIRMATION_FOR_HIGH_RISK] ?: true }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[API_KEY] = key }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WAKE_WORD_ENABLED] = enabled }
    }

    suspend fun setConfirmHighRisk(enabled: Boolean) {
        context.dataStore.edit { it[REQUIRE_CONFIRMATION_FOR_HIGH_RISK] = enabled }
    }
}
