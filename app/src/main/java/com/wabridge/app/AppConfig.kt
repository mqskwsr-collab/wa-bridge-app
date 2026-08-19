package com.wabridge.app

/**
 * IMPORTANT: KNOWN_GROUPS here must be kept in sync with KNOWN_GROUPS in
 * Code.gs (Apps Script). This mirrors classifyNotification()'s group
 * matching so the app can register a ReplyRegistry entry under the same
 * canonical target name that checkReplies()/Code.gs will later ask for
 * (e.g. always "Sionov stuff", never a raw bundled title like
 * "Sionov stuff: SomeSender").
 */
object AppConfig {
    val KNOWN_GROUPS = listOf("Sionov stuff")
}

object Utils {
    /**
     * Removes invisible Unicode bidi control characters (RLM, LRM, and
     * embedding/override/isolate marks) - same fix already applied in
     * Code.gs's stripBidiMarks() for incoming notification titles, and
     * in WaSendAccessibilityService for button labels.
     */
    fun stripBidiMarks(s: String): String =
        s.replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]"), "")

    /**
     * Returns the canonical target name Code.gs's classifyNotification()
     * would compute for this raw notification title - either a known
     * group's exact name, or the (bidi-stripped) title itself for
     * private chats.
     *
     * FIX38: for GROUP notifications, WhatsApp's own title format is
     * always "GroupName: SenderName" (confirmed via a real incident: a
     * brand-new group not yet in KNOWN_GROUPS produced title
     * "+972 54-796-6357: +972 54-796-6357" - group name and sender both
     * showing as the same unresolved phone number). Previously, for any
     * group NOT in the KNOWN_GROUPS whitelist, this returned the WHOLE
     * "GroupName: SenderName" string as the target - which then never
     * matched anything on-screen (the toolbar only ever shows the bare
     * group name) AND never matched the target Code.gs computes
     * server-side (confirmed via a real HTTP response: server-side
     * target was "+972 54-796-6357", single, not doubled). This silently
     * broke group-link learning for every group except the one
     * hardcoded in KNOWN_GROUPS. Now: whenever isGroup is true and the
     * title contains ": ", split off everything before the first ": "
     * as the group name - this generalizes the KNOWN_GROUPS special
     * case to ALL groups, known or brand new, and matches Code.gs's own
     * behavior so the two sides never disagree on a group's name again.
     */

    /**
     * FIX42 (bug 1): Android/WhatsApp appends an unread-message counter to
     * the notification title when several unread messages pile up from the
     * same chat - e.g. "Sionov Club (2 הודעות)" / "Sionov Club (2 messages)"
     * / "Sionov Club (2)". The on-screen conversation toolbar shows only the
     * bare chat name, so the counter made every screen search miss (real
     * incident: "Learn: קבוצה חדשה 'Sionov Club (2 הודעות)'" followed by a
     * Timeout at step 0), and it also made the target disagree with the one
     * Code.gs computes server-side. Strip the trailing counter always.
     */
    fun stripUnreadCountSuffix(s: String): String {
        var out = s.trim()
        // Repeat: some ROMs stack the counter twice on rapid updates.
        while (true) {
            val stripped = out.replace(
                Regex("""\s*\(\s*\d+\s*(הודעות|הודעה|messages?|new messages?)?\s*\)\s*$""", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (stripped == out || stripped.isEmpty()) return out
            out = stripped
        }
    }

    fun canonicalTarget(rawTitle: String, isGroup: Boolean? = null): String {
        val title = stripUnreadCountSuffix(stripBidiMarks(rawTitle).trim())
        if (isGroup == true) {
            val sepIndex = title.indexOf(": ")
            if (sepIndex > 0) return stripUnreadCountSuffix(title.substring(0, sepIndex).trim())
            return title
        }
        val matchedGroup = AppConfig.KNOWN_GROUPS.find { title == it || title.startsWith("$it:") }
        return matchedGroup ?: title
    }
}
