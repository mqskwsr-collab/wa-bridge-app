package com.wabridge.app

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
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
    private const val RETRY_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes

    fun maybeLearnPhone(context: Context, target: String, contentIntent: PendingIntent?, webAppUrl: String?) {
        if (contentIntent == null || webAppUrl.isNullOrBlank()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong(key(target), 0L)
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_COOLDOWN_MS) {
            Log.d(TAG, "Skipping phone-learn for '$target' - attempted recently")
            return
        }
        prefs.edit().putLong(key(target), now).apply()

        if (PhoneLearnCoordinator.hasPendingLearn() || LearnCoordinator.hasPendingLearn() || SendCoordinator.hasPendingJob()) {
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
