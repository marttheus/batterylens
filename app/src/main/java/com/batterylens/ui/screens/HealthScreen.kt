package com.batterylens.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterylens.data.isCharge
import com.batterylens.domain.BatteryMath
import com.batterylens.ui.MainViewModel
import com.batterylens.ui.components.ArcGauge
import com.batterylens.ui.components.ChartPoint
import com.batterylens.ui.components.LineChart
import com.batterylens.ui.components.SectionCard
import com.batterylens.ui.components.StatPair
import com.batterylens.ui.components.StatRow
import com.batterylens.ui.components.formatCycles
import com.batterylens.ui.components.formatMah
import com.batterylens.ui.components.formatPct
import com.batterylens.ui.components.formatTemp
import com.batterylens.ui.components.formatVolts
import com.batterylens.ui.components.healthLabel
import com.batterylens.ui.theme.ChargeGreen
import com.batterylens.ui.theme.CoolBlue
import com.batterylens.ui.theme.DrainAmber
import com.batterylens.ui.theme.HotRed

@Composable
fun HealthScreen(vm: MainViewModel, padding: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val design by vm.designCapacity.collectAsStateWithLifecycle()
    val estimated by vm.estimatedCapacity.collectAsStateWithLifecycle()
    val estimates by vm.capacityEstimates.collectAsStateWithLifecycle()
    val lifetime by vm.lifetime.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val openSession = live?.session
    val totalWear = lifetime.wearCycles + (openSession?.takeIf { it.isCharge }?.wearCycles ?: 0.0)
    val totalCharged = lifetime.chargedMah + (openSession?.takeIf { it.isCharge }?.totalMah ?: 0.0)
    val healthPct = if (design != null && estimated != null) estimated!! / design!! * 100 else null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val color = when {
                healthPct == null -> CoolBlue
                healthPct >= 85 -> ChargeGreen
                healthPct >= 70 -> DrainAmber
                else -> HotRed
            }
            ArcGauge(
                fraction = ((healthPct ?: 0.0) / 100).toFloat(),
                color = color,
                centerText = formatPct(healthPct, 0),
                subText = "Saúde estimada",
            )
        }

        SectionCard("Capacidade") {
            StatPair("Estimada", formatMah(estimated), "Projetada", formatMah(design))
            StatRow("Medições válidas", "${estimates.size}")
            if (estimated == null) {
                Text(
                    "A capacidade é medida em cada carga de pelo menos ${BatteryMath.MIN_LEVEL_DELTA_FOR_CAPACITY}%. " +
                        "Quanto mais cargas longas, mais precisa fica a estimativa.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (design == null) {
                Text(
                    "O sistema não informou a capacidade projetada. Informe-a em Configurações para calcular a saúde.",
                    style = MaterialTheme.typography.bodySmall, color = HotRed,
                )
            }
            if (estimates.size >= 2) {
                Text("Histórico de capacidade (mAh)", style = MaterialTheme.typography.labelMedium)
                LineChart(
                    estimates.reversed().mapNotNull { s -> s.estimatedCapacityMah?.let { ChartPoint(s.startTime, it.toFloat()) } },
                    CoolBlue,
                )
            }
        }

        SectionCard("Desgaste") {
            StatPair(
                "Ciclos de desgaste", formatCycles(totalWear),
                "Da vida útil", formatPct(BatteryMath.wearPercentOfLife(totalWear, settings.ratedCycles), 2),
            )
            StatRow("Carga total recebida", formatMah(totalCharged))
            design?.let { StatRow("Ciclos completos equivalentes", String.format(java.util.Locale.getDefault(), "%.1f", totalCharged / it)) }
            Text(
                "O desgaste pondera cada 1% carregado pelo estresse no nível em que ocorre: carregar perto de 100% " +
                    "conta mais que carregar entre 20% e 80%. Vida útil considerada: ${settings.ratedCycles} ciclos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        live?.snapshot?.let { snap ->
            SectionCard("Informações do sistema") {
                StatRow("Saúde informada", healthLabel(snap.health))
                StatRow("Tecnologia", snap.technology ?: "—")
                StatRow("Temperatura", formatTemp(snap.temperatureC))
                StatRow("Tensão", formatVolts(snap.voltageMv))
                StatRow("Contador de carga", snap.chargeCounterUah?.let { formatMah(it / 1000.0) } ?: "Não disponível")
            }
        }

        SectionCard("Dicas") {
            listOf(
                "Mantenha a carga entre 20% e 80% sempre que possível.",
                "Evite calor: não carregue sob o sol ou com jogos pesados abertos.",
                "Carregamento rápido é seguro, mas gera mais calor; carregue lentamente quando tiver tempo (ex.: à noite).",
                "Descargas até 0% e longos períodos em 100% aceleram o envelhecimento.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
