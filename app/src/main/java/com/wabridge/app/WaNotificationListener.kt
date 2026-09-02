package com.wabridge.app

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Reads incoming WhatsApp (regular, package "com.whatsapp") notifications
 * and forwards title+text to the SAME Apps Script Web App endpoint that
 * MacroDroid's macro #1 ("בדיקה - נוטיפיקציה") currently posts to.
 *
 * IMPORTANT: this deliberately does NOT try to classify group vs private,
 * strip bidi marks, or do anything else "smart" - all of that logic
 * already lives server-side in Code.gs's classifyNotification(), and this
 * keeps the backend contract identical to what MacroDroid already sends,
 * so Code.gs needs ZERO changes for this phase.
 *
 * Setup required (see MainActivity):
 *   1. User must grant "Notification access" for this app in Android
 *      Settings > Apps > Special app access > Notification access.
 *   2. The Apps Script Web App URL must be entered once in the app's
 *      main screen (stored in SharedPreferences, see Prefs.kt).
 */
class WaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WaBridge"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        // Explicit connection-state heartbeat, so PollingService can log
        // "is the listener actually connected right now" on every poll
        // cycle - not just retroactively inferred from missing messages.
        @Volatile var isConnected = false
        // FIX (26.8.2026, stale-replayed-notifications bug): Android's
        // NotificationListenerService replays every currently-ACTIVE
        // notification to a listener the moment it connects/reconnects -
        // not just genuinely new ones. Confirmed on-device: right after a
        // fresh connect, a burst of notifications about messages sent
        // MINUTES earlier (still sitting in the shade) got processed and
        // emailed as if they were new. sbn.postTime is the ORIGINAL post
        // time (unaffected by the replay), so anything posted before the
        // listener connected is unambiguously a replay, never a genuinely
        // new message - filtered in handleNotification() below.
        @Volatile var listenerConnectedAtMs = 0L

        // In-memory de-duplication: Android can re-post/update the same
        // logical notification multiple times in quick succession (e.g.
        // when a second message arrives before the first is dismissed).
        // We key on (title + text) and ignore exact repeats within a short
        // window, mirroring the practical effect of MacroDroid's
        // "Notification Received" trigger which only fires on genuinely
        // new content.
        //
        // FIX (23.8.2026, interleaved-duplicate bug): a single lastKey/
        // lastTimestamp slot can only catch an EXACT repeat of the
        // IMMEDIATELY PRECEDING notification. Real on-device log (23.8
        // 23:19) showed WhatsApp firing an interleaved A,B,A,B sequence
        // for one logical message - "3 תמונות" then "2 הודעות חדשות" then
        // "3 תמונות" again then "2 הודעות חדשות" again - all within under
        // a second. By the time the 2nd "3 תמונות" arrived, lastKey had
        // already been overwritten by "2 הודעות חדשות", so the equality
        // check silently failed and all 4 got forwarded as if genuinely
        // new (4 emails for 1 real message). Replaced with a short bounded
        // history of recent keys, pruned to DEDUPE_WINDOW_MS, checked by
        // membership instead of equality to only the latest one - this
        // catches a duplicate no matter how many OTHER distinct
        // notifications were interleaved in between, as long as it's
        // still within the window.
        private val recentKeys = ArrayDeque<Pair<String, Long>>()
        private const val DEDUPE_WINDOW_MS = 3000L
        private const val RECENT_KEYS_MAX = 8
        // FIX (23.8.2026, redundant-empty-email bug): matches WhatsApp's
        // bare unread-count summary text, e.g. "‏2 הודעות חדשות" / "2 new
        // messages" - see the filter above for why this is always safe
        // to drop rather than forward.
        private val GENERIC_UNREAD_COUNT_REGEX = Regex("""^\d+\s+(הודעות חדשות|new messages?)$""", RegexOption.IGNORE_CASE)

        // FIX (23.8.2026, multi-media): the text-based dedupe above keys
        // on canonicalTarget+canonicalMessage, but WhatsApp posts SEVERAL
        // notifications with genuinely different text for the exact same
        // incoming album/message - e.g. 'תמונה אחת (1)', then '2 תמונות',
        // then '2 הודעות חדשות' all within ~1 second of each other for one
        // real message. Each of those different texts defeats the text
        // dedupe above and re-runs the full media locate/force-download/
        // attach pipeline independently, which is what caused the same
        // file to be attached and POSTed up to 4 times for a single
        // incoming album (confirmed in EventLog from a real device). This
        // second, WIDER dedupe is keyed only on target+mediaType (ignoring
        // the specific wording) and skips re-running media attachment
        // for near-simultaneous notifications about what's almost
        // certainly the same underlying media event. The plain-text POST
        // itself still goes out for every notification variant exactly as
        // before - only the (expensive, and duplicate-prone) media lookup
        // is skipped on repeats.
        private var lastMediaEventKey: String? = null
        private var lastMediaEventTimestamp: Long = 0L
        private const val MEDIA_EVENT_DEDUPE_WINDOW_MS = 6000L

        // FIX (23.8.2026, multi-media): belt-and-suspenders against the
        // dedupe above ever missing a case - remembers the absolute file
        // paths of media already attached+sent recently, so the exact
        // same physical file can never be attached twice even if two
        // independent media events both resolve to it (e.g. WaMediaLocator
        // picking "the newest file" for two different notifications that
        // both landed after only one new file had actually arrived).
        // Expires entries older than SENT_FILE_MEMORY_MS on each check so
        // this can't grow unbounded or block a genuinely-resent file hours
        // later.
        private val recentlySentFiles = LinkedHashMap<String, Long>()
        private const val SENT_FILE_MEMORY_MS = 60_000L

        @Synchronized
        private fun wasRecentlySent(path: String, now: Long): Boolean {
            recentlySentFiles.entries.removeAll { now - it.value > SENT_FILE_MEMORY_MS }
            return recentlySentFiles.containsKey(path)
        }

        @Synchronized
        private fun markSent(path: String, now: Long) {
            recentlySentFiles[path] = now
        }

        /**
         * Atomically checks whether [key] (target+mediaType) was already
         * processed within MEDIA_EVENT_DEDUPE_WINDOW_MS and, if not,
         * records [now] as the new "last processed" time for it. Kept as
         * a single synchronized check-and-set (rather than separate
         * read/write calls from attachMediaIfAny) to avoid a race between
         * two notifications handled on the same single-thread executor in
         * quick succession.
         */
        @Synchronized
        private fun shouldSkipMediaEvent(key: String, now: Long): Boolean {
            val skip = key == lastMediaEventKey && (now - lastMediaEventTimestamp) < MEDIA_EVENT_DEDUPE_WINDOW_MS
            lastMediaEventKey = key
            lastMediaEventTimestamp = now
            return skip
        }

        // FIX (19.8.2026) - media support, phase 1 (inbound only). Raw
        // file size cap before base64 (which inflates size by ~33%) -
        // keeps the encoded payload comfortably under both Apps Script's
        // doPost request-size ceiling and Gmail's ~25MB attachment limit.
        // Images and voice notes are essentially always well under this;
        // it mainly exists to gracefully skip oversized videos rather
        // than attempt (and likely fail) a huge upload.
        private const val MEDIA_SIZE_CAP_BYTES = 12L * 1024 * 1024 // 12MB
        // FIX (24.8.2026, large-media-as-Drive-link): previously files
        // over MEDIA_SIZE_CAP_BYTES were dropped entirely with no way for
        // the recipient to ever get them. Now MEDIA_SIZE_CAP_BYTES is the
        // threshold for a DIRECT Gmail attachment (unchanged); files
        // between that and this higher hard ceiling are still sent (their
        // base64 bytes included) but flagged "tooLargeToAttachDirectly"
        // so Code.gs uploads them to Drive and puts a share link in the
        // email body instead of attaching them. Only files bigger than
        // THIS are truly dropped - kept generous but still comfortably
        // under Apps Script's doPost payload ceiling even after base64's
        // ~37% size inflation.
        private const val MEDIA_HARD_DROP_CAP_BYTES = 30L * 1024 * 1024 // 30MB

        // FIX (02.9.2026, chunked upload for files over 30MB): a file
        // over MEDIA_HARD_DROP_CAP_BYTES is no longer just dropped - up
        // to this absolute ceiling it's instead sent to Code.gs's new
        // chunked-upload endpoint (startChunkUpload/uploadChunk
        // actions) as a series of small requests, avoiding the single-
        // giant-base64-POST risk the 30MB cap was originally chosen to
        // avoid. Kept generous but still bounded, so a truly huge file
        // (or a corrupt/never-ending read) can't upload indefinitely in
        // the background. MUST match CHUNK_UPLOAD_ABSOLUTE_MAX_BYTES in
        // Code.gs.
        private const val CHUNK_UPLOAD_ABSOLUTE_MAX_BYTES = 150L * 1024 * 1024 // 150MB
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handleNotification(sbn)
        } catch (e: Exception) {
            // Last line of defense: NOTHING in here should ever be
            // allowed to crash the whole app process - a crash here
            // takes down PollingService and the Accessibility service
            // along with it, silently, with zero trace (this is exactly
            // what happened with the actionIntent NPE bug above before
            // it was fixed). Log it and move on instead.
            Log.e(TAG, "Unexpected error handling notification (non-fatal, continuing)", e)
            EventLog.log("Listener: ❌ שגיאה בלתי צפויה בטיפול בהתראה (לא קריטי): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE) return

        // FIX (26.8.2026, stale-replayed-notifications bug): see the
        // listenerConnectedAtMs doc comment above - reject anything
        // posted before this connection began, since it's guaranteed to
        // be a system replay of a pre-existing notification, not a new
        // message.
        if (sbn.postTime < listenerConnectedAtMs) {
            Log.d(TAG, "Ignoring stale replayed notification from before listener connected (postTime=${sbn.postTime}, connectedAt=$listenerConnectedAtMs)")
            EventLog.log("Listener: ⏭️ מתעלם - התראה ישנה שנדחפה מחדש ע\"י המערכת (מלפני חיבור השירות)")
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // Log EVERY WhatsApp notification the listener sees, before any
        // filtering - this is the only way to tell whether the listener
        // is even receiving events at all (vs. being killed/suspended by
        // Android in the background) from the on-screen log, since
        // nothing here was visible in EventLog before this fix.
        EventLog.log("Listener: 📩 התראת וואטסאפ התקבלה: title='$title' text='${text.take(40)}'")

        if (title.isEmpty()) {
            Log.d(TAG, "Ignoring notification with empty title (likely a summary/foreground-service notification)")
            EventLog.log("Listener: ⏭️ מתעלם - כותרת ריקה (כנראה התראת סיכום/מערכת)")
            return
        }

        // WhatsApp posts generic system notifications (reconnecting,
        // syncing, media download progress, etc.) whose title is
        // literally "WhatsApp" itself rather than a contact/group name -
        // these were being incorrectly forwarded as if a contact named
        // "WhatsApp" had messaged. Filter them out.
        if (Utils.stripBidiMarks(title).trim().equals("WhatsApp", ignoreCase = true)) {
            Log.d(TAG, "Ignoring generic WhatsApp system notification (title == 'WhatsApp')")
            EventLog.log("Listener: ⏭️ מתעלם - התראת מערכת גנרית ('WhatsApp')")
            return
        }

        // FIX (2.9.2026, fake-contact-from-download-notification bug): see
        // GroupDetector.hasNoMessagingStyle's doc comment. Catches system
        // notifications (e.g. "downloading a large document" progress)
        // whose title ISN'T literally "WhatsApp" (so the filter above
        // misses them) but that still aren't a real per-contact/group
        // chat message - confirmed via EventLog to be the root cause of a
        // repeating "fake contact" being forwarded and re-sent every few
        // seconds while a large file downloaded (title == the download's
        // status text, text == the filename).
        if (GroupDetector.hasNoMessagingStyle(sbn)) {
            Log.d(TAG, "Ignoring non-chat system notification (no MessagingStyle): title='$title'")
            EventLog.log("Listener: ⏭️ מתעלם - התראת מערכת ללא MessagingStyle (כנראה הורדת קובץ/סנכרון, לא הודעת צ'אט אמיתית) title='$title'")
            return
        }

        // FIX (23.8.2026, redundant-empty-email bug): WhatsApp always
        // fires a second, completely generic "N הודעות חדשות" / "N new
        // messages" notification alongside every real per-chat one -
        // confirmed across EVERY log gathered in this whole debugging
        // session, successful or not: this text never carries usable
        // content (GroupDetect can't classify it, PhoneExtract can never
        // extract a MessagingStyle from it), and it always produced its
        // own empty mediaCount:0 email even when the real notification
        // right next to it was processed correctly. Unlike the dedup
        // fix above (which only catches EXACT repeats), this is a
        // distinct, always-different text that legitimately isn't a
        // duplicate of anything - it's simply never useful on its own,
        // so it's filtered here rather than sent onward as a real event.
        val cleanedForCountCheck = Utils.stripBidiMarks(text).trim()
        if (GENERIC_UNREAD_COUNT_REGEX.matches(cleanedForCountCheck)) {
            Log.d(TAG, "Ignoring generic unread-count notification (no real content): $cleanedForCountCheck")
            EventLog.log("Listener: ⏭️ מתעלם - התראת ספירת הודעות גנרית בלי תוכן ('$cleanedForCountCheck')")
            return
        }

        val now = System.currentTimeMillis()

        // Detect group vs private using Android's own MessagingStyle flag
        // instead of a manually-maintained group-name whitelist - this
        // means brand new groups are recognized automatically, with no
        // code edits ever required.
        //
        // FIX38: this must run BEFORE computing canonicalTarget (moved up
        // from further below) - canonicalTarget's group-name extraction
        // needs to know isGroup up front to correctly strip WhatsApp's
        // "GroupName: SenderName" title format for brand-new groups (see
        // Utils.canonicalTarget's doc comment for the real incident this
        // fixes). Previously isGroup was computed AFTER the reply-action
        // capture below, which used the old (uncorrected) target.
        val rawIsGroup = GroupDetector.isGroupConversation(sbn)

        // FIX42 (bug 2): when Android gives us no flag at all (null) we no
        // longer fall through to the "private" path blindly - if this chat
        // name was ALREADY confirmed as a group by Android at least once,
        // the local cache answers authoritatively. See KnownGroupsCache.
        var isGroup = rawIsGroup
        if (rawIsGroup == null) {
            val cleanTitle = Utils.stripUnreadCountSuffix(Utils.stripBidiMarks(title).trim())
            val cached = KnownGroupsCache.resolveGroupName(this, cleanTitle)
            if (cached != null) {
                isGroup = true
                EventLog.log("Listener: 🧠 הדגל חסר - אך '$cached' מוכרת כקבוצה מהמטמון, מסווג כקבוצה")
            } else {
                EventLog.log("Listener: ⚠️ הדגל isGroupConversation חסר ואין התאמה במטמון עבור '$cleanTitle'")
            }
        }

        val canonicalTarget = Utils.canonicalTarget(title, isGroup)
        if (isGroup == true) KnownGroupsCache.remember(this, canonicalTarget)
        EventLog.log("Listener: 🔍 isGroupConversation=$rawIsGroup (בשימוש: $isGroup) עבור '$canonicalTarget'")

        // FIX43: WhatsApp commonly posts the same group message twice: once
        // as title="Group"/text="Sender: message" and once as
        // title="Group: Sender"/text="message". De-duping the raw title and
        // text could never match those two shapes, so both reached Code.gs.
        // Compare the canonical group and message instead.
        val canonicalMessage = if (isGroup == true) {
            val cleanText = Utils.stripBidiMarks(text).trim()
            val senderSeparator = cleanText.indexOf(": ")
            if (senderSeparator > 0) cleanText.substring(senderSeparator + 2).trim() else cleanText
        } else {
            Utils.stripBidiMarks(text).trim()
        }
        val dedupeKey = "$canonicalTarget|$canonicalMessage"
        synchronized(recentKeys) {
            while (recentKeys.isNotEmpty() && (now - recentKeys.first().second) >= DEDUPE_WINDOW_MS) {
                recentKeys.removeFirst()
            }
            if (recentKeys.any { it.first == dedupeKey }) {
                Log.d(TAG, "Ignoring canonical duplicate notification within dedupe window: $dedupeKey")
                EventLog.log("Listener: ⏭️ מתעלם - אותה הודעה כבר התקבלה בפורמט התראה נוסף")
                return
            }
            recentKeys.addLast(dedupeKey to now)
            while (recentKeys.size > RECENT_KEYS_MAX) {
                recentKeys.removeFirst()
            }
        }

        // Capture the notification's own "Reply" action (RemoteInput),
        // if present - this lets PollingService send the eventual email
        // reply DIRECTLY through WhatsApp's inline-reply mechanism later,
        // with no need to open the app, use Accessibility, or already
        // know this contact's phone number. See ReplyRegistry.
        //
        // IMPORTANT: wrapped defensively - Notification.Action's fields
        // (actionIntent in particular) are nullable in the underlying
        // Java API even though Kotlin's platform-type inference doesn't
        // always flag that. A null actionIntent here previously caused
        // an unguarded NullPointerException that crashed the ENTIRE app
        // process (confirmed via a real "WA Bridge has stopped" crash on
        // an actual incoming message) - taking down PollingService and
        // the Accessibility service along with it, and explaining why
        // messages appeared to just vanish with no log trace at all.
        try {
            val actions = sbn.notification.actions
            if (actions != null) {
                for (action in actions) {
                    val remoteInputs = action?.remoteInputs
                    val actionIntent = action?.actionIntent
                    if (remoteInputs != null && remoteInputs.isNotEmpty() && actionIntent != null) {
                        ReplyRegistry.put(
                            canonicalTarget,
                            ReplyRegistry.ReplyHandle(actionIntent, remoteInputs, now)
                        )
                        Log.i(TAG, "Captured reply action for '$canonicalTarget'")
                        EventLog.log("Listener: 💾 נשמרה פעולת תשובה מהירה עבור '$canonicalTarget'")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a malformed/unexpected notification action shape
            // crash the whole listener - this is a "nice to have" fast
            // path, not something worth risking the entire app for.
            Log.e(TAG, "Failed to capture reply action (non-fatal, continuing)", e)
            EventLog.log("Listener: ⚠️ נכשל לשמור פעולת תשובה מהירה (לא קריטי, ממשיך): ${e.javaClass.simpleName}")
        }

        val webAppUrl = Prefs.getWebAppUrl(this)
        if (webAppUrl.isNullOrBlank()) {
            Log.w(TAG, "No Web App URL configured yet - open the app and set it up. Dropping notification.")
            EventLog.log("Listener: ⚠️ אין כתובת Web App מוגדרת - ההתראה נזרקה")
            return
        }

        // Capture the notification's contentIntent (opens straight into
        // this exact conversation, no invite link needed) - used below
        // to automatically learn a new group's invite link the first
        // time it messages in.
        var contentIntent: PendingIntent? = null
        try {
            contentIntent = sbn.notification.contentIntent
            if (contentIntent != null) {
                OpenIntentRegistry.put(canonicalTarget, contentIntent)
            }
            if (isGroup == true) {
                GroupLinkLearner.maybeLearnGroupLink(this, canonicalTarget, contentIntent, webAppUrl)
            } else if (isGroup == false) {
                PhoneLearnLearner.maybeLearnPhone(this, canonicalTarget, contentIntent, webAppUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture/process contentIntent (non-fatal)", e)
        }

        // Best-effort: extract a phone number from the notification's
        // Person data, if WhatsApp included one (tel: URI). When
        // present, this lets Code.gs permanently remember this contact
        // in the Targets sheet automatically, so replies keep working
        // indefinitely - not just while ReplyRegistry's captured action
        // is still valid (see ReplyRegistry's doc comment for why that
        // alone isn't durable long-term).
        val phone = try {
            PersonPhoneExtractor.extract(sbn)
        } catch (e: Throwable) {
            null
        }

        // FIX42 (bug 1): forward the title WITHOUT Android's unread-count
        // suffix, otherwise Code.gs computes target="Sionov Club (2 הודעות)"
        // server-side and no sheet row / screen search can ever match it.
        val outgoingTitle = Utils.stripUnreadCountSuffix(Utils.stripBidiMarks(title).trim())
        if (outgoingTitle != title) {
            EventLog.log("Listener: ✂️ הוסרה סיומת מונה מהכותרת: '$title' -> '$outgoingTitle'")
        }

        Log.i(TAG, "WhatsApp notification: title='$outgoingTitle' text='$text' phone=$phone isGroup=$isGroup -> forwarding")
        EventLog.log("Listener: ➡️ שולח ל-Apps Script...")
        val postTimeMs = sbn.postTime
        val contentIntentFinal = contentIntent
        executor.execute { postToAppsScript(webAppUrl, outgoingTitle, text, phone, isGroup, postTimeMs, canonicalTarget, contentIntentFinal) }
    }

    
    /**
     * Streams raw file bytes as base64 directly into [writer], never
     * holding more than one chunk in memory. Reads in multiples of 3
     * bytes so every chunk-but-the-last encodes to a clean 4-char-per-3-byte
     * base64 block with no padding - simple string concatenation of the
     * chunks (which is exactly what writing them one after another to the
     * stream does) reconstructs the identical base64 a single encodeToString()
     * call over the whole file would have produced. Only the final,
     * possibly-shorter chunk gets padding, which Base64.encodeToString
     * handles correctly on its own.
     */
    private fun writeStreamedBase64(file: File, writer: java.io.Writer) {
        val buffer = ByteArray(3 * 65536) // 192KB raw per chunk (multiple of 3)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val encoded = android.util.Base64.encodeToString(buffer, 0, read, android.util.Base64.NO_WRAP)
                writer.write(encoded)
            }
        }
    }

    private fun postToAppsScript(webAppUrl: String, title: String, text: String, phone: String?, isGroup: Boolean?, postTimeMs: Long, target: String, contentIntent: PendingIntent?) {
        try {
            // FIX (25.8.2026, OOM crash on large media): see the doc
            // comment above PendingAttachment - this used to build the
            // ENTIRE JSON (including every attachment's base64) as one
            // in-memory String via JSONObject.toString() before writing
            // anything, which crashed with OutOfMemoryError on a real
            // device once an album could include large videos. Now
            // attachMediaIfAny only locates files; the JSON (including
            // every attachment's base64) is written directly to the HTTP
            // connection's OutputStreamWriter below, streamed one small
            // fixed-size chunk at a time via writeStreamedBase64.
            val attachResult = attachMediaIfAny(webAppUrl, title, text, phone, isGroup, postTimeMs, target, contentIntent)
            if (attachResult.skipEntireSend) {
                EventLog.log("Listener: 🚫 השליחה כולה בוטלת - ראה שורת הלוג הקודמת")
                return
            }
            val attachments = attachResult.attachments
            // FIX (02.9.2026, silent-drop-over-30MB bug): if a file was
            // over MEDIA_HARD_DROP_CAP_BYTES, the email used to go out
            // with no trace that a file ever existed. Now append a
            // visible note to the email body: either that the file is
            // being uploaded separately in the background (chunked
            // upload path), or - only for the rare file over even the
            // chunked-upload ceiling - that it couldn't be sent at all.
            // droppedTooLargeNote's own text (set in attachMediaIfAny)
            // already distinguishes the two cases.
            val effectiveText = if (attachResult.droppedTooLargeNote != null) {
                text + "\n\n⚠️ קובץ מדיה: ${attachResult.droppedTooLargeNote}"
            } else {
                text
            }

            val url = URL(webAppUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15000
                // A bit more headroom than the old 15s - streaming
                // several large attachments over a slow connection can
                // legitimately take longer than a plain-text notification
                // ever did.
                readTimeout = 30000
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write("{\"title\":")
                writer.write(JSONObject.quote(title))
                writer.write(",\"text\":")
                writer.write(JSONObject.quote(effectiveText))
                if (phone != null) {
                    writer.write(",\"phone\":")
                    writer.write(JSONObject.quote(phone))
                }
                if (isGroup != null) {
                    writer.write(",\"isGroup\":")
                    writer.write(isGroup.toString())
                }
                if (attachments.isNotEmpty()) {
                    writer.write(",\"mediaItemCount\":")
                    writer.write(attachments.size.toString())
                    writer.write(",\"mediaItems\":[")
                    attachments.forEachIndexed { idx, att ->
                        if (idx > 0) writer.write(",")
                        writer.write("{\"mediaMimeType\":")
                        writer.write(JSONObject.quote(att.mimeType))
                        writer.write(",\"mediaFileName\":")
                        writer.write(JSONObject.quote(att.file.name))
                        writer.write(",\"tooLargeToAttachDirectly\":")
                        writer.write(att.tooLargeToAttachDirectly.toString())
                        writer.write(",\"mediaBase64\":\"")
                        writeStreamedBase64(att.file, writer)
                        writer.write("\"}")
                    }
                    writer.write("]")
                }
                writer.write("}")
            }

            val responseCode = conn.responseCode
            val responseBody = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Log.i(TAG, "POST result: HTTP $responseCode body=$responseBody")
            EventLog.log("Listener: ✅ נשלח, HTTP $responseCode: ${responseBody.take(150)}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to POST notification to Apps Script", e)
            EventLog.log("Listener: ❌ שליחה נכשלה: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * FIX (25.8.2026, OOM crash on large media): a real on-device crash
     * (java.lang.OutOfMemoryError inside JSONObject.toString(), via
     * JSONStringer's internal StringBuilder) confirmed that building the
     * ENTIRE outgoing JSON - including every attachment's full base64
     * string - as one in-memory String before ever writing a byte to the
     * network is not safe on a memory-constrained device, especially
     * once an album can include several large (up to 30MB raw) videos
     * simultaneously. Base64-encoding a file roughly adds another ~1.37x
     * its size as a second in-memory copy, and JSONStringer's
     * StringBuilder.append/expandCapacity can transiently need yet
     * another same-sized copy while growing - for a multi-video album
     * that stacks into a multi-hundred-MB peak on a device the crash log
     * showed had only ~52MB free.
     *
     * attachMediaIfAny() therefore no longer touches the JSON body at
     * all - it only LOCATES files and returns lightweight PendingAttachment
     * references (a File handle + mimeType + a size-tier flag). The
     * actual base64 encoding happens in postToAppsScript(), streamed
     * directly to the HTTP connection's OutputStreamWriter one small
     * fixed-size chunk at a time (writeStreamedBase64Chunked) - so peak
     * extra memory for attaching media is bounded by the chunk size
     * (tens of KB), completely independent of how large the file is or
     * how many files are in the album.
     */
    private data class PendingAttachment(val file: File, val mimeType: String, val tooLargeToAttachDirectly: Boolean)

    /**
     * FIX (27.8.2026, empty duplicate-album email bug): confirmed on-
     * device - WhatsApp fires MULTIPLE separate notifications for the
     * same album as it progressively indexes it ("‏תמונה אחת (1)" then
     * moments later "‏2 תמונות" etc, sometimes even after the true count
     * is much higher). Each one is handled as its own independent job
     * here, and the first one to run typically ends up doing the full
     * force-download/swipe flow and actually sending every real photo.
     * By the time a LATER notification for the very same album gets its
     * turn (the A11y flow is serialized, so a slow first job can delay
     * a second job's own attachMediaIfAny call by 40+ seconds - far
     * longer than MEDIA_EVENT_DEDUPE_WINDOW_MS's 6s window, so that
     * guard alone doesn't catch this), MediaStore correctly finds the
     * same real files again, but wasRecentlySent() filters every single
     * one of them out - leaving a completely empty attachment list. The
     * OLD behaviour still POSTed a "successful" text-only email in that
     * case (mediaCount:0, body text still saying e.g. "2 תמונות"),
     * which is pure noise: nothing new to report, the real album was
     * already fully delivered by the earlier job. This return type lets
     * attachMediaIfAny distinguish that specific "found real files, but
     * every one was already sent moments ago" case from a genuine "no
     * media could be located at all" failure (mediaType matched but
     * found was empty from the start) - the latter is still worth an
     * email, since it's real information that something arrived and
     * couldn't be fetched. Only the former is suppressed entirely.
     */
    // FIX (02.9.2026, silent-drop-over-30MB bug): a file over
    // MEDIA_HARD_DROP_CAP_BYTES used to be filtered out with only an
    // EventLog line on-device - the email itself still went out (title+
    // text only, e.g. a caption like "📄 קיבלת?"), so the recipient had
    // zero way to know a media file had even existed, let alone that it
    // was too large to deliver. droppedTooLargeNote carries a short
    // human-readable summary (name+size) of the first such file so
    // postToAppsScript can append a visible warning to the email body.
    private data class AttachResult(
        val attachments: List<PendingAttachment>,
        val skipEntireSend: Boolean = false,
        val droppedTooLargeNote: String? = null
    )

    private fun attachMediaIfAny(webAppUrl: String, title: String, text: String, phone: String?, isGroup: Boolean?, postTimeMs: Long, target: String, contentIntent: PendingIntent?): AttachResult {
        try {
            val mediaType = MediaClassifier.classify(text)
            if (mediaType == MediaClassifier.MediaType.NONE) return AttachResult(emptyList())

            val now = System.currentTimeMillis()
            val mediaEventKey = "$target|${mediaType.name}"

            // FIX (23.8.2026, multi-media): skip the (expensive, and -
            // for albums - duplicate-prone) locate/force-download/attach
            // pipeline entirely if a media notification for this exact
            // target+type was already processed moments ago. See the
            // companion doc comment on lastMediaEventKey for the real
            // multi-notification scenario this guards against.
            if (shouldSkipMediaEvent(mediaEventKey, now)) {
                EventLog.log("Listener: ⏭️ מדלג על צירוף מדיה - אותו אירוע מדיה (${mediaType.name}) עבור '$target' כבר טופל לפני פחות מ-${MEDIA_EVENT_DEDUPE_WINDOW_MS}ms")
                return AttachResult(emptyList())
            }

            // FIX (23.8.2026, multi-media): parse how many items WhatsApp
            // itself says this message/album contains ("2 תמונות" etc.)
            // so an album isn't silently truncated to a single photo.
            val count = MediaClassifier.extractCount(text)
            EventLog.log("Listener: 🖼️ הודעה מסווגת כמדיה (${mediaType.name}, כמות מזוהה: $count) - מחפש קבצים...")
            // Mutable: may be widened below if the media bubble's own
            // accessibility description reveals a larger true album size
            // than the notification text did (see effectiveCount below).
            var expectedCount = count

            var found = WaMediaLocator.findRecentMediaFiles(this, mediaType, postTimeMs, maxCount = count)

            // FIX (26.8.2026, fast-path-truncates-album bug): confirmed
            // on a real device - a 6-photo album's FIRST processed
            // notification said only "2 תמונות" (WhatsApp's own count
            // text updates progressively as it indexes more of the
            // album), the quick MediaStore scan above found exactly 1
            // file already cached from an earlier residual test, and
            // since `found` wasn't EMPTY, force-download (the ONLY path
            // that ever taps the bubble and discovers the REAL album
            // size via ALBUM_SIZE_IN_DESC_REGEX/ALBUM_SIZE_ITEM_OF_TOTAL_REGEX)
            // never ran at all - the message was sent as "done" with 1
            // of 6 real photos, no error anywhere. Broadened the trigger
            // from "found nothing" to "found fewer than the notification
            // itself claims" so a partially-cached album still gets a
            // real force-download attempt, which is what actually
            // re-detects and widens to the true count.
            if (found.size < count) {
                // FIX (20.8.2026): the file wasn't on disk at all (not a
                // timing/path issue - confirmed via diagnostics that the
                // correct folder exists but is completely empty), most
                // likely because Media Auto-Download is off/limited on
                // this device. Force WhatsApp to actually download the
                // full-quality original by opening the chat and tapping
                // the media bubble, then re-scan with a wider window
                // covering however long that automation took.
                //
                // NOTE: this force-download tap currently only opens ONE
                // media item, so for an album (count > 1) with nothing
                // pre-downloaded, this can still only recover a single
                // image - see MediaDownloadLearner's doc comment. It is
                // NOT a regression versus before this fix; it's the same
                // single-image best-effort as always, just no longer
                // duplicated across notification variants.
                val triggerStart = System.currentTimeMillis()
                val triggered = MediaDownloadLearner.triggerDownloadAndWait(this, target, mediaType, contentIntent)
                if (triggered) {
                    // FIX (23.8.2026, album-size mismatch): the media
                    // bubble's own accessibility description can reveal
                    // the true album size even when the notification
                    // text undercounted it (e.g. "תמונה אחת (1)" for the
                    // first item of a real 5-photo album). Widen the
                    // re-scan to whichever count is larger, so a
                    // misleadingly-small notification-text count no
                    // longer caps the whole album at 1 file.
                    val detectedAlbumSize = MediaDownloadCoordinator.lastDetectedAlbumSize ?: 0
                    val effectiveCount = maxOf(count, detectedAlbumSize)
                    if (detectedAlbumSize > count) {
                        EventLog.log("Listener: 🔢 גודל האלבום שזוהה מתוך המסך ($detectedAlbumSize) גדול מהכמות שזוהתה מטקסט ההתראה ($count) - מרחיב את החיפוש ל-$effectiveCount")
                        expectedCount = effectiveCount
                    }
                    val elapsedSinceTrigger = System.currentTimeMillis() - triggerStart
                    found = WaMediaLocator.findRecentMediaFiles(
                        this, mediaType, System.currentTimeMillis(),
                        maxCount = effectiveCount, matchWindowMs = elapsedSinceTrigger + 5000L
                    )
                }
            }

            if (found.isEmpty()) return AttachResult(emptyList())

            // Filter out anything we've already attached+sent very
            // recently (belt-and-suspenders against the event-level
            // dedupe above missing a case - see recentlySentFiles doc
            // comment) and anything over the hard drop cap. Files between
            // MEDIA_SIZE_CAP_BYTES and MEDIA_HARD_DROP_CAP_BYTES are kept
            // here - they're routed to Drive-link instead of a direct
            // attachment further down, not dropped.
            var droppedTooLargeNote: String? = null
            val usable = found.filter { fm ->
                val path = fm.file.absolutePath
                when {
                    wasRecentlySent(path, now) -> {
                        EventLog.log("Listener: ⏭️ מדלג - קובץ זה כבר נשלח לאחרונה: ${fm.file.name}")
                        false
                    }
                    fm.file.length() > MEDIA_HARD_DROP_CAP_BYTES -> {
                        val mb = fm.file.length() / (1024 * 1024)
                        if (fm.file.length() <= CHUNK_UPLOAD_ABSOLUTE_MAX_BYTES) {
                            // FIX (02.9.2026): instead of dropping this
                            // file, hand it to ChunkedMediaUploader,
                            // which sends it to Code.gs in small pieces
                            // (a separate follow-up email with a Drive
                            // link arrives once that finishes - this
                            // main email still goes out normally, just
                            // with a note that more is coming).
                            Log.i(TAG, "Media file over ${MEDIA_HARD_DROP_CAP_BYTES / (1024*1024)}MB - routing to chunked upload: ${fm.file.name} (${mb}MB)")
                            EventLog.log("Listener: 📦 קובץ מדיה גדול (${mb}MB) - יועלה בחלקים ברקע, מייל נפרד יישלח בסיום")
                            ChunkedMediaUploader.uploadInBackground(webAppUrl, fm.file, fm.mimeType, title, text, phone, isGroup)
                            if (droppedTooLargeNote == null) {
                                droppedTooLargeNote = "${fm.file.name} (${mb}MB) - מועלה בחלקים, יגיע במייל נפרד"
                            }
                        } else {
                            Log.w(TAG, "Media file too large even for chunked upload: ${fm.file.name} (${mb}MB)")
                            EventLog.log("Listener: ⚠️ קובץ המדיה גדול מדי אפילו להעלאה בחלקים (${mb}MB, תקרה: ${CHUNK_UPLOAD_ABSOLUTE_MAX_BYTES / (1024 * 1024)}MB) - מדלג")
                            // FIX (02.9.2026): remember this so the caller can
                            // put a visible warning in the email itself,
                            // instead of the drop being on-device-log-only.
                            // Only the first dropped file's info is kept
                            // (multiple oversized files in one album is rare
                            // and a single clear warning is enough).
                            if (droppedTooLargeNote == null) {
                                droppedTooLargeNote = "${fm.file.name} (${mb}MB) - גדול מדי לשליחה (מעל ${CHUNK_UPLOAD_ABSOLUTE_MAX_BYTES / (1024 * 1024)}MB) - לא נשלח"
                            }
                        }
                        false
                    }
                    else -> true
                }
            }
            if (usable.isEmpty()) {
                // FIX (27.8.2026, empty duplicate-album email bug): see
                // the AttachResult doc comment above. `found` was NOT
                // empty here - real files for this album genuinely exist
                // on disk - but every single one of them was filtered out
                // by wasRecentlySent (or, far more rarely, the hard size
                // cap). That means an earlier job for the same album
                // already delivered everything there is to deliver; this
                // notification is a stale re-announcement of it, not new
                // information, so the whole email is suppressed rather
                // than going out empty with a misleading "2 תמונות"-style
                // body and mediaCount:0.
                val allFilteredWereAlreadySent = found.all { wasRecentlySent(it.file.absolutePath, now) }
                if (allFilteredWereAlreadySent) {
                    EventLog.log("Listener: ⏭️ מדלג על שליחה כולה - כל ${found.size} הקבצים שנמצאו כבר נשלחו לאחרונה (התראה חוזרת/מתעדכנת עבור אותו אלבום)")
                    return AttachResult(emptyList(), skipEntireSend = true)
                }
                // FIX (02.9.2026): still surface the too-large note here -
                // this is exactly the "every file was too big" case (e.g.
                // a single 37MB video), which previously fell through to
                // a plain-text-only email with no explanation.
                return AttachResult(emptyList(), droppedTooLargeNote = droppedTooLargeNote)
            }

            if (expectedCount > usable.size) {
                EventLog.log("Listener: ℹ️ זוהו $expectedCount פריטים בהודעה אך נמצאו/נשלחו רק ${usable.size} - יתר הפריטים באלבום לא הצליחו להתאתר (מגבלה ידועה, ראו תיעוד ב-WaMediaLocator)")
            }

            // Backward compatible fields (flat mediaBase64/mediaMimeType/
            // mediaFileName at the JSON root) are no longer emitted -
            // Code.gs's normalizeMediaItems() always prefers a non-empty
            // "mediaItems" array when present, which this app always
            // sends whenever there's any media, so the flat fallback
            // fields were dead weight that only cost an extra read+encode
            // of the first file for no benefit. See the doc comment
            // above PendingAttachment for why encoding itself is deferred
            // out of this function entirely now.
            val attachments = usable.map { fm ->
                markSent(fm.file.absolutePath, now)
                val tooLargeToAttachDirectly = fm.file.length() > MEDIA_SIZE_CAP_BYTES
                val sizeLabel = if (tooLargeToAttachDirectly) "${fm.file.length() / 1024}KB, יעלה כקישור Drive" else "${fm.file.length() / 1024}KB"
                EventLog.log("Listener: 📎 מצרף קובץ מדיה: ${fm.file.name} ($sizeLabel)")
                PendingAttachment(fm.file, fm.mimeType, tooLargeToAttachDirectly)
            }
            return AttachResult(attachments, droppedTooLargeNote = droppedTooLargeNote)
        } catch (e: Exception) {
            // Deliberately swallow - see doc comment above. A media
            // lookup/read failure must never prevent the plain-text
            // notification from being sent.
            Log.e(TAG, "Failed to attach media (non-fatal, sending text only)", e)
            EventLog.log("Listener: ⚠️ צירוף מדיה נכשל (לא קריטי, נשלח כטקסט): ${e.javaClass.simpleName}: ${e.message}")
            return AttachResult(emptyList())
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        listenerConnectedAtMs = System.currentTimeMillis()
        Log.i(TAG, "Notification listener connected")
        EventLog.log("Listener: 🔌 שירות ההאזנה להתראות התחבר")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "Notification listener disconnected")
        EventLog.log("Listener: ⚠️ שירות ההאזנה להתראות התנתק")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op - we only care about newly posted notifications.
    }
}
