package com.klipperremote.app

import android.content.Context
import android.graphics.Bitmap
import com.klipperremote.app.data.model.PrinterSnapshot

/**
 * Zentrale Auswertung des Druck-Lebenszyklus anhand der [PrinterSnapshot]-Updates.
 * Sammelt während des Drucks Min/Max-Werte (Fluss, Geschwindigkeit, Temperaturen),
 * aktualisiert die fortlaufende Druck-Benachrichtigung und meldet bei Druckende das
 * Ergebnis (erfolgreich/abgebrochen). Wird sowohl vom ViewModel (Vordergrund) als
 * auch vom [PrintMonitorService] (Hintergrund) verwendet – daher synchronisiert und
 * mit gemeinsamem Zustand.
 */
object PrintMonitor {

    /** Ergebnis eines beendeten Drucks – zur Persistenz des Datei-Symbols. */
    data class FinishedResult(val filename: String, val success: Boolean)

    private var lastPrintingActive = false
    private var trackedFilename = ""

    @Volatile private var cachedSnapshotBitmap: Bitmap? = null

    /** Aktualisiert das gecachte Webcam-Snapshot-Bild für die Benachrichtigung. */
    fun updateSnapshot(bitmap: Bitmap?) {
        cachedSnapshotBitmap = bitmap
    }

    private var minFlow = Float.MAX_VALUE
    private var maxFlow = 0f
    private var minSpeed = Float.MAX_VALUE
    private var maxSpeed = 0f
    private var minHotend = Float.MAX_VALUE
    private var maxHotend = 0f
    private var minBed = Float.MAX_VALUE
    private var maxBed = 0f
    private var minChamber = Float.MAX_VALUE
    private var maxChamber = 0f

    /**
     * Verarbeitet ein Status-Update. Gibt ein [FinishedResult] zurück, wenn der Druck
     * gerade beendet wurde (sonst null).
     */
    @Synchronized
    fun onSnapshot(context: Context, snap: PrinterSnapshot): FinishedResult? {
        val printing = snap.printerState == "printing" || snap.printerState == "paused"
        if (printing) {
            val filename = snap.printStats?.filename ?: ""
            if (filename.isNotBlank()) trackedFilename = filename
            accumulate(snap)
            val progress = snap.printStats?.progress ?: snap.printProgress ?: 0f
            PrintNotificationHelper.showPrintProgress(context, filename, progress, etaText(snap), cachedSnapshotBitmap)
            lastPrintingActive = true
            return null
        }
        if (lastPrintingActive) {
            lastPrintingActive = false
            return handleFinished(context, snap)
        }
        return null
    }

    private fun etaText(snap: PrinterSnapshot): String? {
        val s = snap.printStats ?: return null
        if (s.progress <= 0.01f) return null
        val remainingSecs = (s.printDuration / s.progress * (1f - s.progress) / s.speedFactor.coerceAtLeast(0.1f)).toLong()
        val etaMillis = System.currentTimeMillis() + remainingSecs * 1000L
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = etaMillis }
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    private fun accumulate(snap: PrinterSnapshot) {
        snap.printStats?.volumetricFlow?.let { f ->
            if (f > 0f) { minFlow = minOf(minFlow, f); maxFlow = maxOf(maxFlow, f) }
        }
        snap.printSpeedMmPerSec?.let { sp ->
            if (sp > 0f) { minSpeed = minOf(minSpeed, sp); maxSpeed = maxOf(maxSpeed, sp) }
        }
        snap.temperatures.forEach { t ->
            val n = t.name.lowercase()
            when {
                n.startsWith("extruder") -> { minHotend = minOf(minHotend, t.current); maxHotend = maxOf(maxHotend, t.current) }
                n == "heater_bed" -> { minBed = minOf(minBed, t.current); maxBed = maxOf(maxBed, t.current) }
                n.contains("chamber") -> { minChamber = minOf(minChamber, t.current); maxChamber = maxOf(maxChamber, t.current) }
            }
        }
    }

    private fun handleFinished(context: Context, snap: PrinterSnapshot): FinishedResult? {
        val raw = snap.rawState.lowercase()
        val filename = snap.printStats?.filename?.ifBlank { trackedFilename } ?: trackedFilename
        val result: FinishedResult? = when (raw) {
            "complete" -> {
                PrintNotificationHelper.showPrintComplete(context, filename, buildStatLines(snap))
                if (filename.isNotBlank()) FinishedResult(filename, true) else null
            }
            "cancelled", "error" -> {
                val msg = snap.printStats?.message?.ifBlank { null }
                    ?: if (raw == "error") "Der Druck ist mit einem Fehler abgebrochen." else "Der Druck wurde abgebrochen."
                PrintNotificationHelper.showPrintFailed(context, filename, msg)
                if (filename.isNotBlank()) FinishedResult(filename, false) else null
            }
            else -> {
                PrintNotificationHelper.clearPrintProgress(context)
                null
            }
        }
        reset()
        trackedFilename = ""
        return result
    }

    private fun buildStatLines(snap: PrinterSnapshot): List<String> {
        val lines = mutableListOf<String>()
        snap.printStats?.let {
            lines.add("Druckdauer: ${formatDuration(it.printDuration.toLong())}")
            lines.add("Material: %.2f m".format(it.filamentUsed / 1000f))
        }
        if (maxFlow > 0f) lines.add("Volumen: %.1f–%.1f mm³/s".format(orZero(minFlow), maxFlow))
        if (maxSpeed > 0f) lines.add("Geschw.: %.0f–%.0f mm/s".format(orZero(minSpeed), maxSpeed))
        if (maxHotend > 0f) lines.add("Hotend: %.0f–%.0f °C".format(orZero(minHotend), maxHotend))
        if (maxBed > 0f) lines.add("Bett: %.0f–%.0f °C".format(orZero(minBed), maxBed))
        if (maxChamber > 0f) lines.add("Kammer: %.0f–%.0f °C".format(orZero(minChamber), maxChamber))
        return lines
    }

    private fun orZero(v: Float) = if (v == Float.MAX_VALUE) 0f else v

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    private fun reset() {
        minFlow = Float.MAX_VALUE; maxFlow = 0f
        minSpeed = Float.MAX_VALUE; maxSpeed = 0f
        minHotend = Float.MAX_VALUE; maxHotend = 0f
        minBed = Float.MAX_VALUE; maxBed = 0f
        minChamber = Float.MAX_VALUE; maxChamber = 0f
    }
}
