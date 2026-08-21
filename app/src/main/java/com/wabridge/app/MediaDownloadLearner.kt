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
    private const val DOWNLOAD_WAIT_TIMEOUT_MS = 15000L

    /**
     * Returns true if the tap-to-download automation completed
     * (meaning it's worth re-scanning the media folder), false if it
     * was skipped entirely (another flow was already running, or no
     * contentIntent/chat was available) or timed out.
     */
    fun triggerDownloadAndWait(context: Context, target: String, mediaType: MediaClassifier.MediaType, contentIntent: PendingIntent?): Boolean {
        if (contentIntent == null) return false

        if (MediaDownloadCoordinator.hasPendingDownload() ||
            PhoneLearnCoordinator.hasPendingLearn() ||
            LearnCoordinator.hasPendingLearn() ||
            SendCoordinator.hasPendingJob()
        ) {
            Log.d(TAG, "Skipping media-download trigger for '$target' - another flow already in progress")
            EventLog.log("MediaDownload: ⏭️ תהליך אחר כבר רץ, מדלג על הכרחת הורדה עבור '$target'")
            return false
        }

        EventLog.log("MediaDownload: 📥 קובץ המדיה לא נמצא בדיסק - מנסה להכריח הורדה עבור '$target'")

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
