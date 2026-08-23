package com.wabridge.app

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
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

        // In-memory de-duplication: Android can re-post/update the same
        // logical notification multiple times in quick succession (e.g.
        // when a second message arrives before the first is dismissed).
        // We key on (title + text) and ignore exact repeats within a short
        // window, mirroring the practical effect of MacroDroid's
        // "Notification Received" trigger which only fires on genuinely
        // new content.
        private var lastKey: String? = null
        private var lastTimestamp: Long = 0L
        private const val DEDUPE_WINDOW_MS = 3000L

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
        if (dedupeKey == lastKey && (now - lastTimestamp) < DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Ignoring canonical duplicate notification within dedupe window: $dedupeKey")
            EventLog.log("Listener: ⏭️ מתעלם - אותה הודעה כבר התקבלה בפורמט התראה נוסף")
            return
        }
        lastKey = dedupeKey
        lastTimestamp = now

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

    
    private fun postToAppsScript(webAppUrl: String, title: String, text: String, phone: String?, isGroup: Boolean?, postTimeMs: Long, target: String, contentIntent: PendingIntent?) {
        try {
            val body = JSONObject().apply {
                put("title", title)
                put("text", text)
                if (phone != null) put("phone", phone)
                if (isGroup != null) put("isGroup", isGroup)
                attachMediaIfAny(this, text, postTimeMs, target, contentIntent)
            }.toString()

            val url = URL(webAppUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15000
                readTimeout = 15000
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

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
     * Best-effort: if the notification text looks like media (see
     * MediaClassifier), tries to locate the actual file on disk and add
     * mediaBase64/mediaMimeType/mediaFileName fields to the outgoing JSON
     * body. On ANY failure along the way (permission not granted, file
     * not found, file too large) this simply leaves the JSON body
     * untouched - the plain-text notification still gets sent exactly as
     * it always has, media or no media. Never throws.
     */
    private fun attachMediaIfAny(body: JSONObject, text: String, postTimeMs: Long, target: String, contentIntent: PendingIntent?) {
        try {
            val mediaType = MediaClassifier.classify(text)
            if (mediaType == MediaClassifier.MediaType.NONE) return

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
                return
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

            if (found.isEmpty()) {
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

            if (found.isEmpty()) return

            // Filter out anything we've already attached+sent very
            // recently (belt-and-suspenders against the event-level
            // dedupe above missing a case - see recentlySentFiles doc
            // comment) and anything over the size cap.
            val usable = found.filter { fm ->
                val path = fm.file.absolutePath
                when {
                    wasRecentlySent(path, now) -> {
                        EventLog.log("Listener: ⏭️ מדלג - קובץ זה כבר נשלח לאחרונה: ${fm.file.name}")
                        false
                    }
                    fm.file.length() > MEDIA_SIZE_CAP_BYTES -> {
                        val mb = fm.file.length() / (1024 * 1024)
                        Log.w(TAG, "Media file too large to attach: ${fm.file.name} (${mb}MB)")
                        EventLog.log("Listener: ⚠️ קובץ המדיה גדול מדי לצירוף אוטומטי (${mb}MB) - מדלג")
                        false
                    }
                    else -> true
                }
            }
            if (usable.isEmpty()) return

            if (expectedCount > usable.size) {
                EventLog.log("Listener: ℹ️ זוהו $expectedCount פריטים בהודעה אך נמצאו/נשלחו רק ${usable.size} - יתר הפריטים באלבום לא הצליחו להתאתר (מגבלה ידועה, ראו תיעוד ב-WaMediaLocator)")
            }

            // Backward compatible: keep the original single-file fields
            // populated with the first item, so a Code.gs backend that
            // hasn't been updated yet still gets exactly the behaviour it
            // had before this fix (one image, no crash/regression).
            // ADDITIONALLY (new): a "mediaItems" array with every item
            // found, for a backend updated to loop over it and forward
            // them all. Code.gs needs a matching update to actually make
            // use of anything beyond the first item - see handoff notes.
            val itemsJson = org.json.JSONArray()
            usable.forEachIndexed { idx, fm ->
                val bytes = fm.file.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val item = JSONObject().apply {
                    put("mediaBase64", base64)
                    put("mediaMimeType", fm.mimeType)
                    put("mediaFileName", fm.file.name)
                }
                itemsJson.put(item)
                markSent(fm.file.absolutePath, now)

                if (idx == 0) {
                    body.put("mediaBase64", base64)
                    body.put("mediaMimeType", fm.mimeType)
                    body.put("mediaFileName", fm.file.name)
                }
                Log.i(TAG, "Attaching media (${idx + 1}/${usable.size}): ${fm.file.name} (${bytes.size} bytes, ${fm.mimeType})")
                EventLog.log("Listener: 📎 מצרף קובץ מדיה (${idx + 1}/${usable.size}): ${fm.file.name} (${bytes.size / 1024}KB)")
            }
            body.put("mediaItems", itemsJson)
            body.put("mediaItemCount", usable.size)
        } catch (e: Exception) {
            // Deliberately swallow - see doc comment above. A media
            // lookup/read failure must never prevent the plain-text
            // notification from being sent.
            Log.e(TAG, "Failed to attach media (non-fatal, sending text only)", e)
            EventLog.log("Listener: ⚠️ צירוף מדיה נכשל (לא קריטי, נשלח כטקסט): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
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
