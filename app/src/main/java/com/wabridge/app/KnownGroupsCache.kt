package com.wabridge.app

import android.content.Context

/**
 * FIX42 (bug 2): Local persistent cache of confirmed group names, used to
 * stabilize classification when Android's isGroupConversation flag is
 * unavailable (null) for a subsequent notification from an already-known
 * group.
 *
 * Real incident: the first notification from the brand-new group
 * "Sionov Club" arrived in a shape where
 * MessagingStyle.extractMessagingStyleFromNotification() returned no
 * usable flag (isGroupConversation=null). With null, the listener fell
 * through to the "private" path and the server created a row with
 * target="Sionov Club" classified as a private contact - which can never
 * be resolved (no phone number exists for a group), producing the endless
 * "נמצאה שורה ממתינה ... אך אין phoneOrLink - מדלג" poll warnings.
 *
 * Fix: every time a notification IS positively confirmed as a group
 * (isGroupConversation == true), remember that group's canonical name
 * here. On any later notification where the flag is null, a cache hit is
 * treated as authoritative "this is a group". Nothing is ever guessed:
 * only names that Android itself confirmed as groups at least once are
 * stored. The cache is additive and persistent across restarts.
 */
object KnownGroupsCache {
    private const val PREFS = "wa_bridge_known_groups"
    private const val KEY = "confirmed_group_names"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun norm(name: String): String =
        Utils.stripBidiMarks(name).trim().lowercase()

    fun remember(ctx: Context, groupName: String) {
        val key = norm(groupName)
        if (key.isEmpty()) return
        try {
            val current = prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()
            if (current.contains(key)) return
            prefs(ctx).edit().putStringSet(KEY, current + key).apply()
            EventLog.log("GroupCache: 💾 נשמרה קבוצה מאומתת '$groupName'")
        } catch (e: Throwable) {
            EventLog.log("GroupCache: ⚠️ נכשלה שמירת '$groupName' (${e.javaClass.simpleName})")
        }
    }

    fun isKnownGroup(ctx: Context, candidate: String): Boolean {
        val key = norm(candidate)
        if (key.isEmpty()) return false
        return try {
            (prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()).contains(key)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Given a raw (already bidi-stripped, count-suffix-stripped) title,
     * returns the cached group name it belongs to, or null. Handles both
     * the bare "GroupName" title shape and WhatsApp's bundled
     * "GroupName: SenderName" shape.
     */
    fun resolveGroupName(ctx: Context, cleanTitle: String): String? {
        if (isKnownGroup(ctx, cleanTitle)) return cleanTitle
        val sep = cleanTitle.indexOf(": ")
        if (sep > 0) {
            val prefix = cleanTitle.substring(0, sep).trim()
            if (isKnownGroup(ctx, prefix)) return prefix
        }
        return null
    }

    fun all(ctx: Context): Set<String> =
        try {
            prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()
        } catch (e: Throwable) {
            emptySet()
        }
}
