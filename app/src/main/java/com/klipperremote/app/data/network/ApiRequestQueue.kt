package com.klipperremote.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicInteger

/**
 * Priorisierte API-Warteschlange mit konfigurierbarer Parallelität.
 *
 * HIGH-Anfragen (Temperatur, Nutzeraktionen) werden immer vor NORMAL-Anfragen
 * (Polling-Hintergrundaufgaben) abgearbeitet. Innerhalb einer Stufe ist die
 * Reihenfolge FIFO. Wie viele Anfragen gleichzeitig laufen dürfen, liefert
 * [maxConcurrent] (zur Laufzeit über die App-Konfiguration änderbar).
 */
class ApiRequestQueue(
    private val scope: CoroutineScope,
    private val maxConcurrent: () -> Int = { 1 }
) {

    private val highChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val normalChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Aktuell laufende Anfragen – begrenzt durch [maxConcurrent]. */
    private val active = AtomicInteger(0)

    init {
        scope.launch {
            while (true) {
                // Auf freie Kapazität warten, bevor die nächste Aufgabe entnommen wird.
                while (active.get() >= maxConcurrent().coerceAtLeast(1)) {
                    delay(20L)
                }
                val item = receiveNext()
                active.incrementAndGet()
                scope.launch {
                    try {
                        runSafe(item)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }
    }

    /** Liefert die nächste Aufgabe – HIGH hat stets Vorrang vor NORMAL. */
    private suspend fun receiveNext(): suspend () -> Unit {
        highChannel.tryReceive().getOrNull()?.let { return it }
        return select {
            highChannel.onReceive { it }
            normalChannel.onReceive { item ->
                // Bevor NORMAL läuft, HIGH nochmal prüfen.
                val pending = highChannel.tryReceive().getOrNull()
                if (pending != null) {
                    normalChannel.trySend(item)
                    pending
                } else {
                    item
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
