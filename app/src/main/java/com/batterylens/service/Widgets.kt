package com.batterylens.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.batterylens.LiveRepository
import com.batterylens.LiveStatus
import com.batterylens.R
import com.batterylens.battery.BatteryReader
import com.batterylens.domain.CurrentUnit
import com.batterylens.ui.MainActivity
import com.batterylens.ui.components.formatMa
import com.batterylens.ui.components.formatTemp
import com.batterylens.ui.components.formatWatts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private fun launchAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 1,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private data class Summary(val level: String, val primary: String, val secondary: String)

private fun summarize(status: LiveStatus): Summary {
    val s = status.snapshot
    val primary = when {
        s.pluggedIn && status.protectionLevel != null -> "Proteção ativa"
        s.pluggedIn -> "${formatMa(s.currentMa)} • ${formatWatts(s.powerW)}"
        else -> formatMa(s.currentMa)
    }
    val secondary = if (s.pluggedIn) "Carregando • ${formatTemp(s.temperatureC)}" else "Na bateria • ${formatTemp(s.temperatureC)}"
    return Summary("${s.level}%", primary, secondary)
}

/** Home-screen widget with level, live current/power and temperature. Updated by the monitor service. */
class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val status = LiveRepository.status.value
            ?: BatteryReader(context).read(CurrentUnit.AUTO, false)?.let { LiveStatus(it, null, true, null) }
            ?: return
        ids.forEach { manager.updateAppWidget(it, views(context, summarize(status))) }
        BatteryMonitorService.start(context)
    }

    companion object {
        private var lastShown: Summary? = null

        private fun views(context: Context, s: Summary) = RemoteViews(context.packageName, R.layout.widget_battery).apply {
            setTextViewText(R.id.widget_level, s.level)
            setTextViewText(R.id.widget_primary, s.primary)
            setTextViewText(R.id.widget_secondary, s.secondary)
            setOnClickPendingIntent(R.id.widget_root, launchAppIntent(context))
        }

        /** Pushes the latest status to every placed widget; skips the IPC when nothing changed. */
        fun updateAll(context: Context, status: LiveStatus) {
            val summary = summarize(status)
            if (summary == lastShown) return
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
            if (ids.isEmpty()) return
            lastShown = summary
            runCatching { manager.updateAppWidget(ids, views(context, summary)) }
        }
    }
}

/** Quick Settings tile: level plus live current, refreshed while the shade is open. */
class BatteryTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        BatteryMonitorService.start(this)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sc
        job = sc.launch { LiveRepository.status.filterNotNull().collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun render(status: LiveStatus) {
        val tile = qsTile ?: return
        val s = summarize(status)
        tile.label = s.level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = s.primary
        tile.contentDescription = "${s.level}, ${s.primary}"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_battery)
        tile.state = if (status.snapshot.pluggedIn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(launchAppIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
