package com.batterylens.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterylens.data.SessionEntity
import com.batterylens.data.durationMs
import com.batterylens.data.isCharge
import com.batterylens.domain.BatteryMath
import com.batterylens.domain.ChargeDiagnostics
import com.batterylens.domain.ChargeSpeed
import com.batterylens.ui.MainViewModel
import com.batterylens.ui.components.ArcGauge
import com.batterylens.ui.components.ChartPoint
import com.batterylens.ui.components.LineChart
import com.batterylens.ui.components.SectionCard
import com.batterylens.ui.components.StatPair
import com.batterylens.ui.components.StatRow
import com.batterylens.ui.components.formatCycles
import com.batterylens.ui.components.formatDuration
import com.batterylens.ui.components.formatMa
import com.batterylens.ui.components.formatMaAbs
import com.batterylens.ui.components.formatMah
import com.batterylens.ui.components.formatMinutes
import com.batterylens.ui.components.formatPct
import com.batterylens.ui.components.formatTemp
import com.batterylens.ui.components.formatVolts
import com.batterylens.ui.components.formatWatts
import com.batterylens.ui.components.plugLabel
import com.batterylens.ui.components.statusLabel
import com.batterylens.ui.theme.ChargeGreen
import com.batterylens.ui.theme.DrainAmber
import com.batterylens.ui.theme.HotRed

@Composable
fun LoadingBox(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ChargingScreen(vm: MainViewModel, padding: PaddingValues, onOpenChargers: () -> Unit) {
    val live by vm.live.collectAsStateWithLifecycle()
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val lastProtected by vm.lastProtectedSession.collectAsStateWithLifecycle()
    val status = live ?: return LoadingBox(padding)
    val snap = status.snapshot
    val session = status.session?.takeIf { it.isCharge }
    val lastCharge = sessions.firstOrNull { it.isCharge && it.endTime != null }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ArcGauge(
                fraction = snap.level / 100f,
                color = if (snap.pluggedIn) ChargeGreen else DrainAmber,
                centerText = "${snap.level}%",
                subText = if (snap.pluggedIn) formatMa(snap.currentMa) else "Não está carregando",
                marker = if (settings.chargeAlarmEnabled) settings.chargeTarget / 100f else null,
            )
        }

        if (snap.pluggedIn) {
            SectionCard("Velocidade de carga") {
                StatPair("Corrente", formatMa(snap.currentMa), "Potência", formatWatts(snap.powerW))
                StatRow("Fonte", plugLabel(snap.plugged))
                StatRow("Status", statusLabel(snap.status))
                StatRow("Temperatura", formatTemp(snap.temperatureC), if (snap.temperatureC >= ChargeDiagnostics.HOT_C) HotRed else androidx.compose.ui.graphics.Color.Unspecified)
                StatRow("Tensão", formatVolts(snap.voltageMv))
            }
            ChargeDiagnosisCard(snap.powerW, snap.level, snap.temperatureC, status.screenOn, snap.plugged, session?.peakPowerW, status.protectionLevel)
            if (session != null) {
                SectionCard("Carregador") {
                    ChargerPicker(vm, session)
                    OutlinedButton(onClick = onOpenChargers) { Text("Comparar carregadores") }
                }
            }
            if (status.protectionLevel == null) SectionCard("Estimativas") {
                StatRow("Até ${settings.chargeTarget}%", formatMinutes(metrics?.minutesToTarget))
                StatRow("Até 100%", formatMinutes(metrics?.minutesToFull))
            }
        }

        ChargeAlarmCard(vm, snap.level, settings.chargeAlarmEnabled, settings.chargeTarget, settings.ratedCycles, lastProtected?.protectionLevel)

        val shown = session ?: lastCharge
        if (shown != null) ChargeSessionCard(vm, shown, isCurrent = session != null, ratedCycles = settings.ratedCycles)
    }
}

