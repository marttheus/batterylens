@file:OptIn(ExperimentalMaterial3Api::class)

package com.batterylens.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterylens.data.SessionEntity
import com.batterylens.data.durationMs
import com.batterylens.data.isCharge
import com.batterylens.data.isOpen
import com.batterylens.domain.BatteryMath
import com.batterylens.ui.MainViewModel
import com.batterylens.ui.components.ChartPoint
import com.batterylens.ui.components.LineChart
import com.batterylens.ui.components.SectionCard
import com.batterylens.ui.components.StatRow
import com.batterylens.ui.components.formatCycles
import com.batterylens.ui.components.formatDateTime
import com.batterylens.ui.components.formatDuration
import com.batterylens.ui.components.formatMaAbs
import com.batterylens.ui.components.formatMah
import com.batterylens.ui.components.formatPct
import com.batterylens.ui.components.formatPctPerHour
import com.batterylens.ui.components.formatTemp
import com.batterylens.ui.components.plugLabel
import com.batterylens.ui.theme.ChargeGreen
import com.batterylens.ui.theme.CoolBlue
import com.batterylens.ui.theme.DrainAmber
import com.batterylens.ui.theme.HotRed

private enum class Filter(val label: String) { ALL("Todas"), CHARGE("Cargas"), DISCHARGE("Descargas") }

@Composable
fun HistoryScreen(vm: MainViewModel, padding: PaddingValues, onOpen: (Long) -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val cap by vm.effectiveCapacity.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = sessions.filter {
        when (filter) {
            Filter.ALL -> true
            Filter.CHARGE -> it.isCharge
            Filter.DISCHARGE -> !it.isCharge
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Nenhuma sessão registrada ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(shown, key = { it.id }) { s -> SessionRow(s, cap) { onOpen(s.id) } }
    }
}

@Composable
private fun SessionRow(s: SessionEntity, capacity: Double, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (s.isCharge) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd, null,
                tint = if (s.isCharge) ChargeGreen else DrainAmber,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${if (s.isCharge) "Carga" else "Descarga"} ${s.startLevel}% → ${s.endLevel}%" + if (s.isOpen) " • em andamento" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatDateTime(s.startTime)} • ${formatDuration(s.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatMah(s.totalMah), style = MaterialTheme.typography.bodyMedium)
                val secondary = if (s.isCharge) "${formatCycles(s.wearCycles)} ciclos"
                else formatPctPerHour(BatteryMath.percentPerHour(BatteryMath.averageMa(s.screenOnMah + s.screenOffMah, s.screenOnMs + s.screenOffMs), capacity))
                Text(secondary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SessionDetailScreen(vm: MainViewModel, id: Long, padding: PaddingValues, onDeleted: () -> Unit) {
    val session by remember(id) { vm.session(id) }.collectAsState(initial = null)
    val samples by remember(id) { vm.samples(id) }.collectAsState(initial = emptyList())
    val usage by remember(id) { vm.appUsage(id) }.collectAsState(initial = emptyList())
    val cap by vm.effectiveCapacity.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val s = session ?: return LoadingBox(padding)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(if (s.isCharge) "Sessão de carga" else "Sessão de descarga") {
            StatRow("Início", formatDateTime(s.startTime))
            StatRow("Fim", s.endTime?.let { formatDateTime(it) } ?: "Em andamento")
            StatRow("Duração", formatDuration(s.durationMs))
            StatRow("Nível", "${s.startLevel}% → ${s.endLevel}%")
            StatRow(if (s.isCharge) "Carga adicionada" else "Carga consumida", formatMah(s.totalMah))
            StatRow("Temperatura máxima", formatTemp(s.maxTempC))
            StatRow("Pico de corrente", formatMaAbs(s.peakCurrentMa.toDouble()))
            if (s.isCharge) {
                StatRow("Fonte", plugLabel(s.plugType))
                StatRow("Velocidade média", formatMaAbs(BatteryMath.averageMa(s.totalMah, s.screenOnMs + s.screenOffMs)))
                StatRow("Desgaste", "${formatCycles(s.wearCycles)} ciclos (${formatPct(BatteryMath.wearPercentOfLife(s.wearCycles, settings.ratedCycles), 3)})")
                StatRow("Capacidade estimada", s.estimatedCapacityMah?.let { formatMah(it) } ?: "Carga curta demais")
            } else {
                StatRow("Tela ligada", "${formatDuration(s.screenOnMs)} • ${formatPctPerHour(BatteryMath.percentPerHour(BatteryMath.averageMa(s.screenOnMah, s.screenOnMs), cap))}")
                StatRow("Tela desligada", "${formatDuration(s.screenOffMs)} • ${formatPctPerHour(BatteryMath.percentPerHour(BatteryMath.averageMa(s.screenOffMah, s.screenOffMs), cap))}")
                StatRow("Sono profundo", formatPct(BatteryMath.deepSleepPercent(s.screenOffAwakeMs, s.screenOffRealMs), 0))
            }
        }
        SectionCard("Nível (%)") {
            LineChart(samples.map { ChartPoint(it.timestamp, it.level.toFloat()) }, CoolBlue, fixedMin = 0f, fixedMax = 100f)
        }
        SectionCard("Corrente (mA)") {
            LineChart(samples.mapNotNull { p -> p.currentMa?.let { ChartPoint(p.timestamp, it) } }, if (s.isCharge) ChargeGreen else DrainAmber)
        }
        SectionCard("Temperatura (°C)") {
            LineChart(samples.map { ChartPoint(it.timestamp, it.temperatureC) }, HotRed, format = { String.format(java.util.Locale.getDefault(), "%.1f", it) })
        }
        if (!s.isCharge) SectionCard("Consumo por app") { AppUsageList(usage) }
        if (!s.isOpen) {
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Excluir sessão") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir sessão?") },
            text = { Text("A sessão e suas amostras serão removidas permanentemente.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSession(id); onDeleted() }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
