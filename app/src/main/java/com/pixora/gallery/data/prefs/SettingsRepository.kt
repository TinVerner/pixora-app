package com.pixora.gallery.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val language: String = "ru",
    val backgroundEnabled: Boolean = false,
    val onlyCharging: Boolean = true,
    val batteryNotLow: Boolean = true,
    val intervalHours: Int = 12,
    val maxFilesPerRun: Int = 40,
    val similarityThreshold: Int = 10,
    val autoClassify: Boolean = false,
    val oldScreenshotDays: Int = 90,
    val excludedBuckets: Set<String> = emptySet(),
    val duplicateScanAt: Long = 0L,
    val similarScanAt: Long = 0L,
    val labelScanAt: Long = 0L,
)

private val Context.dataStore by preferencesDataStore("pixora_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val flow: Flow<UserSettings> = store.data.map { it.toSettings() }

    suspend fun current(): UserSettings = flow.first()

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[THEME] = next.theme.name
            prefs[DYNAMIC] = next.dynamicColor
            prefs[LANGUAGE] = next.language
            prefs[BACKGROUND] = next.backgroundEnabled
            prefs[CHARGING] = next.onlyCharging
            prefs[BATTERY] = next.batteryNotLow
            prefs[INTERVAL] = next.intervalHours
            prefs[MAX_FILES] = next.maxFilesPerRun
            prefs[THRESHOLD] = next.similarityThreshold.coerceIn(4, 20)
            prefs[AUTO] = next.autoClassify
            prefs[OLD_DAYS] = next.oldScreenshotDays.coerceIn(7, 3650)
            prefs[BUCKETS] = next.excludedBuckets
            prefs[DUP_AT] = next.duplicateScanAt
            prefs[SIM_AT] = next.similarScanAt
            prefs[LABEL_AT] = next.labelScanAt
        }
    }

    private fun Preferences.toSettings(): UserSettings = UserSettings(
        theme = ThemeMode.entries.firstOrNull { it.name == this[THEME] } ?: ThemeMode.SYSTEM,
        dynamicColor = this[DYNAMIC] ?: true,
        language = this[LANGUAGE] ?: "ru",
        backgroundEnabled = this[BACKGROUND] ?: false,
        onlyCharging = this[CHARGING] ?: true,
        batteryNotLow = this[BATTERY] ?: true,
        intervalHours = this[INTERVAL] ?: 12,
        maxFilesPerRun = this[MAX_FILES] ?: 40,
        similarityThreshold = this[THRESHOLD] ?: 10,
        autoClassify = this[AUTO] ?: false,
        oldScreenshotDays = this[OLD_DAYS] ?: 90,
        excludedBuckets = this[BUCKETS] ?: emptySet(),
        duplicateScanAt = this[DUP_AT] ?: 0L,
        similarScanAt = this[SIM_AT] ?: 0L,
        labelScanAt = this[LABEL_AT] ?: 0L,
    )

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("dynamic")
        val LANGUAGE = stringPreferencesKey("language")
        val BACKGROUND = booleanPreferencesKey("background")
        val CHARGING = booleanPreferencesKey("charging")
        val BATTERY = booleanPreferencesKey("battery")
        val INTERVAL = intPreferencesKey("interval")
        val MAX_FILES = intPreferencesKey("max_files")
        val THRESHOLD = intPreferencesKey("threshold")
        val AUTO = booleanPreferencesKey("auto")
        val OLD_DAYS = intPreferencesKey("old_days")
        val BUCKETS = stringSetPreferencesKey("buckets")
        val DUP_AT = longPreferencesKey("dup_at")
        val SIM_AT = longPreferencesKey("sim_at")
        val LABEL_AT = longPreferencesKey("label_at")
    }
}