@Composable
private fun ChargeDiagnosisCard(
    powerW: Float?, level: Int, tempC: Float, screenOn: Boolean, plugged: Int, peakW: Float?, protectionLevel: Int?,
) {
    val context = LocalContext.current
    val d = ChargeDiagnostics.diagnose(powerW?.let { kotlin.math.abs(it) }, level, tempC, screenOn, plugged, protectionLevel)
    SectionCard("Diagnóstico de velocidade") {
        val color = when (d.speed) {
            ChargeSpeed.SUPER_FAST, ChargeSpeed.FAST -> ChargeGreen
            ChargeSpeed.NORMAL -> DrainAmber
            else -> HotRed
        }
        if (protectionLevel != null) {
            Text(
                "Carga mantida em $protectionLevel%",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ChargeGreen,
            )
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.speed.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text("  ${formatWatts(powerW)}", style = MaterialTheme.typography.titleMedium)
        }
        peakW?.takeIf { it > 0 }?.let { StatRow("Pico nesta sessão", formatWatts(it)) }
        if (d.limiters.isEmpty()) {
            AssistChip(
                onClick = {}, label = { Text("Nada limitando a carga agora") },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.padding(2.dp), tint = ChargeGreen) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
        d.limiters.forEach { l ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, null, tint = DrainAmber, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                Column {
                    Text(l.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(l.advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "O Android não permite que apps controlem a tensão do carregador: ela é negociada pelo hardware (USB PD/PPS). " +
                "Confira se o carregamento rápido está ativado nas configurações de bateria do sistema.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openBatterySettings(context) }) { Text("Abrir configurações de bateria") }
    }
}

fun openBatterySettings(context: Context) {
    val intents = listOf(
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
private fun ChargeAlarmCard(vm: MainViewModel, level: Int, enabled: Boolean, target: Int, ratedCycles: Int, protectionSeen: Int?) {
    var slider by remember { mutableFloatStateOf(target.toFloat()) }
    LaunchedEffect(target) { slider = target.toFloat() }
    val t = slider.toInt()
    SectionCard("Alarme de carga") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Avisar ao atingir $t%", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = { on -> vm.updateSettings { it.copy(chargeAlarmEnabled = on) } })
        }
        Slider(
            value = slider, onValueChange = { slider = it }, valueRange = 50f..100f, steps = 49,
            onValueChangeFinished = { vm.updateSettings { it.copy(chargeTarget = slider.toInt()) } },
        )
        protectionSeen?.let { p ->
            Text(
                "Seu aparelho já limitou a carga em $p% sozinho (ex.: \"Proteção da bateria\" da Samsung). Esse limite nativo " +
                    "é melhor que o alarme, porque realmente para de carregar. Com ele ativo, o alarme não toca.",
                style = MaterialTheme.typography.bodySmall, color = ChargeGreen,
            )
        }
        val wearTarget = BatteryMath.wearCycles(level, t)
        val wearFull = BatteryMath.wearCycles(level, 100)
        StatRow("Desgaste de $level% até $t%", "${formatCycles(wearTarget)} ciclos")
        StatRow("Desgaste de $level% até 100%", "${formatCycles(wearFull)} ciclos")
        if (wearFull > 0) {
            Text(
                "Parar em $t% causa ${formatPct((1 - wearTarget / wearFull) * 100, 0)} menos desgaste nesta carga " +
                    "(${formatPct(BatteryMath.wearPercentOfLife(wearFull - wearTarget, ratedCycles), 3)} da vida útil).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChargeSessionCard(vm: MainViewModel, s: SessionEntity, isCurrent: Boolean, ratedCycles: Int) {
    val samples by remember(s.id) { vm.samples(s.id) }.collectAsState(initial = emptyList())
    val events by remember(s.id) { vm.chargeEvents(s.id) }.collectAsState(initial = emptyList())
    SectionCard(if (isCurrent) "Sessão atual" else "Última carga") {
        StatPair("Carregado", "${s.startLevel}% → ${s.endLevel}%", "Duração", formatDuration(s.durationMs))
        StatRow("Carga adicionada", formatMah(s.totalMah))
        StatRow("Velocidade média", formatMaAbs(BatteryMath.averageMa(s.totalMah, s.screenOnMs + s.screenOffMs)))
        StatRow("Desgaste", "${formatCycles(s.wearCycles)} ciclos (${formatPct(BatteryMath.wearPercentOfLife(s.wearCycles, ratedCycles), 3)})")
        s.estimatedCapacityMah?.let { StatRow("Capacidade estimada", formatMah(it)) }
        StatRow("Temperatura máxima", formatTemp(s.maxTempC))
        if (s.fullHoldMs > 0) StatRow("Tempo em 100% conectado", formatDuration(s.fullHoldMs), DrainAmber)
        if (s.protectedHoldMs > 0) StatRow("Tempo segurado pela proteção", formatDuration(s.protectedHoldMs))
        Text("Potência (W)", style = MaterialTheme.typography.labelMedium)
        LineChart(
            samples.mapNotNull { p -> p.currentMa?.let { ChartPoint(p.timestamp, kotlin.math.abs(it) * p.voltageMv / 1_000_000f) } },
            ChargeGreen,
            format = { String.format(java.util.Locale.getDefault(), "%.1f", it) },
            markers = events.filter { it.type == "DROP" }.map { it.timestamp },
            markerColor = DrainAmber,
        )
        Text("Quedas de velocidade", style = MaterialTheme.typography.labelMedium)
        ChargeEventList(events)
    }
}

