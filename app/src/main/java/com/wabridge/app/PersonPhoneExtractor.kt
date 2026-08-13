package com.wabridge.app

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Isolated in its own class deliberately: referencing android.app.Person
 * directly inside WaNotificationListener caused a NoClassDefFoundError
 * that crashed the WHOLE app on every incoming message - confirmed via
 * a real crash report - because Android verifies a class's bytecode
 * (including referenced types) the first time any of its methods is
 * called, even if the actual Person-touching code path is wrapped in
 * try/catch. Isolating this into its own class means that class only
 * gets loaded/verified when explicitly called, so a missing Person
 * class here can be safely caught instead of crashing the caller.
 */
object PersonPhoneExtractor {
    fun extract(sbn: StatusBarNotification): String? {
        return try {
            val extras = sbn.notification.extras
            val person = extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
            val uri = person?.uri
            if (uri != null && uri.startsWith("tel:", ignoreCase = true)) {
                val phone = uri.substring(4).replace(Regex("[^+0-9]"), "")
                EventLog.log("PhoneExtract: ✅ מספר חולץ מההתראה: $phone")
                phone
            } else {
                EventLog.log("PhoneExtract: ⏭️ אין URI מסוג tel: בנתוני ה-Person (person=$person, uri=$uri)")
                null
            }
        } catch (e: Throwable) {
            // Catches NoClassDefFoundError too (not just Exception) - this
            // was confirmed to actually happen on this device (the
            // android.app.Person class itself is missing at runtime),
            // which is exactly why this is isolated in its own class.
            EventLog.log("PhoneExtract: ❌ נכשל (${e.javaClass.simpleName}: ${e.message}) - כנראה android.app.Person חסר במכשיר הזה")
            null
        }
    }
}
