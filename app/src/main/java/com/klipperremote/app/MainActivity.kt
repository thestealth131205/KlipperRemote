package com.klipperremote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.klipperremote.app.ui.screens.CrashLogScreen
import com.klipperremote.app.ui.screens.GCodeViewerScreen
import com.klipperremote.app.ui.screens.DriverSettingsScreen
import com.klipperremote.app.ui.screens.HomeScreen
import com.klipperremote.app.ui.screens.MachineConfigScreen
import com.klipperremote.app.ui.screens.PrintProgressBar
import com.klipperremote.app.ui.screens.SettingsScreen
import com.klipperremote.app.ui.theme.KlipperRemoteTheme
import com.klipperremote.app.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

                Box(modifier = Modifier.fillMaxSize()) {
                // Schwebender Fortschrittsbalken – liegt über allem außer Dialogen/Overlays
                PrintProgressBar(
                    progress = uiState.printProgress,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(10f)
                )
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (uiState.printProgress != null) 60.dp else 0.dp)
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
                            onNavigateToCrashLog = { navController.navigate("crash_log") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("crash_log") {
                        CrashLogScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("machine_config") {
                        MachineConfigScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("driver_settings") {
                        DriverSettingsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable(
                        "gcode_viewer/{filename}",
                        arguments = listOf(navArgument("filename") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val encoded = backStackEntry.arguments?.getString("filename") ?: ""
                        val filename = URLDecoder.decode(encoded, "UTF-8")
                        GCodeViewerScreen(
                            filename = filename,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
                } // Box
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

    // Datei-Zugriff erst nach der Benachrichtigungs-Abfrage anzeigen, damit sich
    // die Dialoge nicht überlagern.
    var showStoragePermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        showNotificationRationale = false
        if (needsFileAccessRequest(context)) showStoragePermission = true
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = {
                showNotificationRationale = false
                if (needsFileAccessRequest(context)) showStoragePermission = true
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
                    if (needsFileAccessRequest(context)) showStoragePermission = true
                }) {
                    Text("Ablehnen")
                }
            }
        )
    }

    // Datei-Zugriff direkt beim Start abfragen, falls die Benachrichtigung nicht
    // (mehr) abgefragt werden muss.
    LaunchedEffect(Unit) {
        if (!showNotificationRationale && needsFileAccessRequest(context)) {
            showStoragePermission = true
        }
    }

    if (showStoragePermission) {
        FileAccessGate(onDone = { showStoragePermission = false })
    }
}

/**
 * Ob der Nutzer noch nach Datei-Zugriff gefragt werden sollte. Ab Android 11 (R)
 * gilt das als erfüllt, wenn der volle Zugriff (MANAGE_EXTERNAL_STORAGE) erteilt ist;
 * darunter, wenn die Lese-Berechtigung bereits vorliegt.
 */
private fun needsFileAccessRequest(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        !android.os.Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Fragt den Datei-Zugriff ab. Der Nutzer kann zwischen vollem Zugriff
 * (MANAGE_EXTERNAL_STORAGE über die System-Einstellungen) und eingeschränktem
 * Zugriff (Lese-Berechtigung bzw. nur die System-Dateiauswahl) wählen.
 */
@androidx.compose.runtime.Composable
private fun FileAccessGate(onDone: () -> Unit) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(true) }

    val readLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        visible = false
        onDone()
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = { visible = false; onDone() },
        title = { Text("Datei-Zugriff erlauben?") },
        text = {
            Text(
                "KlipperRemote kann Konfigurationsdateien hochladen und Backups " +
                    "wiederherstellen. Erlaube vollen Zugriff auf alle Dateien oder beschränke " +
                    "den Zugriff auf einzeln ausgewählte Dateien."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                visible = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                )
                            )
                        }
                    }
                    onDone()
                } else {
                    readLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) {
                Text("Voller Zugriff")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                visible = false
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    readLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    onDone()
                }
            }) {
                Text("Eingeschränkt")
            }
        }
    )
}
