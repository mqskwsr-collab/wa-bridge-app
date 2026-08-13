package com.wabridge.app

import android.app.PendingIntent

/**
 * Mirrors ReplyRegistry, but captures the notification's contentIntent
 * (the action fired when tapping the notification body, not a specific
 * action button) - WhatsApp's contentIntent always opens directly into
 * that exact conversation, group or private, with NO invite link
 * needed. This lets the system open a brand-new group on its own the
 * first time it messages in, so WaSendAccessibilityService can learn
 * (read off-screen) the group's invite link automatically - see
 * GroupLinkLearner.
 *
 * Same durability caveat as ReplyRegistry: only valid while the
 * capturing app process is alive and the notification hasn't been
 * dismissed/replaced.
 */
object OpenIntentRegistry {
    private val map = HashMap<String, PendingIntent>()

    @Synchronized
    fun put(target: String, intent: PendingIntent) {
        map[target] = intent
    }

    @Synchronized
    fun get(target: String): PendingIntent? = map[target]
}
