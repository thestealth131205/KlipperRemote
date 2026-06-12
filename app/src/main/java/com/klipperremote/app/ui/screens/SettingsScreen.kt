package com.klipperremote.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.AppConfig
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.viewmodel.MainViewModel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.config
    val appConfig = uiState.appConfig
    val context = LocalContext.current

    // Verbindungs-Backup: hält den .bck-Inhalt + Dateinamen, solange der Dialog offen ist.
    var backup by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Wiederherstellen: System-Dateiauswahl für eine .bck-Datei, dann Einstellungen übernehmen.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val restored = restoreConnectionBackup(context, uri)
            if (restored != null) {
                viewModel.saveConfig(restored)
                android.widget.Toast.makeText(
                    context, "Verbindungseinstellungen wiederhergestellt", android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                android.widget.Toast.makeText(
                    context, "Ungültige oder beschädigte Backup-Datei", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    // App-Konfiguration (Parallelität + Polling-Intervalle)
    var maxConn by remember(appConfig.maxConcurrentConnections) {
        mutableStateOf(appConfig.maxConcurrentConnections.toString())
    }
    var tempInterval by remember(appConfig.tempIntervalSec) {
        mutableStateOf(appConfig.tempIntervalSec.toString())
    }
    var bgInterval by remember(appConfig.backgroundIntervalSec) {
        mutableStateOf(appConfig.backgroundIntervalSec.toString())
    }
    var powerInterval by remember(appConfig.powerIntervalSec) {
        mutableStateOf(appConfig.powerIntervalSec.toString())
    }
    var notifyInterval by remember(appConfig.notifyIntervalSec) {
        mutableStateOf(appConfig.notifyIntervalSec.toString())
    }
    var appConfigSaved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Klipper Konfiguration
            Text(
                text = "Klipper / Moonraker",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host / IP-Adresse *") },
                placeholder = { Text("192.168.1.100") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                supportingText = { Text("IP-Adresse oder Domain des Klipper-Hosts") }
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port *") },
                placeholder = { Text("7125") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                supportingText = { Text("Standard-Moonraker-Port: 7125") }
            )

            HorizontalDivider()

            // Optional: API Key
            Text(
                text = "API Key (optional)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Klipper / Moonraker API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                supportingText = { Text("Wird als X-Api-Key Header gesendet") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val portInt = port.toIntOrNull() ?: 7125
                    viewModel.saveConfig(
                        KlipperConfig(
                            host = host.trim(),
                            port = portInt,
                            username = "",
                            password = "",
                            apiKey = apiKey.trim()
                        )
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = host.isNotBlank() && port.isNotBlank()
            ) {
                Text("Speichern", fontSize = 16.sp)
            }

            if (saved) {
                Text(
                    "Einstellungen gespeichert",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }

            HorizontalDivider()

            // App Konfiguration (Verbindungen & Abfrage-Intervalle)
            Text(
                text = "App Konfiguration",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Steuert, wie viele Anfragen gleichzeitig laufen und in welchen Intervallen Daten abgerufen werden.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = maxConn,
                onValueChange = { maxConn = it.filter { c -> c.isDigit() } },
                label = { Text("Max. gleichzeitige Verbindungen") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                supportingText = { Text("Anzahl paralleler API-Anfragen (1–8)") }
            )

            OutlinedTextField(
                value = tempInterval,
                onValueChange = { tempInterval = it.filter { c -> c.isDigit() } },
                label = { Text("Temperatur-Intervall (Sek.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                supportingText = { Text("Vordergrund-Abfrage der Temperaturen (1–60)") }
            )

            OutlinedTextField(
                value = bgInterval,
                onValueChange = { bgInterval = it.filter { c -> c.isDigit() } },
                label = { Text("Status-Intervall (Sek.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                supportingText = { Text("Vordergrund-Abfrage von Status/Position/Fortschritt (1–120)") }
            )

            OutlinedTextField(
                value = powerInterval,
                onValueChange = { powerInterval = it.filter { c -> c.isDigit() } },
                label = { Text("Power-Geräte-Intervall (Sek.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                supportingText = { Text("Vordergrund-Abfrage der Steckdosen (1–300)") }
            )

            OutlinedTextField(
                value = notifyInterval,
                onValueChange = { notifyInterval = it.filter { c -> c.isDigit() } },
                label = { Text("Benachrichtigungs-Intervall (Sek.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                supportingText = { Text("Hintergrund-Abfrage für Benachrichtigungen (5–600)") }
            )

            Button(
                onClick = {
                    viewModel.saveAppConfig(
                        AppConfig(
                            maxConcurrentConnections = (maxConn.toIntOrNull() ?: 1).coerceIn(1, 8),
                            tempIntervalSec = (tempInterval.toIntOrNull() ?: 2).coerceIn(1, 60),
                            backgroundIntervalSec = (bgInterval.toIntOrNull() ?: 4).coerceIn(1, 120),
                            powerIntervalSec = (powerInterval.toIntOrNull() ?: 15).coerceIn(1, 300),
                            notifyIntervalSec = (notifyInterval.toIntOrNull() ?: 10).coerceIn(5, 600)
                        )
                    )
                    appConfigSaved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = maxConn.isNotBlank() && tempInterval.isNotBlank() &&
                    bgInterval.isNotBlank() && powerInterval.isNotBlank() && notifyInterval.isNotBlank()
            ) {
                Text("App Konfiguration speichern", fontSize = 16.sp)
            }

            if (appConfigSaved) {
                Text(
                    "App-Konfiguration gespeichert",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Backup der Verbindungseinstellungen (Host/Port/API-Key) als .bck-Datei.
            OutlinedButton(
                onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                    val content = JSONObject().apply {
                        put("type", "klipperremote-connection-backup")
                        put("version", 1)
                        put("createdAt", date)
                        put("host", config.host)
                        put("port", config.port)
                        put("apiKey", config.apiKey)
                    }.toString(2)
                    backup = "klipperremote-$date.bck" to content
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verbindungseinstellungen sichern", fontSize = 15.sp)
            }

            // Wiederherstellen aus einer zuvor gesicherten .bck-Datei.
            OutlinedButton(
                onClick = { restoreLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verbindungseinstellungen wiederherstellen", fontSize = 15.sp)
            }

            HorizontalDivider()

            // Über
            Text(
                text = "Über",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "KlipperRemote",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "von TheStealth",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    backup?.let { (fileName, content) ->
        AlertDialog(
            onDismissRequest = { backup = null },
            icon = { Icon(Icons.Default.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Verbindungs-Backup", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Möchtest du „$fileName“ lokal speichern oder teilen?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            val ok = saveBackupToDownloads(context, fileName, content)
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Unter Downloads/KlipperRemote gespeichert"
                                else "Speichern fehlgeschlagen",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            backup = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Lokal speichern", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            shareBackup(context, fileName, content)
                            backup = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Teilen…")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { backup = null }) {
                    Text("Abbrechen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

// Schreibt das Backup in den öffentlichen Downloads/KlipperRemote-Ordner.
private fun saveBackupToDownloads(
    context: android.content.Context,
    fileName: String,
    content: String
): Boolean {
    val bytes = content.toByteArray(Charsets.UTF_8)
    return runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/KlipperRemote")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val sub = java.io.File(dir, "KlipperRemote").apply { mkdirs() }
            java.io.File(sub, fileName).writeBytes(bytes)
            true
        }
    }.getOrDefault(false)
}

// Schreibt das Backup in den Cache und öffnet das Android-Share-Sheet.
private fun shareBackup(
    context: android.content.Context,
    fileName: String,
    content: String
) {
    runCatching {
        val cacheDir = java.io.File(context.cacheDir, "config_backups").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = java.io.File(cacheDir, fileName)
        out.writeBytes(content.toByteArray(Charsets.UTF_8))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, "Verbindungs-Backup teilen")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

// Liest eine ausgewählte .bck-Datei und stellt daraus die Verbindungseinstellungen
// wieder her. Gibt null zurück, wenn die Datei nicht lesbar oder kein gültiges Backup ist.
private fun restoreConnectionBackup(
    context: android.content.Context,
    uri: android.net.Uri
): KlipperConfig? = runCatching {
    val content = context.contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    } ?: return null
    val json = JSONObject(content)
    if (json.optString("type") != "klipperremote-connection-backup") return null
    val host = json.optString("host", "")
    if (host.isBlank()) return null
    KlipperConfig(
        host = host,
        port = json.optInt("port", 7125),
        username = "",
        password = "",
        apiKey = json.optString("apiKey", "")
    )
}.getOrNull()
