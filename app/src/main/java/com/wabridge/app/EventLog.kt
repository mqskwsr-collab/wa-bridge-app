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
    // FIX (21.8.2026): was 80, which combined with a heartbeat logged
    // every 45s poll cycle (see PollingService) meant the buffer held
    // EXACTLY 80*45s = 3600s = 1 hour before the oldest (often the most
    // relevant, e.g. media-download diagnostics) entries got evicted -
    // this is precisely the "only an hour of logs" symptom. Raised to
    // 4000 entries; at ~100 bytes/line that's ~400KB in memory, trivial
    // for a phone, and MainActivity's copy button already truncates to
    // the last 200k chars defensively (see FIX37) so this can't cause
    // the earlier TransactionTooLargeException regression. Combined
    // with throttling the heartbeat itself (see PollingService), this
    // should comfortably cover many hours of real history.
    private const val MAX_ENTRIES = 4000
    // FIX (21.8.2026): added the date - now that the buffer can span
    // well over 24h, "HH:mm:ss" alone would make entries from
    // different days indistinguishable/ambiguous.
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(message: String) {
        val line = "${fmt.format(Date())}  $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun getAll(): String = entries.joinToString("\n")
}
