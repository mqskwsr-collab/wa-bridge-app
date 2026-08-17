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

        // Explicit connection-state heartbeat, so PollingService can log
        // "is the listener actually connected right now" on every poll
        // cycle - not just retroactively inferred from missing messages.
        @Volatile var isConnected = false

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
        try {
            handleNotification(sbn)
        } catch (e: Exception) {
            // Last line of defense: NOTHING in here should ever be
            // allowed to crash the whole app process - a crash here
            // takes down PollingService and the Accessibility service
            // along with it, silently, with zero trace (this is exactly
            // what happened with the actionIntent NPE bug above before
            // it was fixed). Log it and move on instead.
            Log.e(TAG, "Unexpected error handling notification (non-fatal, continuing)", e)
            EventLog.log("Listener: ❌ שגיאה בלתי צפויה בטיפול בהתראה (לא קריטי): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // Log EVERY WhatsApp notification the listener sees, before any
        // filtering - this is the only way to tell whether the listener
        // is even receiving events at all (vs. being killed/suspended by
        // Android in the background) from the on-screen log, since
        // nothing here was visible in EventLog before this fix.
        EventLog.log("Listener: 📩 התראת וואטסאפ התקבלה: title='$title' text='${text.take(40)}'")

        if (title.isEmpty()) {
            Log.d(TAG, "Ignoring notification with empty title (likely a summary/foreground-service notification)")
            EventLog.log("Listener: ⏭️ מתעלם - כותרת ריקה (כנראה התראת סיכום/מערכת)")
            return
        }

        // WhatsApp posts generic system notifications (reconnecting,
        // syncing, media download progress, etc.) whose title is
        // literally "WhatsApp" itself rather than a contact/group name -
        // these were being incorrectly forwarded as if a contact named
        // "WhatsApp" had messaged. Filter them out.
        if (Utils.stripBidiMarks(title).trim().equals("WhatsApp", ignoreCase = true)) {
            Log.d(TAG, "Ignoring generic WhatsApp system notification (title == 'WhatsApp')")
            EventLog.log("Listener: ⏭️ מתעלם - התראת מערכת גנרית ('WhatsApp')")
            return
        }

        val dedupeKey = "$title|$text"
        val now = System.currentTimeMillis()
        if (dedupeKey == lastKey && (now - lastTimestamp) < DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicate notification within dedupe window: $dedupeKey")
            EventLog.log("Listener: ⏭️ מתעלם - כפילות תוך ${DEDUPE_WINDOW_MS}ms")
            return
        }
        lastKey = dedupeKey
        lastTimestamp = now

        // Detect group vs private using Android's own MessagingStyle flag
        // instead of a manually-maintained group-name whitelist - this
        // means brand new groups are recognized automatically, with no
        // code edits ever required.
        //
        // FIX38: this must run BEFORE computing canonicalTarget (moved up
        // from further below) - canonicalTarget's group-name extraction
        // needs to know isGroup up front to correctly strip WhatsApp's
        // "GroupName: SenderName" title format for brand-new groups (see
        // Utils.canonicalTarget's doc comment for the real incident this
        // fixes). Previously isGroup was computed AFTER the reply-action
        // capture below, which used the old (uncorrected) target.
        val isGroup = GroupDetector.isGroupConversation(sbn)
        val canonicalTarget = Utils.canonicalTarget(title, isGroup)
        EventLog.log("Listener: 🔍 isGroupConversation=$isGroup עבור '$canonicalTarget'")

        // Capture the notification's own "Reply" action (RemoteInput),
        // if present - this lets PollingService send the eventual email
        // reply DIRECTLY through WhatsApp's inline-reply mechanism later,
        // with no need to open the app, use Accessibility, or already
        // know this contact's phone number. See ReplyRegistry.
        //
        // IMPORTANT: wrapped defensively - Notification.Action's fields
        // (actionIntent in particular) are nullable in the underlying
        // Java API even though Kotlin's platform-type inference doesn't
        // always flag that. A null actionIntent here previously caused
        // an unguarded NullPointerException that crashed the ENTIRE app
        // process (confirmed via a real "WA Bridge has stopped" crash on
        // an actual incoming message) - taking down PollingService and
        // the Accessibility service along with it, and explaining why
        // messages appeared to just vanish with no log trace at all.
        try {
            val actions = sbn.notification.actions
            if (actions != null) {
                for (action in actions) {
                    val remoteInputs = action?.remoteInputs
                    val actionIntent = action?.actionIntent
                    if (remoteInputs != null && remoteInputs.isNotEmpty() && actionIntent != null) {
                        ReplyRegistry.put(
                            canonicalTarget,
                            ReplyRegistry.ReplyHandle(actionIntent, remoteInputs, now)
                        )
                        Log.i(TAG, "Captured reply action for '$canonicalTarget'")
                        EventLog.log("Listener: 💾 נשמרה פעולת תשובה מהירה עבור '$canonicalTarget'")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a malformed/unexpected notification action shape
            // crash the whole listener - this is a "nice to have" fast
            // path, not something worth risking the entire app for.
            Log.e(TAG, "Failed to capture reply action (non-fatal, continuing)", e)
            EventLog.log("Listener: ⚠️ נכשל לשמור פעולת תשובה מהירה (לא קריטי, ממשיך): ${e.javaClass.simpleName}")
        }

        val webAppUrl = Prefs.getWebAppUrl(this)
        if (webAppUrl.isNullOrBlank()) {
            Log.w(TAG, "No Web App URL configured yet - open the app and set it up. Dropping notification.")
            EventLog.log("Listener: ⚠️ אין כתובת Web App מוגדרת - ההתראה נזרקה")
            return
        }

        // Capture the notification's contentIntent (opens straight into
        // this exact conversation, no invite link needed) - used below
        // to automatically learn a new group's invite link the first
        // time it messages in.
        try {
            val contentIntent = sbn.notification.contentIntent
            if (contentIntent != null) {
                OpenIntentRegistry.put(canonicalTarget, contentIntent)
            }
            if (isGroup == true) {
                GroupLinkLearner.maybeLearnGroupLink(this, canonicalTarget, contentIntent, webAppUrl)
            } else if (isGroup == false) {
                PhoneLearnLearner.maybeLearnPhone(this, canonicalTarget, contentIntent, webAppUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture/process contentIntent (non-fatal)", e)
        }

        // Best-effort: extract a phone number from the notification's
        // Person data, if WhatsApp included one (tel: URI). When
        // present, this lets Code.gs permanently remember this contact
        // in the Targets sheet automatically, so replies keep working
        // indefinitely - not just while ReplyRegistry's captured action
        // is still valid (see ReplyRegistry's doc comment for why that
        // alone isn't durable long-term).
        val phone = try {
            PersonPhoneExtractor.extract(sbn)
        } catch (e: Throwable) {
            null
        }

        Log.i(TAG, "WhatsApp notification: title='$title' text='$text' phone=$phone isGroup=$isGroup -> forwarding")
        EventLog.log("Listener: ➡️ שולח ל-Apps Script...")
        executor.execute { postToAppsScript(webAppUrl, title, text, phone, isGroup) }
    }

    
    private fun postToAppsScript(webAppUrl: String, title: String, text: String, phone: String?, isGroup: Boolean?) {
        try {
            val body = JSONObject().apply {
                put("title", title)
                put("text", text)
                if (phone != null) put("phone", phone)
                if (isGroup != null) put("isGroup", isGroup)
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
            EventLog.log("Listener: ✅ נשלח, HTTP $responseCode: ${responseBody.take(150)}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST notification to Apps Script", e)
            EventLog.log("Listener: ❌ שליחה נכשלה: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "Notification listener connected")
        EventLog.log("Listener: 🔌 שירות ההאזנה להתראות התחבר")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "Notification listener disconnected")
        EventLog.log("Listener: ⚠️ שירות ההאזנה להתראות התנתק")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op - we only care about newly posted notifications.
    }
}
