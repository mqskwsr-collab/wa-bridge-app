package com.wabridge.app

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL (new, unverified as of 14.8.2026) - mirrors
 * GroupLinkLearner's proven pattern, applied to private contacts:
 * opens the chat via contentIntent, then asks
 * WaSendAccessibilityService to click the contact's name in the
 * toolbar (opens "Contact Info") and scan that screen for a
 * phone-number-shaped string. This is a DIFFERENT, previously
 * untried approach from the confirmed-dead-end "read it off the
 * notification's Person data" avenue (see status doc section 1/8.1) -
 * WhatsApp's Contact Info screen often DOES display the number even
 * for saved contacts, so this may succeed where the notification-data
 * approach could not, but this has not yet been tested live.
 */
object PhoneLearnLearner {
    private const val TAG = "WaBridgePhoneLearn"
    private const val PREFS_NAME = "wa_bridge_learned_phones"
    private const val LEARN_WAIT_TIMEOUT_MS = 20000L
    // FIX (19.8.2026): shortened from 30 min to match GroupLinkLearner -
    // doLearn() now checks Targets in real time first (see
    // checkTargetAlreadyKnown), so this cooldown is just a light guard
    // against re-opening WhatsApp on every message in a fast burst, not
    // the only thing standing between a resolved blocker and a retry.
    private const val RETRY_COOLDOWN_MS = 3 * 60 * 1000L // 3 minutes

    // FIX44: this used to be called directly from
    // WaNotificationListener.onNotificationPosted() and blocks on a
    // CountDownLatch for up to LEARN_WAIT_TIMEOUT_MS (20 seconds) -
    // exactly the bug already fixed in GroupLinkLearner under FIX37,
    // which this class's own doc comment claims to mirror but never
    // actually received the executor wrapping for. Calling it inline
    // froze the listener's callback thread for up to 20s on every new
    // private contact, during which any other notification Android
    // tried to deliver could be silently dropped/coalesced - this is
    // the confirmed cause of Yoni's group message never even reaching
    // the on-screen log. The public entry point now returns
    // immediately; the actual blocking work happens on this dedicated
    // background thread instead.
    private val learnExecutor = Executors.newSingleThreadExecutor()

    fun maybeLearnPhone(context: Context, target: String, contentIntent: PendingIntent?, webAppUrl: String?) {
        if (contentIntent == null || webAppUrl.isNullOrBlank()) return
        // Fire-and-forget from the caller's perspective - the caller
        // (WaNotificationListener.onNotificationPosted) must never block.
        learnExecutor.execute {
            try {
                doLearn(context.applicationContext, target, contentIntent, webAppUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during phone learn (non-fatal)", e)
                EventLog.log("PhoneLearn: ❌ שגיאה בלתי צפויה בתהליך למידת מספר: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun doLearn(context: Context, target: String, contentIntent: PendingIntent, webAppUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // FIX (19.8.2026): see GroupLinkLearner's identical fix note -
        // no more permanent local "known" flag, so a manually deleted
        // Targets row is noticed on the very next message instead of
        // staying silently stuck as "known" on this device forever.
        if (checkTargetAlreadyKnown(webAppUrl, target)) {
            return
        }

        val lastAttempt = prefs.getLong(key(target), 0L)
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_COOLDOWN_MS) {
            Log.d(TAG, "Skipping phone-learn for '$target' - attempted recently")
            return
        }
        prefs.edit().putLong(key(target), now).apply()

        // FIX (25.8.2026, concurrent-automation-flows bug): confirmed on
        // a real device - this guard checked every OTHER coordinator but
        // never MediaDownloadCoordinator, so a phone-learn attempt could
        // (and did) start while a video album's forced-download was
        // still mid-flight, firing its own contentIntent.send() and
        // tapping the contact-info header partway through - which
        // knocked the concurrent MediaDownload flow off the screen state
        // it was tracking and broke "More options" detection for every
        // remaining album item from that point on. Added the missing
        // check.
        if (PhoneLearnCoordinator.hasPendingLearn() || LearnCoordinator.hasPendingLearn() ||
            SendCoordinator.hasPendingJob() || MediaDownloadCoordinator.hasPendingDownload()) {
            Log.d(TAG, "Skipping phone-learn for '$target' - another flow already in progress")
            return
        }

        EventLog.log("PhoneLearn: 🔍 איש קשר חדש '$target' - מנסה ללמוד מספר טלפון אוטומטית (ניסיוני)")

        val latch = CountDownLatch(1)
        var result = PhoneLearnCoordinator.Result.TIMEOUT
        var learnedPhone: String? = null

        PhoneLearnCoordinator.startLearn(PhoneLearnCoordinator.PendingLearn(target)) { r, phone ->
            result = r
            learnedPhone = phone
            latch.countDown()
        }

        try {
            contentIntent.send()
            EventLog.log("PhoneLearn: פתחתי את הצ'אט עם '$target' (דרך ה-contentIntent)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire contentIntent", e)
            EventLog.log("PhoneLearn: ❌ נכשל לפתוח את הצ'אט: ${e.message}")
            PhoneLearnCoordinator.reportResult(PhoneLearnCoordinator.Result.TIMEOUT)
        }

        val completed = latch.await(LEARN_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            EventLog.log("PhoneLearn: ❌ Timeout מחכה ללמידת מספר עבור '$target'")
            return
        }

        if (result == PhoneLearnCoordinator.Result.SUCCESS && learnedPhone != null) {
            EventLog.log("PhoneLearn: ✅ מספר נלמד עבור '$target': $learnedPhone")
            reportLearnedPhone(webAppUrl, target, learnedPhone!!)
        } else {
            EventLog.log("PhoneLearn: ❌ לא הצלחתי ללמוד מספר עבור '$target' (result=$result)")
        }
    }

    /** See GroupLinkLearner.checkTargetAlreadyKnown - identical purpose, applied to private contacts. */
    private fun checkTargetAlreadyKnown(webAppUrl: String, target: String): Boolean {
        return try {
            val url = "$webAppUrl?action=lookupTarget&target=" + URLEncoder.encode(target, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code != 200) return false
            val json = JSONObject(body)
            json.optBoolean("found", false) && json.optString("phoneOrLink", "").isNotBlank()
        } catch (e: Exception) {
            Log.e(TAG, "lookupTarget failed for '$target' (assuming not known yet, will attempt learn)", e)
            false
        }
    }

    private fun reportLearnedPhone(webAppUrl: String, target: String, phone: String) {
        try {
            val url = "$webAppUrl?action=savePhone" +
                "&target=" + URLEncoder.encode(target, "UTF-8") +
                "&phone=" + URLEncoder.encode(phone, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            Log.i(TAG, "savePhone reported, HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report learned phone", e)
        }
    }

    private fun key(target: String) = "attempt_$target"
}
