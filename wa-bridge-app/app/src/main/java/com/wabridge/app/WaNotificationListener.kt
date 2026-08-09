package com.wabridge.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Reads incoming WhatsApp (regular, package "com.whatsapp") notifications
 * and forwards title+text to the SAME Apps Script Web App endpoint that
 * MacroDroid's macro #1 ("בדיקה - נוטיפיקציה") currently posts to.
 *
 * IMPORTANT: this deliberately does NOT try to classify group vs private,
 * strip bidi marks, or do anything else "smart" - all of that logic
 * already lives server-side in Code.gs's classifyNotification(), and this
 * keeps the backend contract identical to what MacroDroid already sends,
 * so Code.gs needs ZERO changes for this phase.
 *
 * Setup required (see MainActivity):
 *   1. User must grant "Notification access" for this app in Android
 *      Settings > Apps > Special app access > Notification access.
 *   2. The Apps Script Web App URL must be entered once in the app's
 *      main screen (stored in SharedPreferences, see Prefs.kt).
 */
class WaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WaBridge"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        // In-memory de-duplication: Android can re-post/update the same
        // logical notification multiple times in quick succession (e.g.
        // when a second message arrives before the first is dismissed).
        // We key on (title + text) and ignore exact repeats within a short
        // window, mirroring the practical effect of MacroDroid's
        // "Notification Received" trigger which only fires on genuinely
        // new content.
        private var lastKey: String? = null
        private var lastTimestamp: Long = 0L
        private const val DEDUPE_WINDOW_MS = 3000L
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isEmpty()) {
            Log.d(TAG, "Ignoring notification with empty title (likely a summary/foreground-service notification)")
            return
        }

        val dedupeKey = "$title|$text"
        val now = System.currentTimeMillis()
        if (dedupeKey == lastKey && (now - lastTimestamp) < DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicate notification within dedupe window: $dedupeKey")
            return
        }
        lastKey = dedupeKey
        lastTimestamp = now

        val webAppUrl = Prefs.getWebAppUrl(this)
        if (webAppUrl.isNullOrBlank()) {
            Log.w(TAG, "No Web App URL configured yet - open the app and set it up. Dropping notification.")
            return
        }

        Log.i(TAG, "WhatsApp notification: title='$title' text='$text' -> forwarding")
        executor.execute { postToAppsScript(webAppUrl, title, text) }
    }

    private fun postToAppsScript(webAppUrl: String, title: String, text: String) {
        try {
            val body = JSONObject().apply {
                put("title", title)
                put("text", text)
            }.toString()

            val url = URL(webAppUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15000
                readTimeout = 15000
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val responseCode = conn.responseCode
            val responseBody = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Log.i(TAG, "POST result: HTTP $responseCode body=$responseBody")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST notification to Apps Script", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op - we only care about newly posted notifications.
    }
}
