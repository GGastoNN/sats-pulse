package com.satspulse.game

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sats_pulse_state")

class GameStore(private val context: Context) {
    private val unlockedKey = stringSetPreferencesKey("unlocked_skus")
    private val selectedSkinKey = stringPreferencesKey("selected_skin")

    val unlockedSkus: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[unlockedKey] ?: emptySet()
    }

    val selectedSkin: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[selectedSkinKey] ?: "skin_default"
    }

    fun bestScore(levelId: Int): Flow<Int> {
        val key = intPreferencesKey("best_level_$levelId")
        return context.dataStore.data.map { prefs -> prefs[key] ?: 0 }
    }

    suspend fun unlockSku(sku: String) {
        context.dataStore.edit { prefs ->
            prefs[unlockedKey] = (prefs[unlockedKey] ?: emptySet()) + sku
        }
    }

    suspend fun selectSkin(sku: String) {
        context.dataStore.edit { prefs -> prefs[selectedSkinKey] = sku }
    }

    suspend fun saveBest(levelId: Int, score: Int) {
        val key = intPreferencesKey("best_level_$levelId")
        context.dataStore.edit { prefs ->
            val current = prefs[key] ?: 0
            if (score > current) prefs[key] = score
        }
    }
}
