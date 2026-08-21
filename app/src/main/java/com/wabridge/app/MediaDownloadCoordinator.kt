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

    @Synchronized
    fun startDownload(job: PendingDownload, onResult: (Result) -> Unit) {
        current = job
        resultCallback = onResult
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
