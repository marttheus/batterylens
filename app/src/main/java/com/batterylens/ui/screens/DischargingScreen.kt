package com.batterylens.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterylens.data.AppUsageEntity
import com.batterylens.data.durationMs
import com.batterylens.data.isCharge
import com.batterylens.domain.BatteryMath
import com.batterylens.ui.MainViewModel
import com.batterylens.ui.components.AppIconLabel
import com.batterylens.ui.components.SectionCard
import com.batterylens.ui.components.StatPair
import com.batterylens.ui.components.StatRow
import com.batterylens.ui.components.formatDuration
import com.batterylens.ui.components.formatMa
import com.batterylens.ui.components.formatMaAbs
import com.batterylens.ui.components.formatMah
import com.batterylens.ui.components.formatMinutes
import com.batterylens.ui.components.formatPct
import com.batterylens.ui.components.formatPctPerHour

@Composable
fun DischargingScreen(vm: MainViewModel, padding: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val cap by vm.effectiveCapacity.collectAsStateWithLifecycle()
    val week by vm.weekDrain.collectAsStateWithLifecycle()
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val status = live ?: return LoadingBox(padding)
    val snap = status.snapshot
    val session = status.session?.takeIf { !it.isCharge }
    val m = metrics

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Agora") {
            StatPair("Nível", "${snap.level}%", "Corrente", formatMa(snap.currentMa))
            StatRow("Tela", if (status.screenOn) "Ligada" else "Desligada")
            if (snap.pluggedIn) {
                Text(
                    "Conectado ao carregador. As estatísticas abaixo são da última descarga e recomeçam ao desconectar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (session != null && m != null) {
            SectionCard("Sessão atual • ${session.startLevel}% → ${session.endLevel}%") {
                StatPair("Tela ligada", formatPctPerHour(m.screenOnPctPerHour), "Tela desligada", formatPctPerHour(m.screenOffPctPerHour))
                StatRow("Média tela ligada", formatMaAbs(m.screenOnAvgMa))
                StatRow("Média tela desligada", formatMaAbs(m.screenOffAvgMa))
                StatRow("Tempo tela ligada", formatDuration(session.screenOnMs))
                StatRow("Tempo tela desligada", formatDuration(session.screenOffMs))
                StatRow("Consumo tela ligada", "${formatMah(session.screenOnMah)} • ${session.screenOnLevelDrop}%")
                StatRow("Consumo tela desligada", "${formatMah(session.screenOffMah)} • ${session.screenOffLevelDrop}%")
                StatRow("Duração", formatDuration(session.durationMs))
            }
            SectionCard("Sono profundo") {
                val deep = m.deepSleepPct
                Text(formatPct(deep, 0), style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { ((deep ?: 0.0) / 100).toFloat() }, Modifier.padding(vertical = 4.dp))
                Text(
                    "Parcela do tempo com tela desligada em que o processador dormiu. Valores baixos indicam apps " +
                        "mantendo o aparelho acordado em segundo plano.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (m != null) {
            SectionCard("Tempo restante estimado") {
                StatRow("Só com tela ligada", formatMinutes(m.remainingScreenOnMin))
                StatRow("Só com tela desligada", formatMinutes(m.remainingScreenOffMin))
                StatRow("Uso combinado", formatMinutes(m.remainingCombinedMin))
                Text("Baseado em ${formatMah(cap)} de capacidade.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        week?.let { w ->
            val on = BatteryMath.percentPerHour(BatteryMath.averageMa(w.screenOnMah, w.screenOnMs), cap)
            val off = BatteryMath.percentPerHour(BatteryMath.averageMa(w.screenOffMah, w.screenOffMs), cap)
            SectionCard("Média dos últimos 7 dias") {
                StatPair("Tela ligada", formatPctPerHour(on), "Tela desligada", formatPctPerHour(off))
                StatRow("Tela ligada no total", formatDuration(w.screenOnMs))
            }
        }

        if (session != null) AppUsageCard(vm, session.id, usageAccess)
    }
}

@Composable
fun AppUsageCard(vm: MainViewModel, sessionId: Long, usageAccess: Boolean) {
    val context = LocalContext.current
    SectionCard("Consumo por app (tela ligada)") {
        if (!usageAccess) {
            Text(
                "Para estimar o consumo de cada app, conceda acesso aos dados de uso.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Conceder acesso") }
            return@SectionCard
        }
        val usage by remember(sessionId) { vm.appUsage(sessionId) }.collectAsState(initial = emptyList())
        AppUsageList(usage)
    }
}

@Composable
fun AppUsageList(usage: List<AppUsageEntity>) {
    if (usage.isEmpty()) {
        Text("Nenhum dado ainda.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val total = usage.sumOf { it.mah }.coerceAtLeast(1e-6)
    usage.take(15).forEach { u ->
        AppIconLabel(u.packageName) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text("${formatMah(u.mah)} • ${formatPct(u.mah / total * 100, 0)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${formatDuration(u.foregroundMs)} • ${formatMaAbs(BatteryMath.averageMa(u.mah, u.foregroundMs, 1))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Estimativa: a corrente medida é atribuída ao app em primeiro plano. Inclui o consumo da tela.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

