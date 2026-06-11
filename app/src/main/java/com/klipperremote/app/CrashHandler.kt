package com.klipperremote.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val LOG_FILE = "crash_log.txt"
    private const val MAX_ENTRIES = 10
    private val SEPARATOR = "=== CRASH "

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            appContext?.let { ctx ->
                val file = File(ctx.filesDir, LOG_FILE)
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val newEntry = buildString {
                    append("$SEPARATOR$timestamp ===\n")
                    append("Thread: ${thread.name}\n\n")
                    append(throwable.stackTraceToString())
                    append("\n\n")
                }
                val existing = if (file.exists()) file.readText() else ""
                // Split by separator, keep newest MAX_ENTRIES entries
                val allEntries = (newEntry + existing)
                    .split(SEPARATOR)
                    .filter { it.isNotBlank() }
                    .take(MAX_ENTRIES)
                val combined = allEntries.joinToString("") { "$SEPARATOR$it" }
                file.writeText(combined)
            }
        } catch (_: Exception) {
            // Crash-Handler darf nie selbst crashen
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    fun readLog(context: Context): String {
        val file = File(context.filesDir, LOG_FILE)
        return if (file.exists()) file.readText() else ""
    }

    fun clearLog(context: Context) {
        File(context.filesDir, LOG_FILE).delete()
    }

    fun hasLog(context: Context): Boolean {
        val file = File(context.filesDir, LOG_FILE)
        return file.exists() && file.length() > 0
    }
}
