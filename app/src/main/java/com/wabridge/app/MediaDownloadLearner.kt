package com.wabridge.app

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FIX (20.8.2026): see MediaDownloadCoordinator's doc comment for the
 * root-cause diagnosis. This class opens the chat (like
 * PhoneLearnLearner/GroupLinkLearner already do via contentIntent) and
 * asks WaSendAccessibilityService to tap the most recent media bubble,
 * which forces WhatsApp to download the full-quality original to disk
 * exactly as if the user had opened it manually.
 *
 * UNLIKE PhoneLearnLearner/GroupLinkLearner, this is called with a
 * BLOCKING wait from attachMediaIfAny (which already runs on
 * WaNotificationListener's own single-thread executor, never the
 * NotificationListenerService callback thread - see FIX44's note on
 * PhoneLearnLearner for why that distinction matters) because the
 * caller needs to know whether to re-scan the media folder before it
 * can finish building the outgoing JSON body.
 */
object MediaDownloadLearner {
    private const val TAG = "WaBridgeMediaDownload"
    // FIX (23.8.2026, full-album swipe): must comfortably exceed
    // WaSendAccessibilityService's own dynamic per-job budget (which now
    // scales up with album size, capped at MEDIA_DOWNLOAD_TIMEOUT_MS_MAX
    // = 60s there) - otherwise this caller-side wait could give up on a
    // legitimately still-working multi-swipe album download before the
    // accessibility service itself does, same class of bug as the
    // existing PollingService.SEND_WAIT_TIMEOUT_MS vs. accessibility
    // SEARCH_TIMEOUT_MS margin already documented elsewhere.
    private const val DOWNLOAD_WAIT_TIMEOUT_MS = 65000L

    /**
     * Returns true if the tap-to-download automation completed
     * (meaning it's worth re-scanning the media folder), false if it
     * was skipped entirely (another flow was already running, or no
     * contentIntent/chat was available) or timed out.
     */
    // FIX (08.9.2026): a real transcript showed PhoneLearnCoordinator
    // grabbing its lock in the SAME second as a media-download attempt
    // for the same incoming message (both triggered off the same
    // notification - phone-learning fires for any new contact,
    // regardless of message type) - the old instant-bail-out here
    // meant the photo was silently never attached, even though
    // PhoneLearn itself finished about a second later. Instead of
    // giving up the instant any other coordinator happens to be busy,
    // poll for up to OTHER_FLOW_WAIT_TIMEOUT_MS for them to free up -
    // phone/group-link learning normally completes in 1-3s, so this
    // usually costs nothing observable and lets the real download
    // proceed instead of losing the attachment. Only after that whole
    // window is still busy do we fall back to the original skip.
    private const val OTHER_FLOW_WAIT_TIMEOUT_MS = 8000L
    private const val OTHER_FLOW_POLL_INTERVAL_MS = 250L

    private fun anyOtherFlowBusy(): Boolean =
        MediaDownloadCoordinator.hasPendingDownload() ||
            PhoneLearnCoordinator.hasPendingLearn() ||
            LearnCoordinator.hasPendingLearn() ||
            SendCoordinator.hasPendingJob()

    fun triggerDownloadAndWait(context: Context, target: String, mediaType: MediaClassifier.MediaType, contentIntent: PendingIntent?): Boolean {
        if (contentIntent == null) return false

        if (anyOtherFlowBusy()) {
            EventLog.log("MediaDownload: ⏳ תהליך אחר רץ כרגע עבור '$target' - ממתין עד ${OTHER_FLOW_WAIT_TIMEOUT_MS}ms שיתפנה")
            val waitDeadline = System.currentTimeMillis() + OTHER_FLOW_WAIT_TIMEOUT_MS
            while (anyOtherFlowBusy() && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(OTHER_FLOW_POLL_INTERVAL_MS)
            }
        }

        if (anyOtherFlowBusy()) {
            Log.d(TAG, "Skipping media-download trigger for '$target' - another flow still in progress after waiting")
            EventLog.log("MediaDownload: ⏭️ תהליך אחר עדיין רץ אחרי ההמתנה, מדלג על הכרחת הורדה עבור '$target'")
            return false
        }

        EventLog.log("MediaDownload: 📥 המדיה לא נמצאה במלואה בדיסק - מנסה להכריח הורדה עבור '$target'")

        val latch = CountDownLatch(1)
        var result = MediaDownloadCoordinator.Result.TIMEOUT

        MediaDownloadCoordinator.startDownload(MediaDownloadCoordinator.PendingDownload(target, mediaType)) { r ->
            result = r
            latch.countDown()
        }

        try {
            contentIntent.send()
            EventLog.log("MediaDownload: פתחתי את הצ'אט עם '$target' כדי להכריח הורדה")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire contentIntent for media download", e)
            EventLog.log("MediaDownload: ❌ נכשל לפתוח את הצ'אט: ${e.message}")
            MediaDownloadCoordinator.reportResult(MediaDownloadCoordinator.Result.TIMEOUT)
        }

        val completed = latch.await(DOWNLOAD_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            EventLog.log("MediaDownload: ❌ Timeout מחכה לתהליך ההורדה עבור '$target'")
            return false
        }

        return when (result) {
            MediaDownloadCoordinator.Result.SUCCESS -> {
                EventLog.log("MediaDownload: ✅ תהליך ההורדה הושלם עבור '$target' - בודק שוב את תיקיית המדיה")
                true
            }
            MediaDownloadCoordinator.Result.FAILED_NO_MEDIA_NODE_FOUND -> {
                EventLog.log("MediaDownload: ❌ לא נמצא בועת מדיה ללחיצה עבור '$target'")
                false
            }
            MediaDownloadCoordinator.Result.TIMEOUT -> {
                EventLog.log("MediaDownload: ❌ Timeout בתהליך ההורדה עבור '$target'")
                false
            }
        }
    }
}
