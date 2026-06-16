package com.klipperremote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.klipperremote.app.BuildConfig
import com.klipperremote.app.ui.screens.CrashLogScreen
import com.klipperremote.app.ui.screens.GCodeViewerScreen
import com.klipperremote.app.ui.screens.DriverSettingsScreen
import com.klipperremote.app.ui.screens.HomeScreen
import com.klipperremote.app.ui.screens.MachineConfigScreen
import com.klipperremote.app.ui.screens.PrintProgressBar
import com.klipperremote.app.ui.screens.RoutineEditorScreen
import com.klipperremote.app.ui.screens.SettingsScreen
import com.klipperremote.app.ui.screens.SlicerScreen
import com.klipperremote.app.ui.theme.KlipperRemoteTheme
import com.klipperremote.app.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App ist fest im Dunkel-Theme -> Systemleisten transparent mit hellen
        // Icons erzwingen, unabhaengig vom Hell-/Dunkelmodus des Systems.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            KlipperRemoteTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsState()
                val navController = rememberNavController()

                // App-Vorder-/Hintergrund an das ViewModel melden, damit im
                // Hintergrund nur noch sparsam (Benachrichtigung) gepollt wird.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> viewModel.setAppForeground(true)
                            Lifecycle.Event.ON_STOP -> viewModel.setAppForeground(false)
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // Beim App-Start Berechtigungen prüfen und den Nutzer fragen
                StartupPermissionGate()

                val mainContent: @Composable () -> Unit = {
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                // Im Querformat den Fortschrittsbalken ausblenden, um vertikalen Platz zu sparen.
                val showProgressBar = uiState.printProgress != null && !isLandscape
                Box(modifier = Modifier.fillMaxSize()) {
                // Schwebender Fortschrittsbalken – liegt über allem außer Dialogen/Overlays
                if (showProgressBar) {
                    PrintProgressBar(
                        progress = uiState.printProgress,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .zIndex(10f)
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (showProgressBar) 60.dp else 0.dp)
                ) {
                    composable("home") {
                        HomeScreen(
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToMachine = { navController.navigate("machine_config") },
                            onNavigateToDriverSettings = { navController.navigate("driver_settings") },
                            onOpenGCodeViewer = { filename ->
                                val encoded = java.net.URLEncoder.encode(filename, "UTF-8")
                                navController.navigate("gcode_viewer/$encoded")
                            },
                            onNavigateToCrashLog = { navController.navigate("crash_log") },
                            onNavigateToSlicer = { navController.navigate("slicer") },
                            onNavigateToRoutineEditor = { routineId ->
                                val param = routineId ?: "new"
                                navController.navigate("routine_editor/$param")
                            },
                            viewModel = viewModel
                        )
                    }
                    composable(
                        "routine_editor/{routineId}",
                        arguments = listOf(navArgument("routineId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val param = backStackEntry.arguments?.getString("routineId") ?: "new"
                        RoutineEditorScreen(
                            routineId = if (param == "new") null else param,
                            onNavigateBack = { navController.popBackStack() },
                            viewModel = viewModel
                        )
                    }
                    composable("slicer") {
                        SlicerScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(onNavigateBack = { navController.popBackStack() }, viewModel = viewModel)
                    }
                    composable("crash_log") {
                        CrashLogScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("machine_config") {
                        MachineConfigScreen(onNavigateBack = { navController.popBackStack() }, viewModel = viewModel)
                    }
                    composable("driver_settings") {
                        DriverSettingsScreen(onNavigateBack = { navController.popBackStack() }, viewModel = viewModel)
                    }
                    composable(
                        "gcode_viewer/{filename}",
                        arguments = listOf(navArgument("filename") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val encoded = backStackEntry.arguments?.getString("filename") ?: ""
                        val filename = URLDecoder.decode(encoded, "UTF-8")
                        GCodeViewerScreen(
                            filename = filename,
                            onNavigateBack = { navController.popBackStack() },
                            viewModel = viewModel
                        )
                    }
                }
                } // Box
                } // mainContent lambda

                if (BuildConfig.LICENSE_CHECK_ENABLED) {
                    LicenseCheckOverlay { mainContent() }
                } else {
                    mainContent()
                }
            }
        }
    }
}

/**
 * Prüft beim App-Start die benötigten Laufzeit-Berechtigungen und fragt den Nutzer,
 * ob er sie zulassen möchte. Vor der System-Abfrage wird ein erklärender Dialog gezeigt,
 * sodass der Nutzer bewusst zulassen oder ablehnen kann. Die Abfragen laufen
 * nacheinander: zuerst Benachrichtigungen, danach der Datei-Zugriff.
 */
@androidx.compose.runtime.Composable
private fun StartupPermissionGate() {
    val context = LocalContext.current

    // POST_NOTIFICATIONS ist erst ab Android 13 (Tiramisu) eine Laufzeit-Berechtigung
    val needsNotificationPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var showNotificationRationale by remember {
        mutableStateOf(
            needsNotificationPermission &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        showNotificationRationale = false
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = {
                showNotificationRationale = false
            },
            title = { Text("Benachrichtigungen erlauben?") },
            text = {
                Text(
                    "KlipperRemote möchte dir Benachrichtigungen über den Druckstatus " +
                        "(Start, Fortschritt und Abschluss) senden. Möchtest du das zulassen?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text("Zulassen")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                }) {
                    Text("Ablehnen")
                }
            }
        )
    }
}
