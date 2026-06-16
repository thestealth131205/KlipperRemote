package com.klipperremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Zeigt eine fortlaufende Druck-Benachrichtigung an, sobald die App erkennt,
 * dass ein Druck gestartet wurde. Design angelehnt an die OctoApp-Benachrichtigung,
 * jedoch in der App-Akzentfarbe (Orange) statt Rot.
 */
object PrintNotificationHelper {

    private const val CHANNEL_ID = "print_status_v2"
    private const val NOTIFICATION_ID = 4711
    private const val RESULT_NOTIFICATION_ID = 4712
    private const val ACCENT_COLOR = 0xFFFFFF00.toInt() // Neon-Gelb (AccentYellow)
    private const val SUCCESS_COLOR = 0xFF4CAF50.toInt() // Grün
    private const val ERROR_COLOR = 0xFFE53935.toInt()   // Rot

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Druckstatus",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Benachrichtigungen über laufende Drucke"
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Aktualisiert/zeigt die laufende Druck-Benachrichtigung.
     * @param filename Dateiname des Drucks
     * @param progress Fortschritt 0.0–1.0
     * @param etaText  Voraussichtliche Endzeit, z. B. "03:47" (oder null)
     */
    fun showPrintProgress(
        context: Context,
        filename: String,
        progress: Float,
        etaText: String?,
        largeIconBitmap: Bitmap? = null
    ) {
        if (!hasPermission(context)) return
        ensureChannel(context)

        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        val name = filename.substringAfterLast('/').ifBlank { "Druck" }
        val etaPart = etaText?.let { ", ETA: $it" } ?: ""
        val title = "Drucken $percent%$etaPart"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIconBitmap)
            .setContentTitle(title)
            .setContentText(name)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$name\n$title")
                .setSummaryText("KlipperRemote"))
            .setColor(ACCENT_COLOR)
            .setColorized(true)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .extend(NotificationCompat.WearableExtender()
                .setHintShowBackgroundOnly(false)
                .addPage(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(name)
                        .build()
                )
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** ID der laufenden Druck-Benachrichtigung (für den Vordergrunddienst). */
    val ongoingNotificationId: Int get() = NOTIFICATION_ID

    /**
     * Baut eine minimale laufende Benachrichtigung für den Start des Vordergrunddienstes
     * (startForeground benötigt sofort eine Notification). Wird anschließend durch
     * [showPrintProgress] mit echten Werten aktualisiert.
     */
    fun buildForegroundNotification(context: Context): Notification {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Druck wird überwacht")
            .setColor(ACCENT_COLOR)
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .extend(NotificationCompat.WearableExtender()
                .setHintShowBackgroundOnly(false)
            )
            .build()
    }

    /** Druck beendet → laufende Benachrichtigung entfernen. */
    fun clearPrintProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Erfolgreicher Druck: ersetzt die laufende Benachrichtigung durch eine
     * Druckstatistik (Min/Max-Werte, Druckdauer, Materialverbrauch).
     */
    fun showPrintComplete(
        context: Context,
        filename: String,
        statsLines: List<String>
    ) {
        if (!hasPermission(context)) return
        ensureChannel(context)
        clearPrintProgress(context)

        val name = filename.substringAfterLast('/').ifBlank { "Druck" }
        val body = statsLines.joinToString("\n")

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Druck abgeschlossen: $name")
            .setContentText(statsLines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body).setSummaryText(name))
            .setColor(SUCCESS_COLOR)
            .setColorized(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .extend(NotificationCompat.WearableExtender())
            .build()

        NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
    }

    /**
     * Abgebrochener/fehlgeschlagener Druck: entfernt die laufende Benachrichtigung
     * und zeigt eine extra Benachrichtigung mit dem angehängten Fehler.
     */
    fun showPrintFailed(
        context: Context,
        filename: String,
        errorMessage: String
    ) {
        if (!hasPermission(context)) return
        ensureChannel(context)
        clearPrintProgress(context)

        val name = filename.substringAfterLast('/').ifBlank { "Druck" }
        val error = errorMessage.ifBlank { "Der Druck wurde abgebrochen." }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Druck abgebrochen: $name")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error).setSummaryText(name))
            .setColor(ERROR_COLOR)
            .setColorized(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(pendingIntent)
            .extend(NotificationCompat.WearableExtender())
            .build()

        NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
    }
}
