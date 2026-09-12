package com.satspulse.game

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sats_pulse_state")

class GameStore(private val context: Context) {
    private val unlockedKey = stringSetPreferencesKey("unlocked_skus")
    private val selectedSkinKey = stringPreferencesKey("selected_skin")
    private val languageKey = stringPreferencesKey("language")
    private val xpKey = intPreferencesKey("xp")
    private val totalRunsKey = intPreferencesKey("total_runs")
    private val totalScoreKey = intPreferencesKey("total_score")
    private val bestComboKey = intPreferencesKey("best_combo")
    private val perfectRunsKey = intPreferencesKey("perfect_runs")
    private val claimedChallengesKey = stringSetPreferencesKey("claimed_challenges")

    val unlockedSkus: Flow<Set<String>> = context.dataStore.data.map { it[unlockedKey] ?: emptySet() }
    val selectedSkin: Flow<String> = context.dataStore.data.map { it[selectedSkinKey] ?: "skin_default" }
    val language: Flow<AppLanguage> = context.dataStore.data.map { AppLanguage.fromCode(it[languageKey]) }
    val claimedChallenges: Flow<Set<String>> = context.dataStore.data.map { it[claimedChallengesKey] ?: emptySet() }
    val bestScores: Flow<Map<Int, Int>> = context.dataStore.data.map { prefs ->
        LEVELS.associate { level -> level.id to (prefs[intPreferencesKey("best_level_${level.id}")] ?: 0) }
    }

    val stats: Flow<PlayerStats> = combine(
        context.dataStore.data.map { it[xpKey] ?: 0 },
        context.dataStore.data.map { it[totalRunsKey] ?: 0 },
        context.dataStore.data.map { it[totalScoreKey] ?: 0 },
        context.dataStore.data.map { it[bestComboKey] ?: 0 },
        context.dataStore.data.map { it[perfectRunsKey] ?: 0 }
    ) { xp, runs, totalScore, bestCombo, perfectRuns ->
        PlayerStats(xp, runs, totalScore, bestCombo, perfectRuns)
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

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { prefs -> prefs[languageKey] = language.code }
    }

    suspend fun recordRun(levelId: Int, score: Int, bestCombo: Int, misses: Int): Int {
        val scoreKey = intPreferencesKey("best_level_$levelId")
        val gainedXp = (score / 120).coerceIn(8, 80) + (if (misses == 0) 25 else 0) + (if (bestCombo >= 10) 20 else 0)
        context.dataStore.edit { prefs ->
            val currentBest = prefs[scoreKey] ?: 0
            if (score > currentBest) prefs[scoreKey] = score
            prefs[totalRunsKey] = (prefs[totalRunsKey] ?: 0) + 1
            prefs[totalScoreKey] = (prefs[totalScoreKey] ?: 0) + score
            prefs[bestComboKey] = maxOf(prefs[bestComboKey] ?: 0, bestCombo)
            if (misses == 0) prefs[perfectRunsKey] = (prefs[perfectRunsKey] ?: 0) + 1
            prefs[xpKey] = (prefs[xpKey] ?: 0) + gainedXp
        }
        return gainedXp
    }

    suspend fun claimChallenge(challenge: ChallengeSpec) {
        context.dataStore.edit { prefs ->
            val claimed = prefs[claimedChallengesKey] ?: emptySet()
            if (challenge.id !in claimed) {
                prefs[claimedChallengesKey] = claimed + challenge.id
                prefs[xpKey] = (prefs[xpKey] ?: 0) + challenge.xpReward
            }
        }
    }
}
