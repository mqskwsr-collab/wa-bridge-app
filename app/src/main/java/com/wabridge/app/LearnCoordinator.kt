package com.wabridge.app

/**
 * Coordinates the automatic group-invite-link learning flow, mirroring
 * SendCoordinator's pattern: whoever triggers the learn attempt (the
 * notification listener) registers a job here; WaSendAccessibilityService
 * picks it up once WhatsApp's window changes and reports back a result.
 */
object LearnCoordinator {

    data class PendingLearn(val target: String)

    enum class Result { SUCCESS, FAILED_NO_LINK_FOUND, TIMEOUT }

    @Volatile var current: PendingLearn? = null
        private set

    @Volatile private var resultCallback: ((Result, String?) -> Unit)? = null

    @Synchronized
    fun startLearn(job: PendingLearn, onResult: (Result, String?) -> Unit) {
        current = job
        resultCallback = onResult
    }

    @Synchronized
    fun reportResult(result: Result, link: String? = null) {
        val cb = resultCallback
        current = null
        resultCallback = null
        cb?.invoke(result, link)
    }

    @Synchronized
    fun hasPendingLearn(): Boolean = current != null
}
