package com.wabridge.app

/**
 * FIX (20.8.2026): forces WhatsApp to actually write a media file to
 * disk when Media Auto-Download is off/limited (confirmed root cause -
 * see WaMediaLocator's diagnostics finding the correct folder but
 * completely EMPTY, 0 files, meaning WhatsApp never downloaded the
 * image at all - not a path or timing bug). Mirrors
 * PhoneLearnCoordinator's isolated pattern: this flow taps the most
 * recent media bubble in the open chat (which forces WhatsApp to
 * download the full-quality original, the same as a user manually
 * opening the image would), then backs out. Kept fully separate from
 * the other coordinators so it can't destabilize the already-working
 * send/group-link/phone-learn flows.
 */
object MediaDownloadCoordinator {

    data class PendingDownload(val target: String, val mediaType: MediaClassifier.MediaType)

    enum class Result { SUCCESS, FAILED_NO_MEDIA_NODE_FOUND, TIMEOUT }

    @Volatile var current: PendingDownload? = null
        private set

    @Volatile private var resultCallback: ((Result) -> Unit)? = null

    // FIX (23.8.2026, album-size mismatch): the media bubble WhatsApp
    // shows in the chat frequently carries its OWN true album size in
    // its accessibility content-description (e.g. "הצגת כל 5 פריטי
    // המדיה" / "showing all 5 media items") - confirmed on a real
    // device to be accurate (5) even when the triggering notification's
    // own text said only "תמונה אחת (1)" and so MediaClassifier.
    // extractCount() undercounted to 1, capping the whole find/attach
    // pipeline at a single file despite a real 5-photo album. Set by
    // WaSendAccessibilityService right before it taps the bubble; read
    // by WaNotificationListener.attachMediaIfAny afterwards so it can
    // widen its post-download re-scan to the real count instead of
    // trusting the notification text alone. Cleared on every new
    // startDownload so a stale value from a previous, unrelated album
    // can never leak into an unrelated later message.
    @Volatile var lastDetectedAlbumSize: Int? = null
        private set

    @Synchronized
    fun reportDetectedAlbumSize(size: Int) {
        lastDetectedAlbumSize = size
    }

    @Synchronized
    fun startDownload(job: PendingDownload, onResult: (Result) -> Unit) {
        current = job
        resultCallback = onResult
        lastDetectedAlbumSize = null
    }

    @Synchronized
    fun reportResult(result: Result) {
        val cb = resultCallback
        current = null
        resultCallback = null
        cb?.invoke(result)
    }

    @Synchronized
    fun hasPendingDownload(): Boolean = current != null
}
