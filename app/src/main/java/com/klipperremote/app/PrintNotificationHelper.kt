package com.klipperremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private const val CHANNEL_ID = "print_status"
    private const val NOTIFICATION_ID = 4711
    private const val ACCENT_COLOR = 0xFFFF6D00.toInt() // KlipperOrange

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Druckstatus",
                    NotificationManager.IMPORTANCE_DEFAULT
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
        etaText: String?
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
            .setContentTitle(title)
            .setContentText(name)
            .setColor(ACCENT_COLOR)
            .setColorized(true)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Druck beendet → laufende Benachrichtigung entfernen. */
    fun clearPrintProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
