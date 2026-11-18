@file:OptIn(ExperimentalMaterial3Api::class)

package com.batterylens.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.batterylens.LiveRepository
import com.batterylens.service.BatteryMonitorService
import com.batterylens.ui.screens.ChargersScreen
import com.batterylens.ui.screens.ChargingScreen
import com.batterylens.ui.screens.DischargingScreen
import com.batterylens.ui.screens.HealthScreen
import com.batterylens.ui.screens.HistoryScreen
import com.batterylens.ui.screens.SessionDetailScreen
import com.batterylens.ui.screens.SettingsScreen
import com.batterylens.ui.theme.BatteryLensTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("charging", "Carga", Icons.Filled.BatteryChargingFull),
    Tab("discharging", "Descarga", Icons.Filled.BatteryStd),
    Tab("health", "Saúde", Icons.Filled.Favorite),
    Tab("history", "Histórico", Icons.Filled.History),
)

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BatteryMonitorService.start(this)
        setContent {
            vm = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            BatteryLensTheme(settings.theme) {
                NotificationPermissionRequest()
                AppRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.refreshPermissions()
        if (!LiveRepository.running.value) BatteryMonitorService.start(this)
    }
}

@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** After a restore the database is closed; the process must end so Room reopens the new file. */
@Composable
private fun RestoredDialog() {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("Backup restaurado") },
        text = { Text("O app será fechado. Abra-o novamente para ver os dados restaurados.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                activity?.finishAffinity()
                kotlin.system.exitProcess(0)
            }) { Text("Fechar app") }
        },
    )
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTab = tabs.any { it.route == route }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val restored by vm.restored.collectAsStateWithLifecycle()
    if (restored) RestoredDialog()
    val startRoute = remember { if (LiveRepository.status.value?.snapshot?.pluggedIn == false) "discharging" else "charging" }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            route == "settings" -> "Configurações"
                            route == "chargers" -> "Carregadores"
                            route?.startsWith("session") == true -> "Detalhes da sessão"
                            else -> "BatteryLens"
                        }
                    )
                },
                navigationIcon = {
                    if (!isTab) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                actions = {
                    if (isTab) IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Configurações") }
                },
            )
        },
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = startRoute) {
            composable("charging") { ChargingScreen(vm, padding) { nav.navigate("chargers") } }
            composable("discharging") { DischargingScreen(vm, padding) }
            composable("health") { HealthScreen(vm, padding) }
            composable("history") { HistoryScreen(vm, padding) { id -> nav.navigate("session/$id") } }
            composable("settings") { SettingsScreen(vm, padding) }
            composable("chargers") { ChargersScreen(vm, padding) }
            composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                SessionDetailScreen(vm, e.arguments?.getLong("id") ?: 0L, padding) { nav.popBackStack() }
            }
        }
    }
}
