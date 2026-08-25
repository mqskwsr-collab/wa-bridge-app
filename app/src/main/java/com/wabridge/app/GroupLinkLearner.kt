package com.wabridge.app

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
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
    // FIX (19.8.2026): this used to be the ONLY gate on retrying a
    // failed learn attempt, and it was 30 minutes - meaning if the real
    // blocker was something transient/external (e.g. the group was
    // locked and "Invite via link" genuinely wasn't offered until the
    // account got the right permission), every message in between was
    // silently skipped for up to half an hour even after the blocker
    // was resolved. Now doLearn() first asks Code.gs in real time
    // whether Targets already has this target's phone/link (see
    // checkTargetAlreadyKnown below) - if it does, we skip permanently
    // with zero waiting; if it doesn't, we only need this cooldown as a
    // light guard against re-opening the WhatsApp screen on every
    // single message in a fast burst, so it can be much shorter now.
    private const val RETRY_COOLDOWN_MS = 3 * 60 * 1000L // 3 minutes

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

        // FIX (19.8.2026): there used to be a permanent local "known"
        // flag here that, once set, skipped the sheet check forever -
        // but that meant if a target's row was later deleted from
        // Targets (e.g. to clean up a messy manual entry), this device
        // would keep believing it's still known and never notice or
        // re-attempt learning. Removed - Code.gs's Targets sheet is now
        // checked fresh on every single incoming message (a light GET,
        // not the actual WhatsApp-UI-touching learn flow), so a manual
        // deletion is picked up on the very next message, not stuck
        // forever. Only the real learn attempt below stays gated by the
        // short cooldown.
        if (checkTargetAlreadyKnown(webAppUrl, target)) {
            return
        }

        val lastAttempt = prefs.getLong(key(target), 0L)
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_COOLDOWN_MS) {
            val remainingSec = (RETRY_COOLDOWN_MS - (now - lastAttempt)) / 1000L
            Log.d(TAG, "Skipping learn for '$target' - attempted recently")
            // Previously silent (Log.d only, invisible without Logcat) -
            // this made it impossible to tell, from the on-screen log
            // alone, whether learning was ever attempted for a given
            // group vs. simply skipped due to cooldown. Confirmed via a
            // real incident: a group message came in, isGroupConversation
            // correctly detected true, yet zero "Learn:" lines appeared
            // anywhere in the log - this line is why.
            EventLog.log("Learn: ⏭️ מדלג על למידת קישור עבור '$target' - ניסיון קודם לפני פחות מ-${RETRY_COOLDOWN_MS / 60000L} דק' (עוד כ-${remainingSec} שנ' עד ניסיון הבא)")
            return
        }
        prefs.edit().putLong(key(target), now).apply()

        // FIX (25.8.2026, concurrent-automation-flows bug): same gap as
        // PhoneLearnLearner's identical guard - MediaDownloadCoordinator
        // was never checked here either, so a group-link-learn attempt
        // could equally have collided with a media album mid-download.
        if (LearnCoordinator.hasPendingLearn() || PhoneLearnCoordinator.hasPendingLearn() ||
            SendCoordinator.hasPendingJob() || MediaDownloadCoordinator.hasPendingDownload()) {
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
            // No local "permanently known" flag anymore (see FIX note
            // above doLearn) - the next message will simply confirm this
            // via checkTargetAlreadyKnown() against the sheet, which by
            // then reflects what reportLearnedLink() just saved.
        } else {
            EventLog.log("Learn: ❌ לא הצלחתי ללמוד קישור עבור '$target' (result=$result)")
        }
    }

    /**
     * Asks Code.gs, in real time, whether the Targets sheet already has a
     * phone/link saved for this target - the actual source of truth,
     * covering both "we learned it successfully before" and "a human
     * added it to the sheet manually" (e.g. right after unlocking a
     * group's invite-link permission, as happened for 'משפוחה'). Returns
     * false (i.e. "go ahead and try learning") on any network/parse
     * error, since that's the safe default - worst case we just attempt
     * the existing accessibility flow as before.
     */
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
            if (code != 200) {
                Log.w(TAG, "lookupTarget HTTP $code for '$target' - assuming not known yet")
                return false
            }
            val json = JSONObject(body)
            json.optBoolean("found", false) && json.optString("phoneOrLink", "").isNotBlank()
        } catch (e: Exception) {
            Log.e(TAG, "lookupTarget failed for '$target' (assuming not known yet, will attempt learn)", e)
            false
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
