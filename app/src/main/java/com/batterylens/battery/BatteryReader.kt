package com.batterylens.battery

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import com.batterylens.domain.BatteryMath
import com.batterylens.domain.CurrentUnit

data class BatterySnapshot(
    val timestamp: Long,
    val level: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val technology: String?,
    val voltageMv: Int,
    val temperatureC: Float,
    /** mA, positive = charging. Null if the device does not expose current. */
    val currentMa: Float?,
    /** µAh remaining according to the fuel gauge, if exposed. */
    val chargeCounterUah: Long?,
) {
    val pluggedIn: Boolean get() = plugged != 0
    val isFull: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL
    val powerW: Float? get() = currentMa?.let { it * voltageMv / 1_000_000f }
}

class BatteryReader(private val context: Context) {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    fun read(unit: CurrentUnit, invert: Boolean): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        return fromIntent(intent, unit, invert)
    }

    fun fromIntent(intent: Intent, unit: CurrentUnit, invert: Boolean): BatterySnapshot {
        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val level = if (rawLevel < 0) 0 else (rawLevel * 100 / scale).coerceIn(0, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        var voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        if (voltage in 1..99) voltage *= 1000 // a few devices report volts
        val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val counter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return BatterySnapshot(
            timestamp = System.currentTimeMillis(),
            level = level,
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            plugged = plugged,
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            voltageMv = voltage,
            temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            currentMa = BatteryMath.normalizeCurrent(rawCurrent, unit, invert, plugged != 0),
            chargeCounterUah = counter.takeIf { it > 0 && it != Int.MAX_VALUE }?.toLong(),
        )
    }

    companion object {
        /**
         * Design capacity from the framework power profile. This is a hidden but long-stable API;
         * when it is blocked or returns a placeholder the user can enter the value in Settings.
         */
        @SuppressLint("PrivateApi")
        fun systemDesignCapacityMah(context: Context): Double? = runCatching {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val profile = cls.getConstructor(Context::class.java).newInstance(context)
            cls.getMethod("getBatteryCapacity").invoke(profile) as Double
        }.getOrNull()?.takeIf { it > 500 }
    }
}

/**
 * Tracks which app is in the foreground using UsageStats events. Requires the user to grant
 * "Usage access" in system settings; without it every query returns null.
 */
class ForegroundAppSampler(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = 0L
    private var current: String? = null

    fun currentForeground(now: Long): String? {
        if (!hasUsageAccess(context)) return null
        val from = if (lastQuery == 0L || now - lastQuery > 3_600_000) now - 3_600_000 else lastQuery
        runCatching {
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) current = e.packageName
            }
        }
        lastQuery = now
        return current
    }

    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
