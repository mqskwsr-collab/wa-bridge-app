package com.wabridge.app

/**
 * Best-effort classification of an incoming WhatsApp notification as
 * carrying an image, a video, a voice note, or plain text - based purely
 * on the notification's text field, since that's all we have to go on
 * before deciding whether to even attempt locating a media file on disk.
 *
 * KNOWN LIMITATION (please read before "fixing" false negatives): when a
 * photo/video is sent WITH A CAPTION, WhatsApp sometimes shows the
 * caption text alone in the notification, with no "📷 Photo" / "תמונה"
 * indicator at all - there is no reliable way to distinguish that from a
 * genuine plain-text message using only the notification text. This
 * classifier will misclassify those as MediaType.NONE and the message
 * will be forwarded as plain text only, same as today. This is an
 * accepted gap for the first version, not a bug to chase.
 */
object MediaClassifier {

    enum class MediaType {
        IMAGE,
        VIDEO,
        VOICE_NOTE,
        NONE
    }

    // Hebrew is the primary device language in use (see EventLog history),
    // but English keywords are kept too since WhatsApp's wording can vary
    // by account/region settings independent of the phone's own language.
    private val IMAGE_MARKERS = listOf("תמונה", "photo", "image", "\uD83D\uDCF7", "\uD83D\uDCF8")
    private val VIDEO_MARKERS = listOf("וידאו", "סרטון", "video", "\uD83C\uDFA5", "\uD83D\uDCF9")
    private val VOICE_MARKERS = listOf("הודעה קולית", "voice message", "audio message", "\uD83C\uDFA4")

    fun classify(rawText: String): MediaType {
        val text = Utils.stripBidiMarks(rawText).trim()
        if (text.isEmpty()) return MediaType.NONE

        // Check voice/video before image: some of the emoji/keyword sets
        // could theoretically overlap in future WhatsApp wording changes,
        // and voice notes are the most distinctive (fewest false-positive
        // risk), followed by video, so check in that order.
        if (VOICE_MARKERS.any { text.contains(it, ignoreCase = true) }) return MediaType.VOICE_NOTE
        if (VIDEO_MARKERS.any { text.contains(it, ignoreCase = true) }) return MediaType.VIDEO
        if (IMAGE_MARKERS.any { text.contains(it, ignoreCase = true) }) return MediaType.IMAGE
        return MediaType.NONE
    }

    // FIX (23.8.2026, multi-media): WhatsApp's own summary notification
    // text tells us how many items are in the album ("2 תמונות" / "2
    // photos" / "5 פריטי מדיה"), or explicitly says "one" ("תמונה אחת
    // (1)"). Parsing this lets the caller know it should try to fetch
    // more than a single file instead of silently dropping the rest of
    // the album. Deliberately conservative: falls back to 1 (the
    // existing, known-working behaviour) whenever nothing is parseable,
    // rather than guessing.
    private val EXPLICIT_SINGLE_MARKERS = listOf("אחת", "אחד", "one")
    private val DIGIT_REGEX = Regex("""\d+""")

    fun extractCount(rawText: String): Int {
        val text = Utils.stripBidiMarks(rawText).trim()
        if (text.isEmpty()) return 1

        // "תמונה אחת (1)" - prefer the parenthesised digit if present,
        // since it's unambiguous; otherwise fall back to the Hebrew/
        // English word for "one".
        val match = DIGIT_REGEX.find(text)
        if (match != null) {
            val n = match.value.toIntOrNull() ?: 1
            return if (n in 1..50) n else 1 // sanity cap - never trust an absurd parsed count
        }
        if (EXPLICIT_SINGLE_MARKERS.any { text.contains(it, ignoreCase = true) }) return 1
        return 1
    }
}
