package com.batterylens.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.batterylens.BatteryLensApp
import com.batterylens.LiveRepository
import com.batterylens.LiveStatus
import com.batterylens.battery.BatteryReader
import com.batterylens.battery.ForegroundAppSampler
import com.batterylens.data.AppSettings
import com.batterylens.data.isCharge
import com.batterylens.domain.BatteryMath
import com.batterylens.domain.LiveMetricsCalculator
import com.batterylens.domain.SessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that samples the battery, feeds the [SessionTracker], raises alarms and keeps
 * the live notification up to date. It deliberately holds no wake lock: while the CPU deep-sleeps
 * the loop pauses, and the charge counter covers the gap on the next tick.
 */
class BatteryMonitorService : Service() {

    private val app get() = application as BatteryLensApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private lateinit var reader: BatteryReader
    private lateinit var sampler: ForegroundAppSampler
    private lateinit var tracker: SessionTracker
    private lateinit var alarms: AlarmChecker
    private lateinit var power: PowerManager

    @Volatile private var settings = AppSettings()
    @Volatile private var capacityMah = LiveMetricsCalculator.DEFAULT_CAPACITY_MAH
    private var lastLevel = -1
    private var lastPlugged = -1
    private var lastCapacityRefresh = 0L
    private var lastCleanup = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                if (level == lastLevel && plugged == lastPlugged) return
                lastLevel = level
                lastPlugged = plugged
            }
            scope.launch(worker) { tick() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        sampler = ForegroundAppSampler(this)
        alarms = AlarmChecker(this)
        power = getSystemService(PowerManager::class.java)
        tracker = SessionTracker(app.database.dao()) { closed ->
            app.settings.addLifetime(closed.wearCycles, if (closed.isCharge) closed.totalMah else 0.0)
            lastCapacityRefresh = 0L // a finished charge may carry a new capacity estimate
        }

        ServiceCompat.startForeground(
            this, Notifications.ID_MONITOR,
            Notifications.monitor(this, null, null, settings),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        LiveRepository.setRunning(true)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            settings = app.settings.settings.first()
            launch { app.settings.settings.collect { settings = it } }
            while (isActive) {
                withContext(worker) { tick() }
                delay(settings.sampleIntervalSec * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun tick() {
        val s = settings
        val snap = reader.read(s.currentUnit, s.invertCurrent) ?: return
        val screenOn = power.isInteractive
        val fg = if (screenOn) sampler.currentForeground(snap.timestamp) else null

        val session = runCatching {
            tracker.onTick(snap, screenOn, fg, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
        }.getOrNull()

        refreshCapacityIfDue(snap.timestamp, s)
        cleanupIfDue(snap.timestamp, s)

        val status = LiveStatus(snap, session, screenOn, fg)
        LiveRepository.publish(status)
        alarms.check(status, s)

        val metrics = LiveMetricsCalculator.compute(snap, session, capacityMah, s.chargeTarget, screenOn)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_MONITOR, Notifications.monitor(this, status, metrics, s))
    }

    private suspend fun refreshCapacityIfDue(now: Long, s: AppSettings) {
        if (now - lastCapacityRefresh < 10 * 60_000L) return
        lastCapacityRefresh = now
        val estimates = app.database.dao().capacityEstimates(30)
            .map { (it.estimatedCapacityMah ?: 0.0) to (it.endLevel - it.startLevel) }
        val design = s.designCapacityOverride.takeIf { it > 0 }?.toDouble()
            ?: BatteryReader.systemDesignCapacityMah(this)
        capacityMah = LiveMetricsCalculator.effectiveCapacity(BatteryMath.weightedCapacity(estimates), design)
    }

    private suspend fun cleanupIfDue(now: Long, s: AppSettings) {
        if (s.retentionDays <= 0 || now - lastCleanup < 3_600_000L) return
        lastCleanup = now
        app.database.dao().deleteClosedBefore(now - s.retentionDays * 86_400_000L)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        LiveRepository.setRunning(false)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BatteryMonitorService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as BatteryLensApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.settings.settings.first().startOnBoot) BatteryMonitorService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
