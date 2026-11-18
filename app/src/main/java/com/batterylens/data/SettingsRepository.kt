package com.batterylens.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.batterylens.domain.CurrentUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val chargeAlarmEnabled: Boolean = true,
    val chargeTarget: Int = 80,
    val insistentAlarm: Boolean = true,
    val tempAlarmEnabled: Boolean = false,
    val tempAlarmC: Int = 45,
    val lowAlarmEnabled: Boolean = false,
    val lowAlarmLevel: Int = 20,
    /** 0 = use the value reported by the system power profile. */
    val designCapacityOverride: Int = 0,
    val currentUnit: CurrentUnit = CurrentUnit.AUTO,
    val invertCurrent: Boolean = false,
    val sampleIntervalSec: Int = 10,
    /** 0 = keep forever. */
    val retentionDays: Int = 0,
    val startOnBoot: Boolean = true,
    val detailedNotification: Boolean = true,
    val ratedCycles: Int = 500,
)

/** Totals that must survive history pruning. */
data class LifetimeStats(val wearCycles: Double = 0.0, val chargedMah: Double = 0.0)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val THEME = stringPreferencesKey("theme")
        val ALARM = booleanPreferencesKey("charge_alarm")
        val TARGET = intPreferencesKey("charge_target")
        val INSISTENT = booleanPreferencesKey("insistent_alarm")
        val TEMP_ALARM = booleanPreferencesKey("temp_alarm")
        val TEMP_C = intPreferencesKey("temp_alarm_c")
        val LOW_ALARM = booleanPreferencesKey("low_alarm")
        val LOW_LEVEL = intPreferencesKey("low_alarm_level")
        val DESIGN_CAP = intPreferencesKey("design_capacity")
        val UNIT = stringPreferencesKey("current_unit")
        val INVERT = booleanPreferencesKey("invert_current")
        val INTERVAL = intPreferencesKey("sample_interval")
        val RETENTION = intPreferencesKey("retention_days")
        val BOOT = booleanPreferencesKey("start_on_boot")
        val DETAILED = booleanPreferencesKey("detailed_notification")
        val RATED = intPreferencesKey("rated_cycles")
        val LIFE_WEAR = doublePreferencesKey("lifetime_wear")
        val LIFE_MAH = doublePreferencesKey("lifetime_charged_mah")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    val lifetime: Flow<LifetimeStats> = context.dataStore.data.map {
        LifetimeStats(it[K.LIFE_WEAR] ?: 0.0, it[K.LIFE_MAH] ?: 0.0)
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    suspend fun addLifetime(wearCycles: Double, chargedMah: Double) {
        context.dataStore.edit {
            it[K.LIFE_WEAR] = (it[K.LIFE_WEAR] ?: 0.0) + wearCycles
            it[K.LIFE_MAH] = (it[K.LIFE_MAH] ?: 0.0) + chargedMah
        }
    }

    suspend fun resetLifetime() {
        context.dataStore.edit {
            it.remove(K.LIFE_WEAR)
            it.remove(K.LIFE_MAH)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = this[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            chargeAlarmEnabled = this[K.ALARM] ?: d.chargeAlarmEnabled,
            chargeTarget = this[K.TARGET] ?: d.chargeTarget,
            insistentAlarm = this[K.INSISTENT] ?: d.insistentAlarm,
            tempAlarmEnabled = this[K.TEMP_ALARM] ?: d.tempAlarmEnabled,
            tempAlarmC = this[K.TEMP_C] ?: d.tempAlarmC,
            lowAlarmEnabled = this[K.LOW_ALARM] ?: d.lowAlarmEnabled,
            lowAlarmLevel = this[K.LOW_LEVEL] ?: d.lowAlarmLevel,
            designCapacityOverride = this[K.DESIGN_CAP] ?: d.designCapacityOverride,
            currentUnit = this[K.UNIT]?.let { runCatching { CurrentUnit.valueOf(it) }.getOrNull() } ?: d.currentUnit,
            invertCurrent = this[K.INVERT] ?: d.invertCurrent,
            sampleIntervalSec = this[K.INTERVAL] ?: d.sampleIntervalSec,
            retentionDays = this[K.RETENTION] ?: d.retentionDays,
            startOnBoot = this[K.BOOT] ?: d.startOnBoot,
            detailedNotification = this[K.DETAILED] ?: d.detailedNotification,
            ratedCycles = this[K.RATED] ?: d.ratedCycles,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.THEME] = s.theme.name
        this[K.ALARM] = s.chargeAlarmEnabled
        this[K.TARGET] = s.chargeTarget.coerceIn(50, 100)
        this[K.INSISTENT] = s.insistentAlarm
        this[K.TEMP_ALARM] = s.tempAlarmEnabled
        this[K.TEMP_C] = s.tempAlarmC.coerceIn(30, 60)
        this[K.LOW_ALARM] = s.lowAlarmEnabled
        this[K.LOW_LEVEL] = s.lowAlarmLevel.coerceIn(5, 50)
        this[K.DESIGN_CAP] = s.designCapacityOverride.coerceIn(0, 30_000)
        this[K.UNIT] = s.currentUnit.name
        this[K.INVERT] = s.invertCurrent
        this[K.INTERVAL] = s.sampleIntervalSec.coerceIn(2, 300)
        this[K.RETENTION] = s.retentionDays.coerceAtLeast(0)
        this[K.BOOT] = s.startOnBoot
        this[K.DETAILED] = s.detailedNotification
        this[K.RATED] = s.ratedCycles.coerceIn(100, 3000)
    }
}
