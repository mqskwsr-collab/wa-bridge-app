package com.wabridge.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory ring-buffer log, so diagnostic info can be shown
 * directly on MainActivity's screen (tvLastEvent) instead of requiring
 * Logcat/Android Studio - the user works entirely via screenshots on
 * NoxPlayer, with no ADB/Android Studio access.
 */
object EventLog {
    private val entries = ArrayDeque<String>()
    private const val MAX_ENTRIES = 80
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(message: String) {
        val line = "${fmt.format(Date())}  $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun getAll(): String = entries.joinToString("\n")
}
