package com.batterylens.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batterylens.BatteryLensApp
import com.batterylens.LiveRepository
import com.batterylens.LiveStatus
import com.batterylens.battery.BatteryReader
import com.batterylens.battery.ForegroundAppSampler
import com.batterylens.data.AppSettings
import com.batterylens.data.AppUsageEntity
import com.batterylens.data.DrainTotals
import com.batterylens.data.LifetimeStats
import com.batterylens.data.SampleEntity
import com.batterylens.data.SessionEntity
import com.batterylens.domain.BatteryMath
import com.batterylens.domain.LiveMetrics
import com.batterylens.domain.LiveMetricsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BatteryLensApp
    private val dao = app.database.dao()
    private val systemDesign: Double? = BatteryReader.systemDesignCapacityMah(application)

    val settings: StateFlow<AppSettings> =
        app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val live: StateFlow<LiveStatus?> = LiveRepository.status

    val sessions: StateFlow<List<SessionEntity>> =
        dao.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val capacityEstimates: StateFlow<List<SessionEntity>> =
        dao.observeCapacityEstimates(50).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val lifetime: StateFlow<LifetimeStats> =
        app.settings.lifetime.stateIn(viewModelScope, SharingStarted.Eagerly, LifetimeStats())

    val designCapacity: StateFlow<Double?> = settings
        .map { s -> s.designCapacityOverride.takeIf { it > 0 }?.toDouble() ?: systemDesign }
        .stateIn(viewModelScope, SharingStarted.Eagerly, systemDesign)

    val systemDesignCapacity: Double? get() = systemDesign

    val estimatedCapacity: StateFlow<Double?> = capacityEstimates
        .map { list -> BatteryMath.weightedCapacity(list.map { (it.estimatedCapacityMah ?: 0.0) to (it.endLevel - it.startLevel) }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val effectiveCapacity: StateFlow<Double> = combine(estimatedCapacity, designCapacity) { e, d ->
        LiveMetricsCalculator.effectiveCapacity(e, d)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveMetricsCalculator.DEFAULT_CAPACITY_MAH)

    val metrics: StateFlow<LiveMetrics?> = combine(live, effectiveCapacity, settings) { l, cap, s ->
        l?.let { LiveMetricsCalculator.compute(it.snapshot, it.session, cap, s.chargeTarget, it.screenOn) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val weekDrain: StateFlow<DrainTotals?> = dao
        .observeDrainTotalsSince(System.currentTimeMillis() - 7 * 86_400_000L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _usageAccess = MutableStateFlow(ForegroundAppSampler.hasUsageAccess(application))
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Show something immediately, even before the service's first tick lands.
        viewModelScope.launch(Dispatchers.IO) {
            if (LiveRepository.status.value == null) {
                val s = app.settings.settings.first()
                BatteryReader(app).read(s.currentUnit, s.invertCurrent)?.let {
                    if (LiveRepository.status.value == null) LiveRepository.publish(LiveStatus(it, null, true, null))
                }
            }
        }
    }

    fun refreshPermissions() {
        _usageAccess.value = ForegroundAppSampler.hasUsageAccess(app)
    }

    fun consumeMessage() { _message.value = null }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { app.settings.update(transform) }
    }

    fun session(id: Long): Flow<SessionEntity?> = dao.observeSession(id)
    fun samples(id: Long): Flow<List<SampleEntity>> = dao.observeSamples(id)
    fun appUsage(id: Long): Flow<List<AppUsageEntity>> = dao.observeAppUsage(id)

    fun deleteSession(id: Long) {
        viewModelScope.launch { dao.deleteSession(id) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.deleteAllClosed()
            app.settings.resetLifetime()
            _message.value = "Histórico apagado"
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val rows = dao.allSessions()
                    app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                        w.appendLine(
                            "id,tipo,inicio,fim,nivel_inicial,nivel_final,mah,tela_ligada_ms,tela_desligada_ms," +
                                "mah_tela_ligada,mah_tela_desligada,sono_profundo_pct,desgaste_ciclos,capacidade_estimada_mah,temp_max_c,pico_ma"
                        )
                        rows.forEach { s ->
                            val deep = BatteryMath.deepSleepPercent(s.screenOffAwakeMs, s.screenOffRealMs)
                            w.appendLine(
                                listOf(
                                    s.id, s.type, s.startTime, s.endTime ?: "", s.startLevel, s.endLevel,
                                    "%.1f".format(Locale.US, s.totalMah), s.screenOnMs, s.screenOffMs,
                                    "%.1f".format(Locale.US, s.screenOnMah), "%.1f".format(Locale.US, s.screenOffMah),
                                    deep?.let { "%.1f".format(Locale.US, it) } ?: "",
                                    "%.4f".format(Locale.US, s.wearCycles),
                                    s.estimatedCapacityMah?.let { "%.0f".format(Locale.US, it) } ?: "",
                                    "%.1f".format(Locale.US, s.maxTempC), "%.0f".format(Locale.US, s.peakCurrentMa),
                                ).joinToString(",")
                            )
                        }
                    } != null
                }.getOrDefault(false)
            }
            _message.value = if (ok) "Histórico exportado" else "Falha ao exportar"
        }
    }
}
