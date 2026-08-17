package com.wabridge.app

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the first time a new group messages in: fires the notification's
 * contentIntent (opens straight into that group, no invite link needed
 * for THIS step), then asks WaSendAccessibilityService to navigate
 * Group Info -> Invite via Link and read the link text off-screen -
 * exactly what a human would do, just performed by the app. On success,
 * the link is reported back to Code.gs so it's saved into Targets
 * permanently - after this one-time automatic step, the group behaves
 * exactly like any pre-configured group, forever, with zero manual
 * interaction at any point (not even from the account owner touching
 * the bridge device).
 */
object GroupLinkLearner {
    private const val TAG = "WaBridgeLearn"
    private const val PREFS_NAME = "wa_bridge_learned_groups"
    private const val LEARN_WAIT_TIMEOUT_MS = 20000L
    // Don't retry a failed/unattempted learn on every single message from
    // the same group - retry at most once per this cooldown, in case the
    // first attempt failed transiently (e.g. WhatsApp screen not ready).
    private const val RETRY_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes

    // Dedicated single-thread executor, separate from
    // WaNotificationListener's own posting executor. CRITICAL FIX
    // (FIX37): this function used to be called directly from
    // onNotificationPosted() and blocks on a CountDownLatch for up to
    // LEARN_WAIT_TIMEOUT_MS (20 seconds). Calling it inline blocked the
    // notification listener's own callback thread for up to 20s on
    // EVERY group message - a plausible real contributor to the
    // listener "falling"/getting rebound documented in section 6.4,
    // since Android can consider a service unresponsive if its callback
    // thread doesn't return promptly. Now the public entry point returns
    // immediately and the actual (blocking) work happens on this
    // dedicated background thread instead.
    private val learnExecutor = Executors.newSingleThreadExecutor()

    fun maybeLearnGroupLink(context: Context, target: String, contentIntent: PendingIntent?, webAppUrl: String?) {
        if (contentIntent == null || webAppUrl.isNullOrBlank()) return
        // Fire-and-forget from the caller's perspective - the caller
        // (WaNotificationListener.onNotificationPosted) must never block.
        learnExecutor.execute {
            try {
                doLearn(context.applicationContext, target, contentIntent, webAppUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during group link learn (non-fatal)", e)
                EventLog.log("Learn: ❌ שגיאה בלתי צפויה בתהליך למידת קישור: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun doLearn(context: Context, target: String, contentIntent: PendingIntent, webAppUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong(key(target), 0L)
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_COOLDOWN_MS) {
            val remainingMin = (RETRY_COOLDOWN_MS - (now - lastAttempt)) / 60000L
            Log.d(TAG, "Skipping learn for '$target' - attempted recently")
            // Previously silent (Log.d only, invisible without Logcat) -
            // this made it impossible to tell, from the on-screen log
            // alone, whether learning was ever attempted for a given
            // group vs. simply skipped due to cooldown. Confirmed via a
            // real incident: a group message came in, isGroupConversation
            // correctly detected true, yet zero "Learn:" lines appeared
            // anywhere in the log - this line is why.
            EventLog.log("Learn: ⏭️ מדלג על למידת קישור עבור '$target' - ניסיון קודם לפני פחות מ-30 דק' (עוד כ-${remainingMin} דק' עד ניסיון הבא)")
            return
        }
        prefs.edit().putLong(key(target), now).apply()

        if (LearnCoordinator.hasPendingLearn() || PhoneLearnCoordinator.hasPendingLearn() || SendCoordinator.hasPendingJob()) {
            Log.d(TAG, "Skipping learn for '$target' - another flow already in progress")
            EventLog.log("Learn: ⏭️ מדלג על למידת קישור עבור '$target' - תהליך אחר כבר רץ כרגע")
            return
        }

        EventLog.log("Learn: 🔍 קבוצה חדשה '$target' - מנסה ללמוד קישור הזמנה אוטומטית")

        val latch = CountDownLatch(1)
        var result = LearnCoordinator.Result.TIMEOUT
        var learnedLink: String? = null

        LearnCoordinator.startLearn(LearnCoordinator.PendingLearn(target)) { r, link ->
            result = r
            learnedLink = link
            latch.countDown()
        }

        try {
            contentIntent.send()
            EventLog.log("Learn: פתחתי את הקבוצה '$target' (דרך ה-contentIntent)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire contentIntent", e)
            EventLog.log("Learn: ❌ נכשל לפתוח את הקבוצה: ${e.message}")
            LearnCoordinator.reportResult(LearnCoordinator.Result.TIMEOUT)
        }

        val completed = latch.await(LEARN_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            EventLog.log("Learn: ❌ Timeout מחכה ללמידת קישור עבור '$target'")
            return
        }

        if (result == LearnCoordinator.Result.SUCCESS && learnedLink != null) {
            EventLog.log("Learn: ✅ קישור נלמד עבור '$target': $learnedLink")
            reportLearnedLink(webAppUrl, target, learnedLink!!)
            // Success - don't need the cooldown anymore, but leaving the
            // timestamp set is harmless (upsertTarget will just refresh
            // the same value if this ever fires again).
        } else {
            EventLog.log("Learn: ❌ לא הצלחתי ללמוד קישור עבור '$target' (result=$result)")
        }
    }

    private fun reportLearnedLink(webAppUrl: String, target: String, link: String) {
        try {
            val url = "$webAppUrl?action=saveGroupLink" +
                "&target=" + URLEncoder.encode(target, "UTF-8") +
                "&link=" + URLEncoder.encode(link, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            Log.i(TAG, "saveGroupLink reported, HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report learned link", e)
        }
    }

    private fun key(target: String) = "attempt_$target"
}
