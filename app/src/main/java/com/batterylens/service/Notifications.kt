package com.batterylens.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.batterylens.LiveStatus
import com.batterylens.R
import com.batterylens.data.AppSettings
import com.batterylens.data.SessionEntity
import com.batterylens.domain.LiveMetrics
import com.batterylens.ui.MainActivity
import com.batterylens.ui.components.formatMa
import com.batterylens.ui.components.formatMinutes
import java.util.Locale

object Notifications {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALARM = "alarm"
    const val ID_MONITOR = 1
    const val ID_CHARGE_ALARM = 10
    const val ID_TEMP_ALARM = 11
    const val ID_LOW_ALARM = 12

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val monitor = NotificationChannel(
            CHANNEL_MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
        }
        val alarm = NotificationChannel(
            CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        nm.createNotificationChannels(listOf(monitor, alarm))
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitor(context: Context, status: LiveStatus?, metrics: LiveMetrics?, settings: AppSettings): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(context))
        if (status == null || metrics == null) {
            return b.setContentTitle("Monitorando a bateria…").build()
        }
        val snap = status.snapshot
        val l = Locale.getDefault()
        val title: String
        val text: String
        if (snap.pluggedIn) {
            title = "${snap.level}% • Carregando ${formatMa(snap.currentMa)}"
            text = if (snap.level < settings.chargeTarget) {
                "Até ${settings.chargeTarget}%: ${formatMinutes(metrics.minutesToTarget)} • Até 100%: ${formatMinutes(metrics.minutesToFull)}"
            } else {
                "Até 100%: ${formatMinutes(metrics.minutesToFull)}"
            }
        } else {
            title = "${snap.level}% • ${formatMa(snap.currentMa)}"
            text = "Restante: ${formatMinutes(metrics.remainingCombinedMin)}" +
                (metrics.screenOnPctPerHour?.let { String.format(l, " • Tela ligada %.1f%%/h", it) } ?: "")
        }
        b.setContentTitle(title).setContentText(text)
        if (settings.detailedNotification) {
            val details = buildString {
                appendLine(text)
                append(String.format(l, "%.1f °C • %.2f V", snap.temperatureC, snap.voltageMv / 1000f))
                metrics.powerW?.let { append(String.format(l, " • %.2f W", kotlin.math.abs(it))) }
                status.session?.let {
                    appendLine()
                    if (it.type == SessionEntity.TYPE_CHARGE) {
                        append(String.format(l, "Sessão: +%.0f mAh • desgaste %.3f ciclos", it.totalMah, it.wearCycles))
                    } else {
                        append(String.format(l, "Sessão: -%.0f mAh", it.totalMah))
                        metrics.deepSleepPct?.let { d -> append(String.format(l, " • sono profundo %.0f%%", d)) }
                    }
                }
            }
            b.setStyle(NotificationCompat.BigTextStyle().bigText(details))
        }
        return b.build()
    }

    fun alarm(context: Context, id: Int, title: String, text: String, insistent: Boolean) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        if (insistent) n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }
}

/** Decides when to raise the charge, temperature and low-battery alarms. */
class AlarmChecker(private val context: Context) {
    private var chargeAlarmSession = -1L
    private var lowAlarmSession = -1L
    private var lastTempAlarm = 0L
    private var wasPlugged: Boolean? = null

    fun check(status: LiveStatus, settings: AppSettings) {
        val snap = status.snapshot
        val session = status.session ?: return

        // Unplugging silences a ringing charge alarm.
        if (wasPlugged == true && !snap.pluggedIn) Notifications.cancel(context, Notifications.ID_CHARGE_ALARM)
        wasPlugged = snap.pluggedIn

        if (settings.chargeAlarmEnabled && snap.pluggedIn && snap.level >= settings.chargeTarget &&
            session.startLevel < settings.chargeTarget && chargeAlarmSession != session.id
        ) {
            chargeAlarmSession = session.id
            Notifications.alarm(
                context, Notifications.ID_CHARGE_ALARM,
                "Bateria em ${snap.level}%",
                "Meta de ${settings.chargeTarget}% atingida. Desconecte o carregador para reduzir o desgaste.",
                settings.insistentAlarm,
            )
        }

        if (settings.tempAlarmEnabled && snap.temperatureC >= settings.tempAlarmC &&
            snap.timestamp - lastTempAlarm > 30 * 60_000L
        ) {
            lastTempAlarm = snap.timestamp
            Notifications.alarm(
                context, Notifications.ID_TEMP_ALARM,
                String.format(Locale.getDefault(), "Bateria quente: %.1f °C", snap.temperatureC),
                "Temperaturas altas aceleram o desgaste. Evite usar o aparelho carregando ou sob o sol.",
                false,
            )
        }

        if (settings.lowAlarmEnabled && !snap.pluggedIn && snap.level <= settings.lowAlarmLevel &&
            session.startLevel > settings.lowAlarmLevel && lowAlarmSession != session.id
        ) {
            lowAlarmSession = session.id
            Notifications.alarm(
                context, Notifications.ID_LOW_ALARM,
                "Bateria baixa: ${snap.level}%",
                "Descargas profundas desgastam a bateria. Conecte o carregador.",
                false,
            )
        }
    }
}
