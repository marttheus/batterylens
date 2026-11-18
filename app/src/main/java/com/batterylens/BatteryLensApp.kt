package com.batterylens

import android.app.Application
import com.batterylens.battery.BatterySnapshot
import com.batterylens.data.AppDatabase
import com.batterylens.data.SessionEntity
import com.batterylens.data.SettingsRepository
import com.batterylens.service.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryLensApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
    }

    companion object {
        lateinit var instance: BatteryLensApp
            private set
    }
}

/** Latest state published by the monitor service and observed by the UI (same process). */
data class LiveStatus(
    val snapshot: BatterySnapshot,
    val session: SessionEntity?,
    val screenOn: Boolean,
    val foregroundApp: String?,
)

object LiveRepository {
    private val _status = MutableStateFlow<LiveStatus?>(null)
    val status: StateFlow<LiveStatus?> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun publish(status: LiveStatus) { _status.value = status }
    fun setRunning(running: Boolean) { _running.value = running }
}
