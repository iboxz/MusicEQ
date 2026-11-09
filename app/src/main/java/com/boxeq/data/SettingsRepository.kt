package com.boxeq.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.boxDataStore: DataStore<Preferences> by preferencesDataStore(name = "boxeq_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.boxDataStore

    val config: Flow<EqConfig> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toConfig() }
        .distinctUntilChanged()

    /** Atomic read-modify-write. */
    suspend fun update(transform: (EqConfig) -> EqConfig) {
        store.edit { prefs -> prefs.write(transform(prefs.toConfig())) }
    }

    private fun Preferences.toConfig(): EqConfig {
        val d = EqConfig()
        return EqConfig(
            masterEnabled = this[MASTER] ?: d.masterEnabled,
            limiterEnabled = this[LIMITER] ?: d.limiterEnabled,
            shizukuEnabled = this[SHIZUKU] ?: d.shizukuEnabled,
            onboardingDone = this[ONBOARDING] ?: d.onboardingDone,
            profiles = ConfigCodec.decodeProfiles(this[PROFILES]) ?: d.profiles,
            userPresets = ConfigCodec.decodePresets(this[PRESETS]),
        )
    }

    private fun MutablePreferences.write(c: EqConfig) {
        this[MASTER] = c.masterEnabled
        this[LIMITER] = c.limiterEnabled
        this[SHIZUKU] = c.shizukuEnabled
        this[ONBOARDING] = c.onboardingDone
        this[PROFILES] = ConfigCodec.encodeProfiles(c.profiles)
        this[PRESETS] = ConfigCodec.encodePresets(c.userPresets)
    }

    private companion object {
        val MASTER = booleanPreferencesKey("master_enabled")
        val LIMITER = booleanPreferencesKey("limiter_enabled")
        val SHIZUKU = booleanPreferencesKey("shizuku_enabled")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val PROFILES = stringPreferencesKey("profiles_json")
        val PRESETS = stringPreferencesKey("user_presets_json")
    }
}
