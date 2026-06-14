package com.klipperremote.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.klipperremote.app.data.repository.KlipperRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Vordergrunddienst, der während eines laufenden Drucks aktiv bleibt und so dafür
 * sorgt, dass die Druck-Benachrichtigung auch dann zuverlässig aktualisiert wird,
 * wenn die App nicht im Vordergrund ist (kein Doze-/Hintergrund-Drosseln).
 *
 * Der Dienst pollt unabhängig vom Activity-Lebenszyklus über die [KlipperRepository]
 * den Druckerstatus und übergibt ihn an [PrintMonitor], das Benachrichtigung und
 * Statistik pflegt. Bei Druckende beendet sich der Dienst selbst.
 */
@AndroidEntryPoint
class PrintMonitorService : Service() {

    @Inject
    lateinit var repository: KlipperRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = PrintNotificationHelper.buildForegroundNotification(this)
        val id = PrintNotificationHelper.ongoingNotificationId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private suspend fun pollLoop() {
        // Intervall aus der App-Konfiguration (Benachrichtigungsabfrage), mind. 5 s.
        val intervalMs = runCatching {
            repository.appConfigFlow.first().notifyIntervalSec
        }.getOrDefault(10).coerceAtLeast(5) * 1000L

        while (scope.isActive) {
            val snap = runCatching { repository.getPrinterSnapshot() }.getOrNull()?.getOrNull()
            if (snap != null) {
                val result = PrintMonitor.onSnapshot(applicationContext, snap)
                if (result != null) {
                    runCatching { repository.savePrintResults(repository.loadPrintResults() + (result.filename to result.success)) }
                    stopSelf()
                    break
                }
                // Sicherheits-Abbruch: Wenn kein Druck mehr läuft (z. B. App-Neustart mitten
                // im Leerlauf), Dienst beenden, damit er nicht unnötig weiterläuft.
                val printing = snap.printerState == "printing" || snap.printerState == "paused"
                if (!printing) {
                    stopSelf()
                    break
                }
            }
            delay(intervalMs)
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, PrintMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrintMonitorService::class.java))
        }
    }
}
