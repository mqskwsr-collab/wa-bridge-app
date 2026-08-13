package com.wabridge.app

import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Isolated in its own class deliberately (same reasoning as
 * PersonPhoneExtractor): if any referenced class here is missing or
 * misbehaves on this device/ROM, it should fail safely rather than
 * crash the caller.
 *
 * Uses Android's own MessagingStyle.isGroupConversation() flag -
 * WhatsApp (like any standard messaging app implementing Android's
 * conversation notifications) sets this when building a group
 * notification, so this is the OFFICIAL signal for exactly this
 * question rather than guessing from text patterns or a manually
 * maintained group-name whitelist.
 */
object GroupDetector {
    /** Returns true/false if determinable, or null if the flag couldn't be read at all. */
    fun isGroupConversation(sbn: StatusBarNotification): Boolean? {
        return try {
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification)
            val result = style?.isGroupConversation
            EventLog.log("GroupDetect: style=${style != null} isGroupConversation=$result")
            result
        } catch (e: Throwable) {
            EventLog.log("GroupDetect: ❌ נכשל (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }
}
