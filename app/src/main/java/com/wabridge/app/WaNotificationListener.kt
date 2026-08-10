package com.wabridge.app

import android.app.Notification
import android.app.Person
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

        // WhatsApp posts generic system notifications (reconnecting,
        // syncing, media download progress, etc.) whose title is
        // literally "WhatsApp" itself rather than a contact/group name -
        // these were being incorrectly forwarded as if a contact named
        // "WhatsApp" had messaged. Filter them out.
        if (Utils.stripBidiMarks(title).trim().equals("WhatsApp", ignoreCase = true)) {
            Log.d(TAG, "Ignoring generic WhatsApp system notification (title == 'WhatsApp')")
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

        // Capture the notification's own "Reply" action (RemoteInput),
        // if present - this lets PollingService send the eventual email
        // reply DIRECTLY through WhatsApp's inline-reply mechanism later,
        // with no need to open the app, use Accessibility, or already
        // know this contact's phone number. See ReplyRegistry.
        val canonicalTarget = Utils.canonicalTarget(title)
        val actions = sbn.notification.actions
        if (actions != null) {
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    ReplyRegistry.put(
                        canonicalTarget,
                        ReplyRegistry.ReplyHandle(action.actionIntent, remoteInputs, now)
                    )
                    Log.i(TAG, "Captured reply action for '$canonicalTarget'")
                    EventLog.log("Listener: 💾 נשמרה פעולת תשובה מהירה עבור '$canonicalTarget'")
                    break
                }
            }
        }

        val webAppUrl = Prefs.getWebAppUrl(this)
        if (webAppUrl.isNullOrBlank()) {
            Log.w(TAG, "No Web App URL configured yet - open the app and set it up. Dropping notification.")
            return
        }

        // Best-effort: extract a phone number from the notification's
        // Person data, if WhatsApp included one (tel: URI). When
        // present, this lets Code.gs permanently remember this contact
        // in the Targets sheet automatically, so replies keep working
        // indefinitely - not just while ReplyRegistry's captured action
        // is still valid (see ReplyRegistry's doc comment for why that
        // alone isn't durable long-term).
        val phone = extractPhoneNumber(sbn)

        Log.i(TAG, "WhatsApp notification: title='$title' text='$text' phone=$phone -> forwarding")
        executor.execute { postToAppsScript(webAppUrl, title, text, phone) }
    }

    private fun extractPhoneNumber(sbn: StatusBarNotification): String? {
        try {
            val extras = sbn.notification.extras
            val person = extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)
            val uri = person?.uri
            if (uri != null && uri.startsWith("tel:", ignoreCase = true)) {
                return uri.substring(4).replace(Regex("[^+0-9]"), "")
            }
        } catch (e: Exception) {
            Log.d(TAG, "No phone number available from notification Person data", e)
        }
        return null
    }

    private fun postToAppsScript(webAppUrl: String, title: String, text: String, phone: String?) {
        try {
            val body = JSONObject().apply {
                put("title", title)
                put("text", text)
                if (phone != null) put("phone", phone)
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
