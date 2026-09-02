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

    // FIX (2.9.2026, fake-contact-from-download-notification bug): a real
    // per-contact WhatsApp message ALWAYS builds a MessagingStyle
    // notification (that's the whole mechanism isGroupConversation()
    // above and PersonPhoneExtractor rely on). Confirmed on-device: a
    // large-file "document download in progress" system notification
    // (title like the download's status text, e.g. 'הורדת מסמך מתבצעת',
    // text = the filename, e.g. 'node-v26.8.1-x64.msi') has NO
    // MessagingStyle at all (extractMessagingStyleFromNotification
    // returns null - logged elsewhere as "GroupDetect: style=false" /
    // "PhoneExtract: לא ניתן היה לחלץ MessagingStyle"), yet its title
    // isn't literally "WhatsApp" so the existing generic-title filter
    // above never caught it - it was being treated as if a real contact
    // named after the download status had messaged, and re-forwarded
    // every time Android reposted the progress notification (once every
    // few seconds while the file downloads), racing past the dedupe
    // window's 3s gap between bursts. Since style==null is already proven
    // (see isGroupConversation above) to reliably mean "not a genuine
    // per-contact/group chat notification", this is the definitive place
    // to drop it, rather than pattern-matching locale-specific status
    // text.
    fun hasNoMessagingStyle(sbn: StatusBarNotification): Boolean {
        return try {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification) == null
        } catch (e: Throwable) {
            // Fail safe: if we can't tell, don't drop a possibly-real
            // message - let the rest of the pipeline handle it as before.
            false
        }
    }
}
