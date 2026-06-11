package com.klipperremote.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klipperremote.app.CrashHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(CrashHandler.readLog(context)) }
    var showConfirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash Log", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (logText.isNotEmpty()) {
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Crash Log", logText))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Kopieren")
                        }
                        IconButton(onClick = { showConfirmDelete = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Löschen",
                                tint = Color(0xFFFF5555)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
                .padding(padding)
        ) {
            if (logText.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Kein Crash-Log vorhanden",
                        color = Color(0xFF888888),
                        fontSize = 14.sp
                    )
                }
            } else {
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(vScroll)
                        .horizontalScroll(hScroll)
                        .padding(12.dp)
                ) {
                    Text(
                        text = logText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFCCCCCC),
                        lineHeight = 16.sp,
                        softWrap = false
                    )
                }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Log löschen?") },
            text = { Text("Der Crash-Log wird unwiderruflich gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    CrashHandler.clearLog(context)
                    logText = ""
                    showConfirmDelete = false
                }) {
                    Text("Löschen", color = Color(0xFFFF5555))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}
