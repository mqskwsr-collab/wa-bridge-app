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
                uri.substring(4).replace(Regex("[^+0-9]"), "")
            } else {
                null
            }
        } catch (e: Throwable) {
            // Catches NoClassDefFoundError too (not just Exception) -
            // this is exactly the failure mode that was crashing the
            // whole app before this class was isolated.
            null
        }
    }
}
