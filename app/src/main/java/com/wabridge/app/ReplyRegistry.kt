package com.wabridge.app

import android.app.PendingIntent
import android.app.RemoteInput

/**
 * Holds the WhatsApp notification's own "Reply" action (the same
 * mechanism behind the inline reply button in the notification shade)
 * for each contact/group, captured at the moment their message arrives
 * (see WaNotificationListener). If we still have it when a reply comes
 * back from email, we can send the reply DIRECTLY through it - no need
 * to open WhatsApp, no Accessibility clicking, and critically: no need
 * to know the contact's phone number in advance, so brand-new contacts
 * who message first are handled automatically without any manual setup
 * (the "Targets" sheet is no longer needed for anyone who has messaged
 * in first).
 *
 * Limitation: this only works while the app process that received the
 * notification is still alive (a PendingIntent from a notification
 * becomes invalid once the notification is dismissed/replaced, and this
 * in-memory map is lost if the app process is killed and restarted).
 * PollingService falls back to the Accessibility-based flow
 * automatically when no live handle is available.
 */
object ReplyRegistry {

    data class ReplyHandle(
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val savedAt: Long
    )

    private val map = HashMap<String, ReplyHandle>()

    @Synchronized
    fun put(target: String, handle: ReplyHandle) {
        map[target] = handle
    }

    @Synchronized
    fun get(target: String): ReplyHandle? = map[target]

    @Synchronized
    fun remove(target: String) {
        map.remove(target)
    }

    @Synchronized
    fun size(): Int = map.size
}
