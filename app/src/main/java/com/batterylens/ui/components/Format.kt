package com.batterylens.ui.components

import android.os.BatteryManager
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val locale: Locale get() = Locale.getDefault()

fun formatDuration(ms: Long): String {
    if (ms < 60_000) return "${(ms / 1000).coerceAtLeast(0)}s"
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}min"
        h < 24 -> "${h}h ${m}min"
        else -> "${h / 24}d ${h % 24}h"
    }
}

fun formatMinutes(min: Double?): String =
    if (min == null || min.isNaN() || min.isInfinite() || min < 0) "—"
    else formatDuration((min * 60_000).toLong())

fun formatMa(v: Float?): String = v?.let { String.format(locale, "%+d mA", it.roundToInt()) } ?: "— mA"
fun formatMaAbs(v: Double?): String = v?.let { String.format(locale, "%d mA", it.roundToInt()) } ?: "—"
fun formatMah(v: Double?): String = v?.let { String.format(locale, "%.0f mAh", it) } ?: "—"
fun formatPct(v: Double?, decimals: Int = 1): String = v?.let { String.format(locale, "%.${decimals}f%%", it) } ?: "—"
fun formatPctPerHour(v: Double?): String = v?.let { String.format(locale, "%.1f%%/h", it) } ?: "—"
fun formatCycles(v: Double): String = String.format(locale, "%.3f", v)
fun formatTemp(c: Float): String = String.format(locale, "%.1f °C", c)
fun formatVolts(mv: Int): String = String.format(locale, "%.3f V", mv / 1000f)
fun formatWatts(w: Float?): String = w?.let { String.format(locale, "%.2f W", kotlin.math.abs(it)) } ?: "—"

fun formatDateTime(t: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

fun formatTime(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

fun plugLabel(plugged: Int): String = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "Tomada (AC)"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sem fio"
    8 -> "Dock"
    0 -> "Desconectado"
    else -> "Outro"
}

fun healthLabel(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Boa"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Superaquecida"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Sem condições"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensão"
    BatteryManager.BATTERY_HEALTH_COLD -> "Fria"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Falha"
    else -> "Desconhecida"
}

fun statusLabel(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Carregando"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descarregando"
    BatteryManager.BATTERY_STATUS_FULL -> "Completa"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Conectado, sem carregar"
    else -> "Desconhecido"
}
