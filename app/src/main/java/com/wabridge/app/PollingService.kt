package com.wabridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replaces MacroDroid macro #3 (polling) + macro #2 (open WhatsApp and
 * send). Runs as a foreground service (persistent notification, like
 * MacroDroid's own background operation) so Android doesn't kill it.
 */
class PollingService : Service() {

    companion object {
        private const val TAG = "WaBridgePoll"
        private const val CHANNEL_ID = "wa_bridge_polling"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 45000L
        // How long to wait for the accessibility service to report a
        // result before giving up on this cycle (must exceed the
        // accessibility service's own internal SEARCH_TIMEOUT_MS).
        private const val SEND_WAIT_TIMEOUT_MS = 25000L

        @Volatile var isRunning = false
            private set

        // Guards against two overlapping send attempts running at once
        // (observed: the service got started twice in quick succession,
        // producing two concurrent handlePendingJob() calls for the same
        // row - the second one silently overwrote SendCoordinator's
        // state mid-flight, causing a "success" to be reported/marked
        // sent without the message actually having been typed+sent
        // correctly).
        private val processingLock = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            startForeground(NOTIFICATION_ID, buildNotification("פעיל - בודק תור כל 20 שניות"))
            isRunning = true
            EventLog.log("[${BuildInfo.BUILD_TAG}] Poll: השירות הופעל")
            workerThread = Thread { pollLoop() }.apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        workerThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        var cycleCount = 0
        var lastLoggedListenerConnected: Boolean? = null
        while (running.get()) {
            try {
                cycleCount++
                // FIX (21.8.2026): still checking + rebinding EVERY
                // cycle as before (that part is cheap and matters for
                // reliability), but no longer LOGGING every single
                // cycle - at one line per 45s that was ~80 lines/hour
                // of pure noise, which combined with the old 80-entry
                // buffer cap was the actual reason only ~1h of history
                // was ever visible (see EventLog.kt). Now only logs
                // when the state actually CHANGES (so a real
                // disconnect is still caught immediately), plus a
                // once-every-10-cycles (~7.5min) "still alive" ping so
                // it's still obvious the service didn't just die
                // silently.
                val listenerConnected = WaNotificationListener.isConnected
                val stateChanged = listenerConnected != lastLoggedListenerConnected
                if (stateChanged || cycleCount % 10 == 0) {
                    EventLog.log("Poll: 💓 מצב מאזין=${if (listenerConnected) "מחובר ✅" else "מנותק ❌"}" + if (stateChanged && lastLoggedListenerConnected != null) " (השתנה!)" else "")
                    lastLoggedListenerConnected = listenerConnected
                }
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(this, WaNotificationListener::class.java)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind failed", e)
                }

                val webAppUrl = Prefs.getWebAppUrl(this)
                if (webAppUrl.isNullOrBlank()) {
                    Log.w(TAG, "No Web App URL configured - stopping poll loop")
                    break
                }
                val check = httpGet("$webAppUrl?action=check")
                val json = JSONObject(check)
                if (json.optBoolean("found", false)) {
                    if (processingLock.compareAndSet(false, true)) {
                        try {
                            handlePendingJob(webAppUrl, json)
                        } finally {
                            processingLock.set(false)
                        }
                    } else {
                        Log.w(TAG, "Skipping this cycle - another send is already in progress")
                        EventLog.log("Poll: ⏭️ מדלג - שליחה אחרת כבר בתהליך (הגנה מפני כפילות)")
                    }
                } else {
                    Log.d(TAG, "check() returned found=false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Poll cycle failed", e)
                EventLog.log("Poll: ❌ מחזור נכשל: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun handlePendingJob(webAppUrl: String, json: JSONObject) {
        val rowNumber = json.optInt("rowNumber", -1)
        val type = json.optString("type", "")
        val target = json.optString("target", "")
        val text = json.optString("text", "")
        val phoneOrLink = json.optString("phoneOrLink", "")

        if (rowNumber < 0) {
            Log.w(TAG, "Pending job missing rowNumber, skipping this cycle: $json")
            EventLog.log("Poll: ⚠️ נמצאה שורה ממתינה עם row לא תקין - מדלג.")
            return
        }

        // FIX (31.8.2026, admin-less-group fallback): a blank
        // phoneOrLink used to mean an unconditional skip (see the old
        // comment/log this replaced) - in practice this happens
        // permanently for any group we're not admin in, since
        // GroupLinkLearner can then never learn an invite link no
        // matter how many times it retries (WhatsApp simply doesn't
        // expose "Invite via link" to non-admin members by default).
        // Rather than requiring the invite link at all, fall back to
        // driving WhatsApp's own in-app search by the target's exact
        // display name - see SendCoordinator.PendingSend.searchByName
        // and WaSendAccessibilityService's handling of it. This is
        // still attempted for BOTH group and private targets (the
        // mechanism is identical either way), so it also helps a
        // private contact we never captured/saved a phone number for.
        val searchByName = phoneOrLink.isBlank()
        if (searchByName) {
            Log.i(TAG, "No phoneOrLink for row=$rowNumber target=$target - will fall back to in-app search by name")
            EventLog.log("Poll: ℹ️ אין phoneOrLink עבור row=$rowNumber target=$target (כנראה אין הרשאות אדמין/קישור הזמנה) - מנסה לפתוח דרך חיפוש בתוך וואטסאפ במקום")
        }

        Log.i(TAG, "Pending job row=$rowNumber type=$type target=$target")
        EventLog.log("Poll: נמצא תור ממתין - row=$rowNumber target=$target")
        updateNotification("שולח הודעה ל: $target")

        // FAST PATH: if we still have this contact/group's own
        // notification "Reply" action captured (see ReplyRegistry), send
        // directly through it - instant, no need to open WhatsApp at
        // all, and works even for brand-new contacts we've never
        // manually configured a phone number for.
        val replyHandle = ReplyRegistry.get(target)
        if (replyHandle != null) {
            val sentDirectly = trySendViaReplyAction(replyHandle, text)
            if (sentDirectly) {
                Log.i(TAG, "Sent directly via notification reply action for row $rowNumber")
                EventLog.log("Poll: ⚡ נשלח מיידית דרך פעולת התשובה של ההתראה (בלי לפתוח וואטסאפ בכלל)")
                try {
                    httpGet("$webAppUrl?action=markSent&row=$rowNumber")
                } catch (e: Exception) {
                    Log.e(TAG, "markSent call failed for row $rowNumber", e)
                    EventLog.log("Poll: ⚠️ markSent נכשל: ${e.message}")
                }
                updateNotification("פעיל - בודק תור כל 20 שניות")
                return
            } else {
                Log.w(TAG, "Reply-action send failed (likely expired) - falling back to opening WhatsApp")
                EventLog.log("Poll: ⚠️ פעולת התשובה המהירה נכשלה (כנראה פגה) - עובר לשיטה הרגילה")
                ReplyRegistry.remove(target)
            }
        }

        // FIX (25.8.2026, concurrent-automation-flows bug): same class of
        // gap found in PhoneLearnLearner/GroupLinkLearner - this trigger
        // point had NO check at all against any other flow already
        // running, unlike the other three which (now) all check each
        // other. A queued reply could fire mid-way through an unrelated
        // media-download/phone-learn/group-link flow and collide on
        // screen exactly like the video-album case that surfaced this.
        if (MediaDownloadCoordinator.hasPendingDownload() || PhoneLearnCoordinator.hasPendingLearn() ||
            LearnCoordinator.hasPendingLearn() || SendCoordinator.hasPendingJob()) {
            Log.d(TAG, "Deferring send for row $rowNumber - another flow already in progress")
            EventLog.log("Poll: ⏭️ תהליך אחר כבר רץ - דוחה שליחה לשורה $rowNumber לסבב הבא")
            return
        }

        val job = SendCoordinator.PendingSend(rowNumber, type, target, text, phoneOrLink, searchByName)
        val latch = CountDownLatch(1)
        var result: SendCoordinator.Result = SendCoordinator.Result.TIMEOUT

        SendCoordinator.startSend(job) { r ->
            result = r
            latch.countDown()
        }

        try {
            if (searchByName) {
                // No URL to deep-link into at all - just bring WhatsApp
                // to the foreground on whatever screen it last had open
                // (usually the chat list). WaSendAccessibilityService's
                // searchByName handling takes it from there: opens
                // in-app search, types the target name, taps the
                // matching row.
                val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                if (launchIntent == null) {
                    throw IOException("com.whatsapp אינו מותקן / getLaunchIntentForPackage החזיר null")
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
                EventLog.log("Poll: פתחתי את וואטסאפ (חיפוש לפי שם - אין קישור/מספר)")
            } else {
                val chatUrl = if (type == "group") {
                    phoneOrLink
                } else {
                    "https://api.whatsapp.com/send?phone=$phoneOrLink"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(chatUrl)).apply {
                    // CLEAR_TASK forces WhatsApp's existing task to be torn
                    // down and rebuilt fresh from this intent, instead of
                    // just resuming whatever screen was already open (which
                    // was observed to sometimes silently ignore the deep
                    // link and land on an unrelated chat).
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    setPackage("com.whatsapp")
                }
                startActivity(intent)
                EventLog.log("Poll: פתחתי את וואטסאפ (${if (type=="group") "קבוצה" else "פרטי"})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp intent", e)
            EventLog.log("Poll: ❌ נכשל לפתוח וואטסאפ: ${e.message}")
            SendCoordinator.reportResult(SendCoordinator.Result.FAILED_NO_TARGET_SCREEN)
        }

        val completed = latch.await(SEND_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.w(TAG, "Timed out waiting for accessibility service result for row $rowNumber")
            EventLog.log("Poll: ❌ Timeout מחכה לתגובת שירות הנגישות")
            updateNotification("פעיל - בודק תור כל 20 שניות")
            return
        }

        if (result == SendCoordinator.Result.SUCCESS) {
            Log.i(TAG, "Send confirmed for row $rowNumber - calling markSent")
            EventLog.log("Poll: ✅ נשלח בהצלחה, מעדכן markSent")
            try {
                httpGet("$webAppUrl?action=markSent&row=$rowNumber")
            } catch (e: Exception) {
                Log.e(TAG, "markSent call failed for row $rowNumber", e)
                EventLog.log("Poll: ⚠️ markSent נכשל: ${e.message}")
            }
        } else {
            Log.w(TAG, "Send did NOT succeed for row $rowNumber (result=$result) - will retry next cycle")
            EventLog.log("Poll: ❌ השליחה לא הצליחה (result=$result), ינסה שוב בסבב הבא")
        }
        updateNotification("פעיל - בודק תור כל 20 שניות")
    }

    /**
     * Sends replyText directly through a captured notification "Reply"
     * action, exactly as if the user had used the inline-reply box in
     * the notification shade. Returns false if the action is no longer
     * valid (e.g. PendingIntent.CanceledException - the original
     * notification was dismissed/replaced since we captured it).
     */
    private fun trySendViaReplyAction(handle: ReplyRegistry.ReplyHandle, replyText: String): Boolean {
        return try {
            val resultIntent = Intent()
            val bundle = Bundle()
            for (ri in handle.remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(handle.remoteInputs, resultIntent, bundle)
            handle.actionIntent.send(this, 0, resultIntent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply action PendingIntent was cancelled (notification no longer live)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending via reply action", e)
            false
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            // Apps Script cold starts occasionally exceed 15 seconds.
            readTimeout = 30000
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            throw IOException("Apps Script החזיר HTTP $code")
        }
        if (body.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
            body.trimStart().startsWith("<html", ignoreCase = true)
        ) {
            throw IOException("Apps Script החזיר דף HTML במקום JSON (תקלה זמנית של Google)")
        }
        return body
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WA Bridge - שירות פולינג", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WA Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
