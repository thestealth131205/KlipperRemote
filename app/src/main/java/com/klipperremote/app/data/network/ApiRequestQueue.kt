package com.klipperremote.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Serialisierte API-Warteschlange mit zwei Prioritätsstufen.
 *
 * HIGH-Anfragen (Temperatur, Nutzeraktionen) werden immer vor NORMAL-Anfragen
 * (Polling-Hintergrundaufgaben) abgearbeitet. Innerhalb einer Stufe ist die
 * Reihenfolge FIFO. Zu jedem Zeitpunkt läuft maximal eine Anfrage.
 */
class ApiRequestQueue(scope: CoroutineScope) {

    private val highChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val normalChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            while (true) {
                // Erst alle HIGH-Einträge abarbeiten, bevor ein NORMAL verarbeitet wird.
                val highItem = highChannel.tryReceive().getOrNull()
                if (highItem != null) {
                    runSafe(highItem)
                    continue
                }
                // Warten auf das nächste Element – HIGH hat Vorrang via select.
                select {
                    highChannel.onReceive { runSafe(it) }
                    normalChannel.onReceive { item ->
                        // Bevor NORMAL ausgeführt wird, HIGH nochmal prüfen.
                        val pending = highChannel.tryReceive().getOrNull()
                        if (pending != null) {
                            runSafe(pending)
                            // NORMAL zurück in die Warteschlange stellen.
                            normalChannel.trySend(item)
                        } else {
                            runSafe(item)
                        }
                    }
                }
            }
        }
    }

    private suspend fun runSafe(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Fehler werden im Block selbst behandelt; hier nicht abstürzen.
        }
    }

    /** Fügt eine Aufgabe mit hoher Priorität ein (Temperatur, Nutzerbefehle). */
    fun enqueueHigh(block: suspend () -> Unit) {
        highChannel.trySend(block)
    }

    /** Fügt eine Hintergrundaufgabe mit normaler Priorität ein. */
    fun enqueueNormal(block: suspend () -> Unit) {
        normalChannel.trySend(block)
    }
}
