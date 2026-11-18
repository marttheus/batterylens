@file:OptIn(ExperimentalLayoutApi::class)

package com.batterylens.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterylens.data.ChargeEventEntity
import com.batterylens.data.ChargerEntity
import com.batterylens.data.SessionEntity
import com.batterylens.data.durationMs
import com.batterylens.domain.ChargeEventType
import com.batterylens.domain.ChargeLimiter
import com.batterylens.domain.ChargerComparison
import com.batterylens.ui.MainViewModel
import com.batterylens.ui.components.SectionCard
import com.batterylens.ui.components.StatRow
import com.batterylens.ui.components.formatMinutes
import com.batterylens.ui.components.formatTemp
import com.batterylens.ui.components.formatTime
import com.batterylens.ui.components.formatWatts
import com.batterylens.ui.theme.ChargeGreen
import com.batterylens.ui.theme.DrainAmber

/** Lets the user say which charger/cable a charge session used, with a guess from peak power. */
@Composable
fun ChargerPicker(vm: MainViewModel, session: SessionEntity) {
    val chargers by vm.chargers.collectAsStateWithLifecycle()
    val stats by vm.chargerStats.collectAsStateWithLifecycle()
    val assigned by remember(session.id) { vm.sessionCharger(session.id) }.collectAsState(initial = null)
    var adding by remember { mutableStateOf(false) }
    val guess = if (assigned == null && session.durationMs >= 3 * 60_000L) {
        ChargerComparison.guess(session.peakPowerW, stats)?.let { id -> chargers.firstOrNull { it.id == id } }
    } else null

    Text(
        if (assigned == null) "Qual carregador é este?" else "Carregador",
        style = MaterialTheme.typography.bodyMedium,
    )
    guess?.let { g ->
        AssistChip(onClick = { vm.assignCharger(session.id, g.id) }, label = { Text("Parece ser: ${g.name} — confirmar") })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chargers.forEach { c ->
            FilterChip(
                selected = assigned == c.id,
                onClick = { vm.assignCharger(session.id, if (assigned == c.id) null else c.id) },
                label = { Text(c.name) },
            )
        }
        AssistChip(onClick = { adding = true }, label = { Text("Novo") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
    }
    if (adding) {
        NameDialog("Novo carregador", "Ex.: Samsung 45W + cabo 5A", "") { name ->
            adding = false
            name?.let { vm.addCharger(it, session.id) }
        }
    }
}

@Composable
fun NameDialog(title: String, hint: String, initial: String, onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, placeholder = { Text(hint) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onResult(text) }, enabled = text.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancelar") } },
    )
}

/** Drops and recoveries of charging power, newest last. */
@Composable
fun ChargeEventList(events: List<ChargeEventEntity>) {
    if (events.isEmpty()) {
        Text(
            "Nenhuma queda de velocidade abaixo de 80% nesta carga.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    events.forEach { e ->
        val drop = e.type == ChargeEventType.DROP.name
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (drop) Icons.AutoMirrored.Filled.TrendingDown else Icons.AutoMirrored.Filled.TrendingUp, null,
                tint = if (drop) DrainAmber else ChargeGreen, modifier = Modifier.padding(end = 8.dp, top = 2.dp),
            )
            Column {
                Text(
                    "${formatTime(e.timestamp)} • ${if (drop) "caiu" else "voltou"} de ${formatWatts(e.fromW)} para ${formatWatts(e.toW)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val cause = e.cause?.let { runCatching { ChargeLimiter.valueOf(it).title }.getOrNull() }
                Text(
                    "${e.level}% • ${formatTemp(e.temperatureC)}" + (cause?.let { " • $it" } ?: if (drop) " • causa não identificada (cabo/carregador?)" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ChargersScreen(vm: MainViewModel, padding: PaddingValues) {
    val chargers by vm.chargers.collectAsStateWithLifecycle()
    val stats by vm.chargerStats.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChargerEntity?>(null) }
    var deleting by remember { mutableStateOf<ChargerEntity?>(null) }
    val best = stats.mapNotNull { it.avgPowerW }.maxOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Marque o carregador em cada carga e compare a potência média abaixo de 80% — a parte da carga que " +
                "depende do carregador e do cabo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chargers.isEmpty()) {
            SectionCard("Nenhum carregador cadastrado") {
                Text("Conecte o celular e toque em \"Novo\" no cartão do carregador, ou cadastre aqui.")
            }
        }
        chargers.forEach { c ->
            val st = stats.firstOrNull { it.chargerId == c.id }
            SectionCard(c.name) {
                if (st == null) {
                    Text("Ainda sem cargas marcadas.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        formatWatts(st.avgPowerW?.toFloat()) + " em média",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    if (best != null && st.avgPowerW != null) {
                        LinearProgressIndicator(
                            progress = { (st.avgPowerW / best).toFloat() },
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = if (st.avgPowerW >= best * 0.99) ChargeGreen else DrainAmber,
                        )
                    }
                    StatRow("Pico", formatWatts(st.bestPeakW))
                    StatRow("20% → 80% (estimado)", formatMinutes(st.minutes20to80))
                    StatRow("Cargas", "${st.sessions}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { renaming = c }) { Icon(Icons.Filled.Edit, "Renomear") }
                    IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, "Excluir") }
                }
            }
        }
        TextButton(onClick = { adding = true }) { Text("Adicionar carregador") }
    }

    if (adding) NameDialog("Novo carregador", "Ex.: Samsung 45W + cabo 5A", "") { n -> adding = false; n?.let { vm.addCharger(it, null) } }
    renaming?.let { c -> NameDialog("Renomear", "", c.name) { n -> renaming = null; n?.let { vm.renameCharger(c, it) } } }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir ${c.name}?") },
            text = { Text("As cargas continuam no histórico, apenas sem este carregador marcado.") },
            confirmButton = { TextButton(onClick = { vm.deleteCharger(c.id); deleting = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}
