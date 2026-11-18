package com.batterylens.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterylens.data.AppSettings
import com.batterylens.data.ThemeMode
import com.batterylens.domain.CurrentUnit
import com.batterylens.service.Notifications
import com.batterylens.ui.MainViewModel
import com.batterylens.ui.components.SectionCard
import com.batterylens.ui.components.formatMah

@Composable
fun SettingsScreen(vm: MainViewModel, padding: PaddingValues) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::exportBackup)
    }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }
    fun update(t: (AppSettings) -> AppSettings) = vm.updateSettings(t)
    fun open(intent: Intent) { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Tema") {
            listOf(
                ThemeMode.SYSTEM to "Seguir o sistema", ThemeMode.LIGHT to "Claro",
                ThemeMode.DARK to "Escuro", ThemeMode.AMOLED to "Preto (AMOLED)",
            ).forEach { (mode, label) ->
                RadioRow(label, s.theme == mode) { update { it.copy(theme = mode) } }
            }
        }

        SectionCard("Alarmes") {
            SwitchRow("Alarme de carga (${s.chargeTarget}%)", s.chargeAlarmEnabled) { v -> update { it.copy(chargeAlarmEnabled = v) } }
            SwitchRow("Repetir som até dispensar", s.insistentAlarm) { v -> update { it.copy(insistentAlarm = v) } }
            SwitchRow("Alarme de temperatura (${s.tempAlarmC} °C)", s.tempAlarmEnabled) { v -> update { it.copy(tempAlarmEnabled = v) } }
            IntSlider(s.tempAlarmC, 35..55) { v -> update { it.copy(tempAlarmC = v) } }
            SwitchRow("Alarme de bateria baixa (${s.lowAlarmLevel}%)", s.lowAlarmEnabled) { v -> update { it.copy(lowAlarmEnabled = v) } }
            IntSlider(s.lowAlarmLevel, 5..50) { v -> update { it.copy(lowAlarmLevel = v) } }
            SwitchRow("Alerta de consumo anormal (tela desligada)", s.drainAlertEnabled) { v -> update { it.copy(drainAlertEnabled = v) } }
            Text("Avisar acima de ${s.drainAlertPctPerHour}%/h", style = MaterialTheme.typography.bodySmall)
            IntSlider(s.drainAlertPctPerHour, 1..10) { v -> update { it.copy(drainAlertPctPerHour = v) } }
            Text("…ou com sono profundo abaixo de ${s.drainAlertMinDeepSleep}%", style = MaterialTheme.typography.bodySmall)
            IntSlider(s.drainAlertMinDeepSleep / 5, 0..18) { v -> update { it.copy(drainAlertMinDeepSleep = v * 5) } }
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_ALARM))
            }) { Text("Som e vibração do alarme") }
        }

        SectionCard("Medição") {
            var capText by remember(s.designCapacityOverride) {
                mutableStateOf(if (s.designCapacityOverride > 0) s.designCapacityOverride.toString() else "")
            }
            OutlinedTextField(
                value = capText,
                onValueChange = { txt ->
                    capText = txt.filter(Char::isDigit).take(5)
                    update { it.copy(designCapacityOverride = capText.toIntOrNull() ?: 0) }
                },
                label = { Text("Capacidade projetada (mAh)") },
                placeholder = { Text("Sistema: ${formatMah(vm.systemDesignCapacity)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Unidade da corrente", style = MaterialTheme.typography.bodyMedium)
            listOf(
                CurrentUnit.AUTO to "Automática", CurrentUnit.MICROAMPS to "Microampères (µA)", CurrentUnit.MILLIAMPS to "Miliampères (mA)",
            ).forEach { (unit, label) -> RadioRow(label, s.currentUnit == unit) { update { it.copy(currentUnit = unit) } } }
            if (s.currentUnit != CurrentUnit.AUTO) {
                SwitchRow("Inverter sinal da corrente", s.invertCurrent) { v -> update { it.copy(invertCurrent = v) } }
            }
            Text("Intervalo de amostragem: ${s.sampleIntervalSec}s", style = MaterialTheme.typography.bodyMedium)
            IntSlider(s.sampleIntervalSec, 5..60, showValue = false) { v -> update { it.copy(sampleIntervalSec = v) } }
            Text("Vida útil nominal: ${s.ratedCycles} ciclos", style = MaterialTheme.typography.bodyMedium)
            IntSlider(s.ratedCycles / 100, 3..20, showValue = false) { v -> update { it.copy(ratedCycles = v * 100) } }
        }

        SectionCard("Histórico") {
            Text("Manter sessões por", style = MaterialTheme.typography.bodyMedium)
            listOf(7 to "7 dias", 30 to "30 dias", 90 to "90 dias", 0 to "Sempre").forEach { (days, label) ->
                RadioRow(label, s.retentionDays == days) { update { it.copy(retentionDays = days) } }
            }
            OutlinedButton(onClick = { exporter.launch("batterylens-historico.csv") }) { Text("Exportar CSV") }
            OutlinedButton(onClick = { backupSaver.launch("batterylens-backup.zip") }) { Text("Fazer backup") }
            OutlinedButton(onClick = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restaurar backup") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Apagar histórico e estatísticas") }
        }

        SectionCard("Sistema") {
            SwitchRow("Iniciar ao ligar o aparelho", s.startOnBoot) { v -> update { it.copy(startOnBoot = v) } }
            SwitchRow("Notificação detalhada", s.detailedNotification) { v -> update { it.copy(detailedNotification = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Acesso aos dados de uso: ${if (usageAccess) "concedido" else "não concedido"}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Acesso aos dados de uso") }
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("Otimização de bateria")
            }
            Text(
                "Para medições contínuas, deixe o BatteryLens sem restrição de bateria. O app não mantém o processador acordado.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "BatteryLens ${com.batterylens.BuildConfig.VERSION_NAME} • todos os recursos liberados, sem anúncios.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restaurar backup?") },
            text = { Text("O histórico e as configurações atuais serão substituídos pelos do backup. O app será fechado em seguida.") },
            confirmButton = { TextButton(onClick = { pendingRestore = null; vm.restoreBackup(uri) }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancelar") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Apagar histórico?") },
            text = { Text("Todas as sessões encerradas, medições de capacidade e o desgaste acumulado serão apagados. Não é possível desfazer.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IntSlider(value: Int, range: IntRange, showValue: Boolean = false, onDone: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { v = value.toFloat() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = v, onValueChange = { v = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            onValueChangeFinished = { onDone(v.toInt()) },
            modifier = Modifier.weight(1f),
        )
        if (showValue) Text(v.toInt().toString())
    }
}
