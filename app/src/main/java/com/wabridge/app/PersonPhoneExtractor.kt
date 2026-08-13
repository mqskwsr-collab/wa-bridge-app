package com.wabridge.app

import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Isolated in its own class deliberately (same reasoning applies as
 * before). Uses the ANDROIDX COMPAT MessagingStyle/Person classes
 * (androidx.core.app.Person), NOT the OS framework's android.app.Person
 * - this is the key fix: the framework class was confirmed missing at
 * runtime on this device (NoClassDefFoundError), but the compat classes
 * are bundled INSIDE our own app's APK, not provided by the OS, so they
 * can never be "missing" regardless of the device's quirks. GroupDetect
 * already proved NotificationCompat.MessagingStyle extraction works
 * fine on this device (used for group detection) - this reuses the
 * exact same extraction, then reads the sender Person's URI from it
 * instead of touching the OS's Person class at all.
 */
object PersonPhoneExtractor {
    fun extract(sbn: StatusBarNotification): String? {
        return try {
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification)
            if (style == null) {
                EventLog.log("PhoneExtract: ⏭️ לא ניתן היה לחלץ MessagingStyle")
                return null
            }

            // Try the most recent message's sender first (for private
            // chats, this is the contact who messaged); fall back to the
            // style's own "user" (less likely to be the contact, but
            // checked just in case).
            val candidates = listOfNotNull(
                style.messages.lastOrNull()?.person,
                style.user
            )

            for (person in candidates) {
                val uri = person?.uri
                if (uri != null && uri.startsWith("tel:", ignoreCase = true)) {
                    val phone = uri.substring(4).replace(Regex("[^+0-9]"), "")
                    EventLog.log("PhoneExtract: ✅ מספר חולץ (דרך androidx Person): $phone")
                    return phone
                }
            }

            EventLog.log("PhoneExtract: ⏭️ נמצאו ${candidates.size} מועמדי Person אך אף אחד לא עם URI מסוג tel: (uris=${candidates.map { it?.uri }})")
            null
        } catch (e: Throwable) {
            EventLog.log("PhoneExtract: ❌ נכשל (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }
}
