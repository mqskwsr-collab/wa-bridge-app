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
        // FIX (04.9.2026, mixed-album-loses-photos bug): confirmed
        // on-device - a real album containing both photos AND a video
        // ("11 תמונות, סרטון וידאו 1") was classified as pure VIDEO
        // (classify() checked video keywords first and stopped there),
        // which downstream (WaMediaLocator) meant ONLY the video's
        // MediaStore collection (Video) was ever queried - all 11
        // photos in the same message were never even searched for, let
        // alone attached. A dedicated MIXED type lets callers search
        // BOTH collections instead of picking one and silently losing
        // the other.
        MIXED,
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

        // Check voice first (most distinctive, fewest false-positive
        // risk). Then check whether BOTH image and video markers are
        // present - a mixed album, e.g. the camera emoji from the photo
        // count PLUS "סרטון"/"video" from the video count in the same
        // notification text - before falling back to single-type
        // detection, so a mix never gets mistaken for one type alone.
        if (VOICE_MARKERS.any { text.contains(it, ignoreCase = true) }) return MediaType.VOICE_NOTE
        val hasVideo = VIDEO_MARKERS.any { text.contains(it, ignoreCase = true) }
        val hasImage = IMAGE_MARKERS.any { text.contains(it, ignoreCase = true) }
        if (hasVideo && hasImage) return MediaType.MIXED
        if (hasVideo) return MediaType.VIDEO
        if (hasImage) return MediaType.IMAGE
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
