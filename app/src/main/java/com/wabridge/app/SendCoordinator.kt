package com.wabridge.app

/**
 * In-process coordination between PollingService (which knows WHAT to
 * send and WHERE) and WaSendAccessibilityService (which knows HOW to
 * find the message box / send button once WhatsApp's conversation
 * screen is actually on screen).
 *
 * Both run inside the same app process, so a simple synchronized
 * singleton is enough - no need for broadcasts/bound services.
 */
object SendCoordinator {

    data class PendingSend(
        val rowNumber: Int,
        val type: String,      // "group" | "private"
        val target: String,
        val text: String,
        val phoneOrLink: String,
        // FIX (31.8.2026, admin-less-group fallback): true when
        // PollingService had NO phoneOrLink to work with at all (most
        // commonly: a group we're not an admin in, so GroupLinkLearner
        // could never learn an invite link - see its own doc comment).
        // phoneOrLink is then simply "" and WaSendAccessibilityService
        // does not deep-link into anything; instead it drives
        // WhatsApp's own in-app search UI to find target by exact
        // display name and opens the matching chat-list row directly,
        // which needs no invite link or admin rights at all.
        val searchByName: Boolean = false
    )

    enum class Result { SUCCESS, FAILED_NO_TARGET_SCREEN, FAILED_NO_SEND_BUTTON, TIMEOUT }

    @Volatile var current: PendingSend? = null
        private set

    // A simple latch-like callback the polling service waits on.
    @Volatile private var resultCallback: ((Result) -> Unit)? = null

    @Synchronized
    fun startSend(job: PendingSend, onResult: (Result) -> Unit) {
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
    fun hasPendingJob(): Boolean = current != null
}
