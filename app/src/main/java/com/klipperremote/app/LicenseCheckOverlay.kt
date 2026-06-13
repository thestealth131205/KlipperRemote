package com.klipperremote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "license_prefs"
private const val KEY_EMAIL = "verified_license_key"
private const val LICENSE_URL = "https://letheapp.de/klipper_licenses.txt"

private val NeonYellow = Color(0xFFCCFF00)
private val ErrorRed = Color(0xFFFF4444)
private val TextGray = Color(0xFFAAAAAA)

private enum class LicenseState {
    LOADING,     // Startup: gespeicherte E-Mail gegen Server prüfen
    INPUT,       // E-Mail eingeben
    CHECKING,    // Netzwerk-Check läuft
    AUTHORIZED,  // Zugang gewährt – Overlay wird nicht angezeigt
    BLOCKED,     // E-Mail nicht in Lizenzliste
    ERROR        // Netzwerkfehler beim Prüfen
}

/**
 * Wrapper-Composable: zeigt [content] nur, wenn eine lizenzierte E-Mail-Adresse
 * bestätigt wurde. Bei fehlender oder ungültiger Lizenz wird ein nicht
 * schließbares Overlay eingeblendet, das jegliche Nutzung der App blockiert.
 */
@Composable
fun LicenseCheckOverlay(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(LicenseState.LOADING) }
    var keyInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchAndCheck(key: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LICENSE_URL).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: ""
            val input = key.trim()
            body.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .any { line ->
                    val firstToken = line.split(Regex("\\s+"))[0]
                    firstToken.equals(input, ignoreCase = true)
                }
        }
    }

    // Beim Start gespeicherte E-Mail prüfen
    LaunchedEffect(Unit) {
        val saved = prefs.getString(KEY_EMAIL, null)
        if (saved == null) {
            state = LicenseState.INPUT
        } else {
            state = LicenseState.LOADING
            try {
                if (fetchAndCheck(saved)) {
                    state = LicenseState.AUTHORIZED
                } else {
                    prefs.edit().remove(KEY_EMAIL).apply()
                    errorMessage = "Dein Lizenzschlüssel ist ungültig. Bitte wende dich an den Entwickler."
                    state = LicenseState.BLOCKED
                }
            } catch (e: Exception) {
                errorMessage = "Netzwerkfehler. Bitte Internetverbindung prüfen und erneut versuchen."
                state = LicenseState.ERROR
            }
        }
    }

    // Solange noch nicht autorisiert: Back-Button deaktivieren
    BackHandler(enabled = state != LicenseState.AUTHORIZED) {}

    if (state == LicenseState.AUTHORIZED) {
        content()
        return
    }

    // Vollbild-Overlay – blockiert die gesamte App-Nutzung
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2111111))
    ) {
        when (state) {

            LicenseState.LOADING, LicenseState.CHECKING -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = NeonYellow)
                    Text(
                        text = if (state == LicenseState.LOADING) "Lizenz wird geprüft…" else "Schlüssel wird überprüft…",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }

            LicenseState.INPUT, LicenseState.ERROR -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = NeonYellow,
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        text = "KlipperRemote",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gib deinen Lizenzschlüssel ein,\num die App zu verwenden.",
                        color = TextGray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    if (state == LicenseState.ERROR) {
                        Text(
                            text = errorMessage,
                            color = ErrorRed,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it.uppercase() },
                        label = { Text("Lizenzschlüssel (z. B. KLPR-XXXX-XXXX-XXXX)", color = TextGray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonYellow,
                            unfocusedBorderColor = Color(0xFF555555),
                            cursorColor = NeonYellow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val key = keyInput.trim()
                            if (key.isEmpty()) return@Button
                            state = LicenseState.CHECKING
                            scope.launch {
                                try {
                                    if (fetchAndCheck(key)) {
                                        prefs.edit().putString(KEY_EMAIL, key.uppercase()).apply()
                                        state = LicenseState.AUTHORIZED
                                    } else {
                                        errorMessage = "Dieser Lizenzschlüssel ist nicht gültig."
                                        state = LicenseState.INPUT
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Netzwerkfehler. Bitte Internetverbindung prüfen."
                                    state = LicenseState.ERROR
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonYellow),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Schlüssel prüfen", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            LicenseState.BLOCKED -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(60.dp)
                    )
                    Text(
                        text = "Kein Zugang",
                        color = ErrorRed,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = errorMessage,
                        color = TextGray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            LicenseState.AUTHORIZED -> {
                // Wird oben bereits abgefangen – hier nie erreicht
            }
        }
    }
}
