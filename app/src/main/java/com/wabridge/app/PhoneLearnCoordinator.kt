package com.wabridge.app

/**
 * Isolated from LearnCoordinator (group-link learning) deliberately -
 * this is a NEW, unverified automation flow (reading a contact's phone
 * number off the "Contact Info" screen). Keeping it fully separate
 * means if this flow needs debugging/retrying, it cannot accidentally
 * destabilize the already-confirmed-working group-link learning flow.
 */
object PhoneLearnCoordinator {

    data class PendingLearn(val target: String)

    enum class Result { SUCCESS, FAILED_NO_PHONE_FOUND, TIMEOUT }

    @Volatile var current: PendingLearn? = null
        private set

    @Volatile private var resultCallback: ((Result, String?) -> Unit)? = null

    @Synchronized
    fun startLearn(job: PendingLearn, onResult: (Result, String?) -> Unit) {
        current = job
        resultCallback = onResult
    }

    @Synchronized
    fun reportResult(result: Result, phone: String? = null) {
        val cb = resultCallback
        current = null
        resultCallback = null
        cb?.invoke(result, phone)
    }

    @Synchronized
    fun hasPendingLearn(): Boolean = current != null
}
