package com.wabridge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Replaces MacroDroid's fixed-coordinate "UI Interaction Click(905,842)"
 * style automation. Instead of guessing pixel positions and hoping the
 * screen has finished loading after a blind Wait, this:
 *
 *  1. Only starts looking once Android tells us WhatsApp's window
 *     actually changed (onAccessibilityEvent, TYPE_WINDOW_STATE_CHANGED) -
 *     no fixed delay guessing.
 *  2. Searches the actual accessibility node tree for the message entry
 *     box (an EditText) and the send button (by resource id or
 *     content-description), retrying for a few seconds if the screen is
 *     still rendering - this survives WhatsApp UI/version/resolution
 *     changes that would silently break fixed coordinates.
 *  3. Sets the message text directly via ACTION_SET_TEXT (no clipboard
 *     paste needed) and performs a real ACTION_CLICK on the send node.
 *
 * This only activates when SendCoordinator has a job queued by
 * PollingService - it does nothing and reads nothing otherwise, even
 * though Android requires accessibility services to declare broad
 * capabilities.
 */
class WaSendAccessibilityService : AccessibilityService() {

    /**
     * FIX (23.8.2026, missed-message root cause): every automated action
     * (send reply / force-download media / learn group link / learn
     * phone) opens a specific WhatsApp chat and then, before this fix,
     * simply left it open on screen with no code ever navigating away.
     * WhatsApp does NOT post a system notification for a new incoming
     * message on a chat that is already open on screen - so if a real
     * message from that exact contact/group arrived while the chat sat
     * open from an earlier automated action, WA Bridge never saw ANY
     * trace of it (not even the raw "📩 notification received" log line
     * that fires for every other WhatsApp notification), producing a
     * silent, un-diagnosable total miss. Confirmed as the likely cause
     * of a real missed-photo report on 23.8.2026: the log showed
     * MediaDownloadLearner opening a chat at 12:28, then a real message
     * to that same chat at 12:47 left zero trace anywhere in the log.
     *
     * Fix: after every terminal result (success OR failure/timeout - a
     * failed action can just as easily leave a chat open), press the
     * system Home button so WhatsApp goes to the background and stops
     * owning "the currently open chat". This restores normal WhatsApp
     * notification behaviour for every chat between automated actions.
     * A short delay lets the just-performed action (e.g. the send
     * verification read) finish first; performGlobalAction itself is
     * fire-and-forget and never throws.
     */
    private fun goHomeToCloseWhatsAppChat() {
        Handler(Looper.getMainLooper()).postDelayed({
            val ok = performGlobalAction(GLOBAL_ACTION_HOME)
            EventLog.log("A11y: 🏠 יציאה למסך הבית אחרי סיום הפעולה (סוגר צ'אט פתוח כדי לא לחסום התראות עתידיות)" + if (!ok) " - performGlobalAction החזיר false" else "")
        }, 400)
    }

    companion object {
        private const val TAG = "WaBridgeA11y"
        private const val SEARCH_TIMEOUT_MS = 15000L
        private const val SEARCH_INTERVAL_MS = 400L
        private const val MAX_TAP_ATTEMPTS = 8
        private const val LEARN_TIMEOUT_MS = 18000L
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 14000L
        // FIX (23.8.2026, full-album swipe): a multi-image album needs
        // far more than the single-image budget above - one swipe +
        // save-menu + write-to-disk cycle per extra item realistically
        // costs several seconds. Once the true album size is known (see
        // ALBUM_SIZE_IN_DESC_REGEX), the per-job dynamic timeout is
        // extended by this much for every item beyond the first, capped
        // at MEDIA_DOWNLOAD_TIMEOUT_MS_MAX so a huge/misdetected count
        // can never hang the flow indefinitely.
        private const val MEDIA_DOWNLOAD_TIMEOUT_PER_EXTRA_ITEM_MS = 6000L
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS_MAX = 60000L
        // How often to re-check the media folder while the full-image
        // viewer is open, waiting for WhatsApp to finish writing the
        // real file to disk (see stage 1 doc comment - replaces the old
        // fixed MEDIA_DOWNLOAD_SETTLE_MS blind wait).
        private const val MEDIA_DOWNLOAD_POLL_INTERVAL_MS = 1000L
        private val MEMBERS_COUNT_REGEX = Regex("""\d+\s*(חברים|חברות|משתתפים|members|participants)""", RegexOption.IGNORE_CASE)
        // Matches WhatsApp's "jump to newest message" floating button
        // across the Hebrew/English variants observed in practice.
        private val JUMP_TO_LAST_MESSAGE_REGEX = Regex("""(עבור אל ההודעה האחרונה|עבור להודעה האחרונה|scroll to (the )?last message|go to last message)""", RegexOption.IGNORE_CASE)
        // Chrome/overlay icons that are NOT message bubbles but can look
        // like one to a bare "clickable ImageView" search (confirmed via
        // on-device logs: the "jump to last message" FAB and the
        // overflow "More options" menu both matched before this
        // denylist existed). Belt-and-suspenders alongside stage -1's
        // scroll-to-bottom fix, in case that doesn't fully resolve it on
        // some device/WhatsApp version.
        private val NON_MEDIA_ICON_DESC_REGEX = Regex("""(עבור אל ההודעה האחרונה|עבור להודעה האחרונה|scroll to (the )?last message|go to last message|more options|options menu|camera|attach|voice message|emoji|search|send)""", RegexOption.IGNORE_CASE)
        // FIX (23.8.2026, album-size mismatch): matches WhatsApp's "show
        // all N media items" bubble description in Hebrew/English, to
        // recover the true album size when the triggering notification's
        // own text undercounted it. See MediaDownloadCoordinator's
        // lastDetectedAlbumSize doc comment for the full story.
        private val ALBUM_SIZE_IN_DESC_REGEX = Regex("""(?:הצגת כל|showing all|view all)\s*(\d+)\s*(?:פריטי\s*(?:ה)?מדיה|media items?)""", RegexOption.IGNORE_CASE)
        // FIX (23.8.2026, second album-size format): on-device log
        // (23.8 18:24) showed WhatsApp doesn't always use the "show all
        // N media items" collapsed-bubble format above - sometimes
        // (when the chat is already scrolled such that individual album
        // items are directly visible, not collapsed) the tapped bubble's
        // own description is instead the per-item "showing image X of Y"
        // form, e.g. "‏הצגת ‏תמונה, 3 מתוך 3" / "showing image 3 of 3".
        // The Y here is just as reliable a total-album-size signal as
        // the "show all" form - actually more so, since it's on the
        // EXACT bubble being tapped rather than a sibling summary bubble.
        private val ALBUM_SIZE_ITEM_OF_TOTAL_REGEX = Regex("""(?:מתוך|of)\s*(\d+)""", RegexOption.IGNORE_CASE)
        // FIX (21.8.2026): the FULL on-device tree dump (see FIX46)
        // revealed the real culprit for why imgCount stayed flat at 2
        // the whole timeout - the photo bubble is classed as
        // android.widget.Button, NOT ImageView at all:
        //   class=android.widget.Button desc='הגדלת התמונה' bounds=...
        // ("הגדלת התמונה" = "enlarge the image"). Matching on class name
        // was never going to work reliably here - WhatsApp has already
        // fooled this exact class-name-substring approach twice now (see
        // findEditText's older fix, and the media bubble itself). This
        // regex instead matches the STABLE content-description directly,
        // regardless of what class WhatsApp decides to wrap it in.
        // Includes a video-bubble guess (unconfirmed, but likely
        // analogous - "הפעלת הסרטון"/"play video") since MediaClassifier
        // also handles VIDEO; safe to leave in even if never confirmed,
        // since it only narrows what counts as a match.
        private val MEDIA_BUBBLE_DESC_REGEX = Regex("""(הגדלת התמונה|enlarge (the )?image|play video|הפעלת הסרטון)""", RegexOption.IGNORE_CASE)
        // FIX (22.8.2026): the post-tap tree dump (see FIX50) confirmed
        // a REAL screen navigation happens (back/star/forward/edit/more-
        // options icons, reaction row - genuinely WhatsApp's full-screen
        // media viewer, not just an inline zoom). But its top bar has NO
        // visible save/download icon - only "More options" (⋮, English
        // label despite the rest of the UI being Hebrew, per the
        // on-device dump). On current WhatsApp, "Save to gallery" lives
        // INSIDE that overflow menu, not as a top-level icon - we were
        // opening the viewer correctly but never actually triggering the
        // save action, which is exactly why polling for the file always
        // timed out even with the correct screen open.
        private val MORE_OPTIONS_DESC_REGEX = Regex("""(more options|options menu|אפשרויות נוספות|עוד אפשרויות)""", RegexOption.IGNORE_CASE)
        private val SAVE_TO_GALLERY_REGEX = Regex("""(save to gallery|save|download|שמירה בגלריה|שמור בגלריה|שמירה|הורדה)""", RegexOption.IGNORE_CASE)
        // FIX (23.8.2026, full-album swipe): label of the container node
        // that holds the currently-displayed full-screen image (seen in
        // the on-device dump as a ViewGroup with label='הגדלת התמונה'
        // and bounds spanning almost the whole screen) - used to know
        // WHERE on screen to swipe. Falls back to a generous full-width
        // band if this isn't found for some reason.
        private val IMAGE_VIEWER_CONTAINER_LABEL_REGEX = Regex("""(הגדלת התמונה|enlarge (the )?image)""", RegexOption.IGNORE_CASE)
    }

    // 0=not attempted, 1=tapped "More options", waiting for menu, 2=done
    // (either tapped a save item, or gave up after one attempt - either
    // way only tried once per download job).
    private var saveMenuAttemptStep = 0

    private val handler = Handler(Looper.getMainLooper())
    private var searching = false
    private var searchStartTime = 0L
    private var lastDiagnosticDumpTime = 0L
    private var lastSendDumpTime = 0L
    // Group invite links open an intermediate WhatsApp landing screen
    // (a preview with a "הודעה"/"Message" button) before the actual
    // conversation screen appears - MacroDroid's old macro #2 had a
    // dedicated click for exactly this. We only want to click it once
    // per job.
    private var clickedIntermediateScreen = false

    // --- Group-link learning state (separate from the send flow above) ---
    private var learning = false
    private var learnStartTime = 0L
    private var learnStage = 0 // 0=find/click group header, 1=find/click "Invite via link", 2=read link text
    private var lastLearnDumpTime = 0L
    private var lastLearnScrollTime = 0L
    private var triedMembersRowClick = false

    // --- Phone-number learning state (separate from both flows above) ---
    private var phoneLearning = false
    private var phoneLearnStartTime = 0L
    private var phoneLearnStage = 0 // 0=find/click contact header, 1=scan for a phone-shaped string
    private var lastPhoneLearnDumpTime = 0L

    // --- Media forced-download state (separate from all flows above) ---
    private var downloadingMedia = false
    private var mediaDownloadStartTime = 0L
    private var mediaDownloadStage = 0 // -1=scroll to bottom if needed, 0=find/tap most recent media bubble, 1=settle+back out, 2=swipe to next album item
    private var mediaTapTime = 0L
    private var lastMediaDownloadDumpTime = 0L
    private var scrolledToLatestMessage = false
    private var hasDumpedFullMediaTree = false
    private var hasDumpedPostTapTree = false
    private var hasDumpedMenuTree = false
    private var hasDumpedAfterSaveTapTree = false
    // FIX (23.8.2026, full-album swipe): extends the single-bubble force
    // -download flow into a real per-item loop through WhatsApp's
    // full-screen album viewer instead of stopping after one image.
    // dynamicMediaDownloadTimeoutMs starts equal to the normal single-
    // image budget and is widened once the true album size is known
    // (see ALBUM_SIZE_IN_DESC_REGEX / MediaDownloadCoordinator.
    // lastDetectedAlbumSize). swipesAttempted/maxSwipes bound how many
    // times stage 2 will try to advance to the next item. swipeLeftToRight
    // is a best guess (unconfirmed on a real device - WhatsApp's RTL
    // Hebrew layout may reverse which physical direction means "next")
    // that self-corrects: if 2 consecutive swipes produce no new file at
    // all, it flips direction once and tries again, so a wrong initial
    // guess still recovers rather than silently swiping the wrong way
    // for the whole album.
    private var dynamicMediaDownloadTimeoutMs = MEDIA_DOWNLOAD_TIMEOUT_MS
    private var swipesAttempted = 0
    private var maxSwipes = 0
    private var swipeLeftToRight = true
    private var directionFlipped = false
    private var noProgressSwipeCount = 0
    private var lastKnownFoundCount = 0
    private var currentViewerBounds: Rect? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (SendCoordinator.hasPendingJob() && !searching) {
            Log.i(TAG, "WhatsApp window state changed and a job is pending - starting search")
            EventLog.log("A11y: חלון וואטסאפ השתנה, מתחיל לחפש שדה הודעה+שליחה")
            searching = true
            searchStartTime = System.currentTimeMillis()
            clickedIntermediateScreen = false
            lastDiagnosticDumpTime = 0L
            lastSendDumpTime = 0L
            handler.post(searchRunnable)
        }

        if (LearnCoordinator.hasPendingLearn() && !learning) {
            Log.i(TAG, "WhatsApp window state changed and a group-link learn is pending - starting")
            EventLog.log("A11y-Learn: חלון וואטסאפ השתנה, מתחיל תהליך למידת קישור")
            learning = true
            learnStartTime = System.currentTimeMillis()
            learnStage = 0
            lastLearnDumpTime = 0L
            lastLearnScrollTime = 0L
            triedMembersRowClick = false
            handler.post(learnRunnable)
        }

        if (PhoneLearnCoordinator.hasPendingLearn() && !phoneLearning) {
            Log.i(TAG, "WhatsApp window state changed and a phone-learn is pending - starting")
            EventLog.log("A11y-PhoneLearn: חלון וואטסאפ השתנה, מתחיל תהליך למידת מספר")
            phoneLearning = true
            phoneLearnStartTime = System.currentTimeMillis()
            phoneLearnStage = 0
            lastPhoneLearnDumpTime = 0L
            handler.post(phoneLearnRunnable)
        }

        if (MediaDownloadCoordinator.hasPendingDownload() && !downloadingMedia) {
            Log.i(TAG, "WhatsApp window state changed and a media-download trigger is pending - starting")
            EventLog.log("A11y-MediaDownload: חלון וואטסאפ השתנה, מתחיל תהליך הכרחת הורדה")
            downloadingMedia = true
            mediaDownloadStartTime = System.currentTimeMillis()
            // FIX (21.8.2026): start at stage -1, not 0. Log evidence
            // (21.8.2026 01:13 run) showed the chat can open WITHOUT
            // being scrolled to the newest message - WhatsApp then shows
            // a floating "עבור אל ההודעה האחרונה" / "Go to last
            // message" jump button, which is itself a clickable
            // FrameLayout containing an ImageView icon. Since it sits
            // low on screen (and the real photo bubble is off-screen,
            // not even in the tree yet), the old bottommost-ImageView
            // search grabbed the jump button instead of the photo -
            // explaining exactly why the chat opened, something got
            // tapped, but no file ever appeared. Stage -1 taps that
            // jump button first (if present) and waits for the list to
            // actually settle at the bottom before stage 0 searches for
            // the real bubble.
            mediaDownloadStage = -1
            scrolledToLatestMessage = false
            hasDumpedFullMediaTree = false
            hasDumpedPostTapTree = false
            hasDumpedMenuTree = false
            hasDumpedAfterSaveTapTree = false
            saveMenuAttemptStep = 0
            lastMediaDownloadDumpTime = 0L
            dynamicMediaDownloadTimeoutMs = MEDIA_DOWNLOAD_TIMEOUT_MS
            swipesAttempted = 0
            maxSwipes = 0
            swipeLeftToRight = true
            directionFlipped = false
            noProgressSwipeCount = 0
            lastKnownFoundCount = 0
            currentViewerBounds = null
            handler.post(mediaDownloadRunnable)
        }
    }

    private val searchRunnable = object : Runnable {
        override fun run() {
            val job = SendCoordinator.current
            if (job == null) {
                searching = false
                return
            }

            val elapsed = System.currentTimeMillis() - searchStartTime
            if (elapsed > SEARCH_TIMEOUT_MS) {
                Log.w(TAG, "Timed out searching for entry/send fields")
                EventLog.log("A11y: ❌ Timeout - לא נמצא שדה הודעה/שליחה תוך ${SEARCH_TIMEOUT_MS / 1000} שניות")
                searching = false
                SendCoordinator.reportResult(SendCoordinator.Result.TIMEOUT)
                goHomeToCloseWhatsAppChat()
                return
            }

            val root = rootInActiveWindow
            if (root == null) {
                EventLog.log("A11y: ⚠️ rootInActiveWindow=null (אין חלון פעיל זמין כרגע)")
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            if (elapsed - lastDiagnosticDumpTime > 2000L) {
                lastDiagnosticDumpTime = elapsed
                val pkg = root.packageName ?: "?"
                val cls = root.className ?: "?"
                val texts = mutableListOf<String>()
                collectTexts(root, texts, maxCount = 12)
                val editCount = countMatchingClass(root, "Edit")
                val windowCount = windows?.size ?: -1
                EventLog.log("A11y: 🔍 [+${elapsed / 1000}s] חלון=$pkg/$cls | חלונות=$windowCount | nodes עם 'Edit' ב-class=$editCount | טקסטים: ${texts.joinToString(" | ").ifBlank { "(אין טקסטים כלל)" }}")
            }

            // Search across ALL open windows, not just the active one -
            // WhatsApp's compose bar could conceivably live in a
            // separate accessibility window (e.g. overlay/IME-adjacent
            // region) that rootInActiveWindow alone wouldn't include.
            var entryNode = findEditText(root)
            var sendNode = findSendButton(root)
            if (entryNode == null) {
                windows?.forEach { w ->
                    val wRoot = w.root ?: return@forEach
                    if (entryNode == null) entryNode = findEditText(wRoot)
                    if (sendNode == null) sendNode = findSendButton(wRoot)
                }
            }

            if (entryNode == null) {
                // No message box yet - this could be a group invite's
                // intermediate landing screen (a preview with a
                // "הודעה"/"Message" button, not the conversation itself).
                // Try clicking that once, then keep searching for entry.
                if (!clickedIntermediateScreen) {
                    val intermediateBtn = findClickableByText(root, listOf("הודעה", "Message", "message"))
                    if (intermediateBtn != null) {
                        Log.i(TAG, "Found intermediate landing screen button - clicking it")
                        EventLog.log("A11y: נמצא מסך ביניים, לוחץ על כפתור \"הודעה\"")
                        clickedIntermediateScreen = true
                        intermediateBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                // Screen probably still loading (or this is not the chat
                // screen yet) - keep retrying until timeout.
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            if (sendNode == null) {
                if (elapsed - lastSendDumpTime > 1900L) {
                    lastSendDumpTime = elapsed
                    // Entry found but send button not yet - dump clickable
                    // node info to help identify it if this keeps failing.
                    val clickables = mutableListOf<String>()
                    collectClickableInfo(root, clickables, maxCount = 15)
                    EventLog.log("A11y: ✏️ entry נמצא (class=${entryNode?.className}), אין עדיין כפתור שליחה - זה צפוי לפני הקלדה. כפתורים: ${clickables.joinToString(" | ")}")
                }
                // WhatsApp only shows the Send button once the entry field
                // has non-empty text - before typing, that slot is
                // occupied by a microphone (voice message) button
                // instead. So we don't wait for sendNode here at all -
                // we go ahead and type now; the send button is searched
                // for AGAIN after typing, below.
            }

            // Copy to a non-null local val so Kotlin's smart-cast works
            // correctly inside the nested closure below.
            val entryNodeFinal: AccessibilityNodeInfo = entryNode!!

            Log.i(TAG, "Found entry node - typing text (send button appears after typing)")
            EventLog.log("A11y: נמצא entry, מקליד טקסט...")
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                job.text
            )
            val typed = entryNodeFinal.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!typed) {
                Log.w(TAG, "ACTION_SET_TEXT failed on entry node")
                EventLog.log("A11y: ⚠️ הקלדה נכשלה, מנסה שוב...")
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            // Now that text was entered, WhatsApp should swap the mic
            // button for a Send button - retry a few times over ~3s to
            // let the UI update, rather than checking only once.
            searchForSendButtonAfterTyping(attempt = 0)
        }
    }

    /**
     * After typing, the Send button may take a moment to render (it
     * replaces the mic button). Retries up to 6 times, 400ms apart
     * (~2.4s total), rather than checking only once.
     */
    private fun searchForSendButtonAfterTyping(attempt: Int) {
        val job = SendCoordinator.current
        if (job == null) {
            // The job was cleared/overwritten from under us (e.g. by a
            // concurrent attempt) - abort rather than risk clicking send
            // with unknown/stale text in the box.
            Log.w(TAG, "Job disappeared mid-send - aborting without clicking")
            EventLog.log("A11y: ⚠️ העבודה נעלמה תוך כדי (כנראה כפילות) - מבטל בלי ללחוץ")
            searching = false
            return
        }

        if (attempt >= MAX_TAP_ATTEMPTS) {
            Log.w(TAG, "Send button/tap still not working after $attempt attempts")
            val root = rootInActiveWindow
            val entryNowText = root?.let { findEditText(it) }?.text?.toString() ?: "?"
            val clickables = mutableListOf<String>()
            if (root != null) collectClickableInfo(root, clickables, maxCount = 20)
            EventLog.log("A11y: ❌ כפתור שליחה/הקשה לא הצליחו אחרי $attempt ניסיונות. תוכן entry='$entryNowText' | כפתורים: ${clickables.joinToString(" | ")}")
            searching = false
            SendCoordinator.reportResult(SendCoordinator.Result.FAILED_NO_SEND_BUTTON)
            goHomeToCloseWhatsAppChat()
            return
        }

        val freshRoot = rootInActiveWindow
        val currentEntry = freshRoot?.let { findEditText(it) }
        val currentEntryText = stripBidiMarks(currentEntry?.text?.toString() ?: "").trim()
        val expectedEntryText = stripBidiMarks(job.text).trim()
        val freshSend = freshRoot?.let { findSendButton(it) }

        if (freshSend == null) {
            EventLog.log("A11y: ✏️ [ניסיון $attempt] עדיין אין כפתור שליחה, ממתין...")
            handler.postDelayed({ searchForSendButtonAfterTyping(attempt + 1) }, 500)
            return
        }

        // Safety check: only tap Send if the box actually contains the
        // text we intended to send. This guards against tapping send on
        // stale/empty/wrong content if state got clobbered by another
        // concurrent attempt.
        if (currentEntryText != expectedEntryText) {
            EventLog.log("A11y: ⚠️ [ניסיון $attempt] תוכן התיבה ('$currentEntryText') לא תואם לצפוי - לא לוחץ, ממתין")
            handler.postDelayed({ searchForSendButtonAfterTyping(attempt + 1) }, 500)
            return
        }

        val bounds = Rect()
        freshSend.getBoundsInScreen(bounds)
        val clickableInfo = "class=${freshSend.className} clickable=${freshSend.isClickable} bounds=$bounds"
        EventLog.log("A11y: 🖱️ [ניסיון $attempt] לוחץ ACTION_CLICK על כפתור ($clickableInfo)")
        val clickResult = freshSend.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clickResult) {
            EventLog.log("A11y: ⚠️ [ניסיון $attempt] ACTION_CLICK החזיר false, ממתין ומנסה שוב")
            handler.postDelayed({ searchForSendButtonAfterTyping(attempt + 1) }, 600)
            return
        }

        // ACTION_CLICK returning true only means Android delivered the
        // click event - it does NOT guarantee WhatsApp actually acted on
        // it, so verify for real. IMPORTANT: WhatsApp's compose box does
        // NOT necessarily go fully blank after a successful send - it
        // was confirmed (via screenshot showing 4 real successful sends)
        // that it reverts to showing the placeholder/hint text (e.g.
        // "הודעה") which is NOT an empty string, so checking
        // isBlank() alone produced false negatives (real sends being
        // misreported as failures, causing repeated re-sends). The
        // correct check is simply: does the box still contain OUR
        // message? If not - whether it's truly empty or showing a
        // placeholder - the send succeeded.
        handler.postDelayed({
            val checkRoot = rootInActiveWindow
            val checkEntry = checkRoot?.let { findEditText(it) }
            val textNow = stripBidiMarks(checkEntry?.text?.toString() ?: "").trim()
            val expectedText = stripBidiMarks(job.text).trim()
            if (textNow != expectedText) {
                Log.i(TAG, "Compose box no longer contains our message ('$textNow') - send confirmed")
                EventLog.log("A11y: ✅ התיבה כבר לא מכילה את ההודעה שלנו (מציגה '$textNow') - השליחה אומתה בפועל")
                searching = false
                SendCoordinator.reportResult(SendCoordinator.Result.SUCCESS)
                goHomeToCloseWhatsAppChat()
            } else {
                Log.w(TAG, "Compose box still has our exact text after click - not actually sent")
                EventLog.log("A11y: ❌ [ניסיון $attempt] התיבה עדיין מכילה את אותה הודעה בדיוק אחרי הלחיצה - לא נשלח באמת, מנסה שוב")
                searchForSendButtonAfterTyping(attempt + 1)
            }
        }, 700)
    }

    /**
     * Dispatches a real synthesized tap (touch down+up, ~80ms) at the
     * given screen coordinates via the AccessibilityService gesture API.
     * NOTE: kept for reference/future use, but NOT currently called -
     * diagnostics showed dispatchGesture gets cancelled by the system
     * 100% of the time in this NoxPlayer environment (even with 1s
     * spacing between attempts, ruling out overlap as the cause), so
     * ACTION_CLICK + real-outcome verification is used instead (see
     * searchForSendButtonAfterTyping).
     */
    @Suppress("unused")
    private fun performTapGesture(x: Float, y: Float, onSuccess: () -> Unit, onCancelled: () -> Unit): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onSuccess()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onCancelled()
            }
        }, null)
    }

    /**
     * Automatically learns a group's invite link: clicks the group name
     * in the toolbar (opens Group Info), finds and clicks the "Invite
     * via link" option, then reads the link text off-screen - the same
     * steps a human would take, performed by the app. Diagnostic dumps
     * are included since the exact screen text/layout for this flow is
     * unverified until tested for real (same iterative pattern used to
     * get the send flow working).
     */
    private val learnRunnable = object : Runnable {
        override fun run() {
            val job = LearnCoordinator.current
            if (job == null) {
                learning = false
                return
            }

            val elapsed = System.currentTimeMillis() - learnStartTime
            if (elapsed > LEARN_TIMEOUT_MS) {
                Log.w(TAG, "Timed out learning group link (stage=$learnStage)")
                EventLog.log("A11y-Learn: ❌ Timeout בשלב $learnStage")
                learning = false
                LearnCoordinator.reportResult(LearnCoordinator.Result.TIMEOUT)
                goHomeToCloseWhatsAppChat()
                return
            }

            val root = rootInActiveWindow
            if (root == null) {
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            if (elapsed - lastLearnDumpTime > 2000L) {
                lastLearnDumpTime = elapsed
                val texts = mutableListOf<String>()
                collectTexts(root, texts, maxCount = 15)
                EventLog.log("A11y-Learn: 🔍 [שלב $learnStage, +${elapsed / 1000}s] טקסטים: ${texts.joinToString(" | ")}")
            }

            when (learnStage) {
                0 -> {
                    // Click the group name in the toolbar to open Group Info.
                    // The exact group name text should appear on-screen and
                    // be clickable (it's the conversation title).
                    val header = findClickableByText(root, listOf(job.target))
                    if (header != null) {
                        Log.i(TAG, "Found group header - clicking to open Group Info")
                        EventLog.log("A11y-Learn: נמצאה כותרת הקבוצה, לוחץ לפתיחת פרטי קבוצה")
                        header.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        learnStage = 1
                    }
                    handler.postDelayed(this, SEARCH_INTERVAL_MS)
                }
                1 -> {
                    // Look for the "Invite via link" option - try several
                    // known label variants (English + Hebrew). Diagnostics
                    // confirmed the actual button here is just plain
                    // "הזמנה" (not "הזמנה בקישור" or similar longer
                    // phrasing that was originally guessed) - added as the
                    // primary candidate.
                    val inviteCandidates = listOf(
                        "הזמנה", "Invite",
                        "Invite via link", "Invite to group via link",
                        "הזמנה לקבוצה באמצעות קישור", "הזמנה בקישור", "הזמנה דרך קישור"
                    )
                    val inviteBtn = findClickableByText(root, inviteCandidates)
                        ?: findClickableContaining(root, listOf("קישור", "link", "Link", "הזמנה", "Invite"))
                    if (inviteBtn != null) {
                        Log.i(TAG, "Found 'Invite' option - clicking")
                        EventLog.log("A11y-Learn: נמצא \"הזמנה\", לוחץ")
                        inviteBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        learnStage = 2
                    } else if (!triedMembersRowClick) {
                        // Not found on the current Group Info screen. FIX
                        // (confirmed via logs for 'משפוחה'): a plain
                        // ACTION_SCROLL_FORWARD on this long settings page
                        // scrolls by a full "page" - for a 5-member group
                        // that one jump lands straight in the middle of the
                        // member list, skipping clean over the "Invite via
                        // link" row (which sits just above the member list,
                        // right after "Add participant"). Confirmed in logs:
                        // the dump right after the very first scroll already
                        // showed 4 members, with "הזמנה" never having
                        // appeared in any dump in between.
                        //
                        // Instead of trying to calibrate scroll distance,
                        // tap the "X members"/"X חברים" row, which opens
                        // WhatsApp's dedicated Participants screen - there,
                        // "Invite via link" sits right near the top
                        // (immediately below "Add participant"), regardless
                        // of member count, so it's reliably on-screen
                        // without any scrolling at all. Only try this once
                        // per learn attempt; if it doesn't pan out, fall
                        // back to the old blind-scroll behavior below.
                        triedMembersRowClick = true
                        val membersRow = findClickableMatchingRegex(root, MEMBERS_COUNT_REGEX)
                        if (membersRow != null) {
                            Log.i(TAG, "Clicking members-count row to open Participants screen")
                            EventLog.log("A11y-Learn: \"הזמנה\" לא נמצא, לוחץ על מספר החברים כדי לפתוח את מסך המשתתפים")
                            membersRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    } else {
                        // Fallback: blind scroll of the current screen,
                        // throttled so we don't spam scroll events every
                        // 400ms and overshoot further than necessary.
                        if (elapsed - lastLearnScrollTime > 1200L) {
                            lastLearnScrollTime = elapsed
                            val scrollable = findScrollableNode(root)
                            if (scrollable != null) {
                                Log.i(TAG, "'Invite' option not visible yet - scrolling down")
                                EventLog.log("A11y-Learn: \"הזמנה\" לא נמצא במסך הנוכחי, גולל למטה")
                                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            }
                        }
                    }
                    handler.postDelayed(this, SEARCH_INTERVAL_MS)
                }
                2 -> {
                    // Look for a node whose text IS the invite link itself.
                    val linkTexts = mutableListOf<String>()
                    collectTexts(root, linkTexts, maxCount = 30)
                    val link = linkTexts.firstOrNull { it.contains("chat.whatsapp.com") }
                    if (link != null) {
                        Log.i(TAG, "Found group invite link: $link")
                        EventLog.log("A11y-Learn: ✅ קישור נמצא: $link")
                        learning = false
                        LearnCoordinator.reportResult(LearnCoordinator.Result.SUCCESS, link)
                        goHomeToCloseWhatsAppChat()
                    } else {
                        handler.postDelayed(this, SEARCH_INTERVAL_MS)
                    }
                }
            }
        }
    }

    /**
     * EXPERIMENTAL phone-number learning (new, 14.8.2026 - mirrors the
     * proven group-link learnRunnable pattern, applied to private
     * contacts): stage 0 clicks the contact's name in the toolbar to
     * open "Contact Info"; stage 1 scans that screen's text for a
     * phone-number-shaped string. Untested live as of writing - watch
     * the "A11y-PhoneLearn:" diagnostic dumps closely on first use,
     * exactly like every other new automation flow in this project
     * needed iteration before working.
     */
    private val phoneLearnRunnable = object : Runnable {
        override fun run() {
            val job = PhoneLearnCoordinator.current
            if (job == null) {
                phoneLearning = false
                return
            }

            val elapsed = System.currentTimeMillis() - phoneLearnStartTime
            if (elapsed > LEARN_TIMEOUT_MS) {
                Log.w(TAG, "Timed out learning phone number (stage=$phoneLearnStage)")
                EventLog.log("A11y-PhoneLearn: ❌ Timeout בשלב $phoneLearnStage")
                phoneLearning = false
                PhoneLearnCoordinator.reportResult(PhoneLearnCoordinator.Result.TIMEOUT)
                goHomeToCloseWhatsAppChat()
                return
            }

            val root = rootInActiveWindow
            if (root == null) {
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            if (elapsed - lastPhoneLearnDumpTime > 2000L) {
                lastPhoneLearnDumpTime = elapsed
                val texts = mutableListOf<String>()
                collectTexts(root, texts, maxCount = 15)
                EventLog.log("A11y-PhoneLearn: 🔍 [שלב $phoneLearnStage, +${elapsed / 1000}s] טקסטים: ${texts.joinToString(" | ")}")
            }

            when (phoneLearnStage) {
                0 -> {
                    val header = findClickableByText(root, listOf(job.target))
                    if (header != null) {
                        Log.i(TAG, "Found contact header - clicking to open Contact Info")
                        EventLog.log("A11y-PhoneLearn: נמצאה כותרת איש הקשר, לוחץ לפתיחת פרטי איש קשר")
                        header.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        phoneLearnStage = 1
                    }
                    handler.postDelayed(this, SEARCH_INTERVAL_MS)
                }
                1 -> {
                    val texts = mutableListOf<String>()
                    collectTexts(root, texts, maxCount = 40)
                    // Phone-shaped: starts with + and has enough digits -
                    // matches the format WhatsApp itself displays
                    // (confirmed via observed titles like
                    // "+972 54-648-9005").
                    val phonePattern = Regex("""\+\d{1,4}[\d\s\-]{6,}""")
                    val phoneText = texts.firstOrNull { phonePattern.containsMatchIn(it) }
                    if (phoneText != null) {
                        val digitsOnly = phonePattern.find(phoneText)!!.value.replace(Regex("[^+0-9]"), "")
                        Log.i(TAG, "Found phone-shaped text: $phoneText -> $digitsOnly")
                        EventLog.log("A11y-PhoneLearn: ✅ מספר נמצא: '$phoneText' -> $digitsOnly")
                        phoneLearning = false
                        PhoneLearnCoordinator.reportResult(PhoneLearnCoordinator.Result.SUCCESS, digitsOnly)
                        goHomeToCloseWhatsAppChat()
                    } else {
                        handler.postDelayed(this, SEARCH_INTERVAL_MS)
                    }
                }
            }
        }
    }

    /**
     * FIX (20.8.2026): forces WhatsApp to write the full-quality media
     * file to disk when Media Auto-Download is off/limited (see
     * MediaDownloadCoordinator's doc comment for the diagnosis). Stage 0
     * finds the most recent media bubble (an ImageView-class node
     * closest to the bottom of the screen, i.e. the newest message in
     * the chat) and taps it - opening WhatsApp's full-screen viewer is
     * exactly what triggers a manual-style download of the original
     * file. Stage 1 just waits a moment for that download to actually
     * finish writing to disk, then presses back so WhatsApp doesn't sit
     * on the viewer indefinitely.
     */
    private val mediaDownloadRunnable = object : Runnable {
        override fun run() {
            val job = MediaDownloadCoordinator.current
            if (job == null) {
                downloadingMedia = false
                return
            }

            val elapsed = System.currentTimeMillis() - mediaDownloadStartTime
            if (elapsed > dynamicMediaDownloadTimeoutMs) {
                Log.w(TAG, "Timed out forcing media download (stage=$mediaDownloadStage)")
                EventLog.log("A11y-MediaDownload: ❌ Timeout בשלב $mediaDownloadStage (תקציב זמן: ${dynamicMediaDownloadTimeoutMs / 1000}s)")
                // FIX (21.8.2026): if the timeout fires while stage 1's
                // new polling loop is still waiting (i.e. we're still
                // sitting in WhatsApp's full-screen photo viewer), back
                // out before giving up - otherwise the app is left
                // stuck displaying the image indefinitely.
                if (mediaDownloadStage == 1) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                downloadingMedia = false
                MediaDownloadCoordinator.reportResult(MediaDownloadCoordinator.Result.TIMEOUT)
                goHomeToCloseWhatsAppChat()
                return
            }

            val root = rootInActiveWindow
            if (root == null) {
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            when (mediaDownloadStage) {
                -1 -> {
                    // Look for WhatsApp's "jump to last message" floating
                    // button (Hebrew/English variants seen in practice).
                    // If present, the newest message isn't rendered yet -
                    // tap it, give the list a moment to actually scroll
                    // and render the bottom, then move on to stage 0.
                    // If absent, we're already at the bottom - proceed
                    // immediately, no wasted wait.
                    val jumpButton = findClickableMatchingRegex(root, JUMP_TO_LAST_MESSAGE_REGEX)
                    if (jumpButton != null && !scrolledToLatestMessage) {
                        EventLog.log("A11y-MediaDownload: 🔽 נמצא כפתור \"עבור להודעה האחרונה\" - הצ'אט לא היה גלול לתחתית, לוחץ")
                        jumpButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        scrolledToLatestMessage = true
                        mediaDownloadStage = 0
                        handler.postDelayed(this, 900L)
                    } else {
                        scrolledToLatestMessage = true
                        mediaDownloadStage = 0
                        handler.post(this)
                    }
                }
                0 -> {
                    if (elapsed - lastMediaDownloadDumpTime > 2000L) {
                        lastMediaDownloadDumpTime = elapsed
                        val imgCount = countMatchingClass(root, "ImageView")
                        EventLog.log("A11y-MediaDownload: 🔍 [+${elapsed / 1000}s] nodes עם 'ImageView' ב-class=$imgCount")
                    }
                    // DIAGNOSTIC (21.8.2026): imgCount stayed FLAT across
                    // an entire 12s timeout on-device (both runs, 2-3
                    // nodes, never increasing) even after the stage -1
                    // scroll-to-bottom fix confirmed firing. That rules
                    // out "not rendered yet" as the remaining problem -
                    // the photo bubble is on screen but its clickable
                    // element apparently isn't ImageView-classed at all.
                    // We already hit this exact pattern once before with
                    // the message EditText (see findEditText's comment)
                    // - WhatsApp doesn't always use the class name you'd
                    // expect. So: one-time full dump of every distinct
                    // class name present, plus every clickable node
                    // regardless of class, so we can see the bubble's
                    // REAL class instead of guessing again. Fires once,
                    // ~4s in, not every 2s cycle (would flood the log).
                    if (!hasDumpedFullMediaTree && elapsed > 4000L) {
                        hasDumpedFullMediaTree = true
                        dumpFullNodeTree(root)
                    }
                    val bubble = findBottommostImageNode(root)
                    if (bubble != null) {
                        Log.i(TAG, "Found media bubble - tapping to force download")
                        // DIAGNOSTIC (21.8.2026): log exactly WHAT is
                        // about to be tapped - className/text/content-
                        // description/resource-id/bounds - so we can
                        // directly confirm or rule out from the log
                        // whether this is really the photo bubble or
                        // (suspected) a bottom-toolbar icon like camera/
                        // attach, which also sit in the accessibility
                        // tree as clickable ImageView-class nodes with a
                        // large bounds.bottom.
                        val bRect = Rect()
                        bubble.getBoundsInScreen(bRect)
                        EventLog.log(
                            "A11y-MediaDownload: 🎯 יעד הלחיצה - class=${bubble.className} " +
                                "text='${bubble.text}' desc='${bubble.contentDescription}' " +
                                "id=${bubble.viewIdResourceName} bounds=$bRect"
                        )
                        // DIAGNOSTIC (21.8.2026): also log the other
                        // top candidates that were NOT picked (by
                        // bounds.bottom) - if the toolbar-icon theory is
                        // right, we should see camera/attach/mic-like
                        // nodes clustered at the very bottom of the
                        // screen, likely ABOVE the actual photo bubble.
                        logImageNodeCandidates(root)
                        // FIX (23.8.2026, album-size mismatch): the bubble's
                        // own content-description sometimes states the
                        // REAL album size ("הצגת כל 5 פריטי המדיה" /
                        // "showing all 5 media items"), which is more
                        // trustworthy than the triggering notification's
                        // text (which can say "one photo (1)" for the
                        // first-arriving item of a larger album). Grab it
                        // here, before the tap, so attachMediaIfAny can
                        // widen its search instead of stopping at 1.
                        val descText = bubble.contentDescription?.toString() ?: ""
                        val detectedAlbumN = ALBUM_SIZE_IN_DESC_REGEX.find(descText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: ALBUM_SIZE_ITEM_OF_TOTAL_REGEX.find(descText)?.groupValues?.get(1)?.toIntOrNull()
                        detectedAlbumN?.let { n ->
                            EventLog.log("A11y-MediaDownload: 🔢 גודל אלבום אמיתי זוהה מתוך תיאור הבועה: $n")
                            MediaDownloadCoordinator.reportDetectedAlbumSize(n)
                            maxSwipes = (n - 1).coerceAtLeast(0)
                            dynamicMediaDownloadTimeoutMs = (MEDIA_DOWNLOAD_TIMEOUT_MS + maxSwipes * MEDIA_DOWNLOAD_TIMEOUT_PER_EXTRA_ITEM_MS)
                                .coerceAtMost(MEDIA_DOWNLOAD_TIMEOUT_MS_MAX)
                            EventLog.log("A11y-MediaDownload: ⏱️ תקציב הזמן הורחב ל-${dynamicMediaDownloadTimeoutMs / 1000}s עבור אלבום בגודל $n (עד $maxSwipes החלקות)")
                        }
                        EventLog.log("A11y-MediaDownload: נמצאה בועת מדיה, לוחץ לפתיחה (הכרחת הורדה)")
                        bubble.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        mediaDownloadStage = 1
                        mediaTapTime = System.currentTimeMillis()
                        handler.postDelayed(this, MEDIA_DOWNLOAD_POLL_INTERVAL_MS)
                    } else {
                        handler.postDelayed(this, SEARCH_INTERVAL_MS)
                    }
                }
                1 -> {
                    // DIAGNOSTIC (21.8.2026): on-device log (21.8 13:01)
                    // showed the correct bubble tapped, 12s of active
                    // polling, and STILL zero new files - meanwhile the
                    // 5 newest files in the folder were all HOURS old
                    // (deltas of 21222s/55523s/etc from the notification
                    // time), meaning no new file was written at all
                    // during the whole wait. That raises a real
                    // possibility that 'הגדלת התמונה' ("enlarge the
                    // image") is just a pinch-zoom on the EXISTING
                    // thumbnail bitmap in place, not a navigation to
                    // WhatsApp's actual full-screen media-viewer Activity
                    // that would trigger a real download. One-time full
                    // tree dump right after the tap (reusing FIX46's
                    // dumpFullNodeTree) shows exactly what's on screen
                    // now, so we can tell whether a new screen actually
                    // opened (different node structure/back button/etc.)
                    // or whether we're still looking at the same chat
                    // screen with nothing new.
                    if (!hasDumpedPostTapTree) {
                        hasDumpedPostTapTree = true
                        EventLog.log("A11y-MediaDownload: 🌳 אבחון מסך אחרי הלחיצה (מיד):")
                        dumpFullNodeTree(root)
                    }
                    // FIX (22.8.2026): the post-tap dump confirmed a real
                    // viewer screen opens, but its top bar has no visible
                    // save/download icon - only "More options" (⋮). This
                    // taps that overflow menu once, looks for a save/
                    // gallery-ish menu item, and taps it - THIS is what
                    // actually triggers writing the file to public
                    // storage, which is why polling below always timed
                    // out before this existed even with the correct
                    // screen genuinely open.
                    if (saveMenuAttemptStep == 0) {
                        val moreOptions = findClickableMatchingRegex(root, MORE_OPTIONS_DESC_REGEX)
                        if (moreOptions != null) {
                            EventLog.log("A11y-MediaDownload: ⋮ לוחץ על \"More options\" כדי לחפש אפשרות שמירה")
                            moreOptions.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            saveMenuAttemptStep = 1
                            handler.postDelayed(this, 700L)
                            return
                        } else {
                            EventLog.log("A11y-MediaDownload: ⚠️ לא נמצא כפתור \"More options\" - מדלג ישר להמתנה")
                            saveMenuAttemptStep = 2
                        }
                    } else if (saveMenuAttemptStep == 1) {
                        // DIAGNOSTIC (22.8.2026): the previous attempt
                        // DID find and tap something matching the save
                        // regex, but logged text='null' - the regex
                        // search climbs from the matching descendant up
                        // to its nearest clickable ANCESTOR (a common
                        // pattern: a menu row is a plain clickable
                        // container wrapping a separate TextView with
                        // the actual label), so the container itself
                        // legitimately has no text of its own to log.
                        // That means we genuinely don't know what got
                        // tapped, and 9+ more seconds of polling
                        // afterward still found nothing new. Dump the
                        // OPEN menu's full tree first (one-time, reusing
                        // FIX46's dumpFullNodeTree) so we can see the
                        // real item labels with our own eyes instead of
                        // guessing at broader/narrower regexes blind.
                        if (!hasDumpedMenuTree) {
                            hasDumpedMenuTree = true
                            EventLog.log("A11y-MediaDownload: 🌳 אבחון תפריט \"More options\" (פתוח כרגע):")
                            dumpFullNodeTree(root)
                        }
                        val matchedText = findDescendantTextMatchingRegex(root, SAVE_TO_GALLERY_REGEX)
                        val saveItem = findClickableMatchingRegex(root, SAVE_TO_GALLERY_REGEX)
                        if (saveItem != null) {
                            EventLog.log("A11y-MediaDownload: 💾 נמצא פריט תפריט תואם טקסט '$matchedText' - לוחץ")
                            saveItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } else {
                            EventLog.log("A11y-MediaDownload: ⚠️ תפריט \"More options\" נפתח אך לא נמצא בו פריט שמירה - סוגר")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        saveMenuAttemptStep = 2
                        handler.postDelayed(this, 700L)
                        return
                    }
                    // DIAGNOSTIC (23.8.2026): one more one-time dump,
                    // right after tapping whatever matched inside the
                    // overflow menu - shows whether the menu actually
                    // closed (confirming the tap registered at all) and
                    // what screen we're looking at afterward, in case the
                    // tap silently did nothing or navigated somewhere
                    // unexpected.
                    if (!hasDumpedAfterSaveTapTree) {
                        hasDumpedAfterSaveTapTree = true
                        EventLog.log("A11y-MediaDownload: 🌳 אבחון מסך אחרי לחיצה על פריט התפריט:")
                        dumpFullNodeTree(root)
                    }
                    // FIX (21.8.2026): was a blind fixed 3s wait then
                    // unconditional "back" - on-device log (21.8 10:25)
                    // showed the CORRECT bubble now gets tapped (desc=
                    // 'הגדלת התמונה', a real photo, not a toolbar icon)
                    // but the media folder was STILL empty afterward.
                    // Most likely explanation: WhatsApp hadn't actually
                    // finished downloading the full-quality file within
                    // that fixed 3s, and navigating away (GLOBAL_ACTION_
                    // BACK) while the viewer is still loading likely
                    // cancels the in-flight download outright. Now
                    // actively polls the real media folder (via the same
                    // WaMediaLocator used by the normal, non-forced path)
                    // every second, and only backs out once the file is
                    // actually confirmed on disk - or once the overall
                    // MEDIA_DOWNLOAD_TIMEOUT_MS budget runs out, in which
                    // case it still backs out (so WhatsApp doesn't sit on
                    // the viewer forever) but reports TIMEOUT instead of
                    // a false SUCCESS, so the caller knows not to trust a
                    // rescan blindly.
                    val elapsedSinceTap = System.currentTimeMillis() - mediaTapTime
                    // FIX (23.8.2026, album-size mismatch): previously
                    // backed out the INSTANT a single file appeared,
                    // which - now that we tap knowing the real album size
                    // up front (see ALBUM_SIZE_IN_DESC_REGEX above) -
                    // was needlessly cutting WhatsApp's background
                    // prefetch of neighbouring album images short. Now
                    // waits for as many files as the bubble's own
                    // description said the album contains (falls back to
                    // 1 if that wasn't detected), still bounded by the
                    // overall MEDIA_DOWNLOAD_TIMEOUT_MS budget - backs
                    // out early if the budget is nearly spent even with
                    // fewer than expected, so this can never overrun the
                    // outer timeout and get stuck on the viewer.
                    val expectedAlbumSize = (MediaDownloadCoordinator.lastDetectedAlbumSize ?: 1).coerceAtLeast(1)
                    val found = WaMediaLocator.findRecentMediaFiles(
                        this@WaSendAccessibilityService,
                        job.mediaType,
                        mediaTapTime,
                        maxCount = expectedAlbumSize,
                        matchWindowMs = elapsedSinceTap + 5000L
                    )
                    val remainingBudgetMs = dynamicMediaDownloadTimeoutMs - (System.currentTimeMillis() - mediaDownloadStartTime)
                    val mustBackOutNow = found.isNotEmpty() && remainingBudgetMs < 2500L

                    // FIX (23.8.2026, full-album swipe): track whether the
                    // most recent swipe actually produced a new file, so a
                    // wrong direction guess can self-correct instead of
                    // burning every remaining swipe attempt uselessly.
                    if (found.size > lastKnownFoundCount) {
                        noProgressSwipeCount = 0
                    } else if (swipesAttempted > 0) {
                        noProgressSwipeCount++
                    }
                    lastKnownFoundCount = found.size

                    val canSwipeForMore = found.size < expectedAlbumSize &&
                        swipesAttempted < maxSwipes &&
                        remainingBudgetMs > 3500L &&
                        !mustBackOutNow

                    when {
                        found.size >= expectedAlbumSize || mustBackOutNow -> {
                            EventLog.log("A11y-MediaDownload: ✅ נמצאו ${found.size}/$expectedAlbumSize קבצים בדיסק אחרי ${elapsedSinceTap / 1000}s ו-$swipesAttempted החלקות - חוזר אחורה")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            downloadingMedia = false
                            MediaDownloadCoordinator.reportResult(MediaDownloadCoordinator.Result.SUCCESS)
                            goHomeToCloseWhatsAppChat()
                        }
                        canSwipeForMore -> {
                            // FIX (23.8.2026, full-album swipe): self-
                            // correct a wrong direction guess - if 2
                            // straight swipes produced no new file at all
                            // AND we haven't already flipped once, try the
                            // opposite direction instead of continuing to
                            // swipe the wrong way for the rest of the
                            // album.
                            if (noProgressSwipeCount >= 2 && !directionFlipped) {
                                swipeLeftToRight = !swipeLeftToRight
                                directionFlipped = true
                                noProgressSwipeCount = 0
                                EventLog.log("A11y-MediaDownload: 🔄 שתי החלקות רצופות בלי קובץ חדש - מהפך כיוון החלקה (עכשיו: ${if (swipeLeftToRight) "שמאל→ימין" else "ימין→שמאל"})")
                            }
                            EventLog.log("A11y-MediaDownload: 👉 מחליק לתמונה הבאה באלבום (${swipesAttempted + 1}/$maxSwipes) - נמצאו ${found.size}/$expectedAlbumSize עד כה")
                            mediaDownloadStage = 2
                            handler.post(this)
                        }
                        else -> {
                            EventLog.log("A11y-MediaDownload: ⏳ [+${elapsedSinceTap / 1000}s] נמצאו ${found.size}/$expectedAlbumSize קבצים, ממשיך להמתין...")
                            handler.postDelayed(this, MEDIA_DOWNLOAD_POLL_INTERVAL_MS)
                        }
                    }
                }
                2 -> {
                    // FIX (23.8.2026, full-album swipe): swipes within
                    // WhatsApp's full-screen album viewer to advance to
                    // the next item, then hands back to stage 1 to run
                    // the same "More options -> save" sequence for THAT
                    // item. UNVERIFIED ON A REAL DEVICE - the gesture
                    // itself, the exact swipe direction, and whether the
                    // viewer resets its "More options" state per-item all
                    // need confirming from a real log. If this stage
                    // never manages to increase found.size at all (both
                    // directions tried), stage 1's mustBackOutNow/timeout
                    // paths still guarantee we back out cleanly with
                    // whatever was actually captured - this can only add
                    // images on top of the old single-image behaviour,
                    // never regress below it.
                    if (currentViewerBounds == null) {
                        currentViewerBounds = findImageViewerBounds(root)
                    }
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    val bounds = currentViewerBounds ?: Rect(0, screenHeight / 5, screenWidth, screenHeight * 4 / 5)
                    performSwipeGesture(bounds, swipeLeftToRight) {
                        swipesAttempted++
                        saveMenuAttemptStep = 0
                        // Only re-dump the diagnostic trees for the first
                        // swipe - repeating a full tree dump per item
                        // would flood the log for a 5+ image album with
                        // little added value once the pattern is known.
                        if (swipesAttempted > 1) {
                            hasDumpedPostTapTree = true
                            hasDumpedMenuTree = true
                            hasDumpedAfterSaveTapTree = true
                        }
                        mediaDownloadStage = 1
                        // Give the swipe animation + WhatsApp's next-image
                        // load a moment to settle before immediately
                        // hunting for "More options" on what might still
                        // be mid-transition.
                        handler.postDelayed(mediaDownloadRunnable, 900L)
                    }
                }
            }
        }
    }

    /**
     * Finds the media bubble to tap. Primary strategy (FIX 21.8.2026):
     * search by the STABLE content-description WhatsApp actually uses
     * (MEDIA_BUBBLE_DESC_REGEX - confirmed via on-device full-tree dump
     * to be android.widget.Button, desc='הגדלת התמונה', NOT ImageView).
     * Falls back to the older bottommost-clickable-ImageView heuristic
     * only if no description match is found, as a safety net for
     * WhatsApp versions/locales where the description text differs.
     */
    /**
     * FIX (23.8.2026, full-album swipe): dispatches a real synthesized
     * horizontal drag across [bounds] - the only way to advance
     * WhatsApp's full-screen album viewer to the next item; there is no
     * clickable "next" button in the accessibility tree (confirmed by
     * the on-device dumps used to build the rest of this flow - only
     * back/star/forward/edit/more-options icons were ever found). Always
     * calls [onComplete] exactly once, whether the gesture actually
     * dispatched or not, so the caller never has to special-case a
     * dispatch failure.
     */
    private fun performSwipeGesture(bounds: Rect, leftToRight: Boolean, onComplete: () -> Unit) {
        try {
            val y = bounds.centerY().toFloat()
            val margin = (bounds.width() / 6).coerceAtLeast(40)
            val startX = if (leftToRight) (bounds.left + margin).toFloat() else (bounds.right - margin).toFloat()
            val endX = if (leftToRight) (bounds.right - margin).toFloat() else (bounds.left + margin).toFloat()
            val path = Path().apply {
                moveTo(startX, y)
                lineTo(endX, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
                .build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    EventLog.log("A11y-MediaDownload: ⚠️ מחוות ההחלקה בוטלה על ידי המערכת")
                    onComplete()
                }
            }, null)
            if (!dispatched) {
                EventLog.log("A11y-MediaDownload: ⚠️ dispatchGesture להחלקה החזיר false - ממשיך בכל זאת")
                onComplete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Swipe gesture failed", e)
            EventLog.log("A11y-MediaDownload: ❌ שגיאה בביצוע מחוות החלקה: ${e.javaClass.simpleName}: ${e.message}")
            onComplete()
        }
    }

    /**
     * Finds the on-screen bounds of the currently-displayed full-screen
     * image, to know where to swipe. Looks for the container node whose
     * own label/content-description matches IMAGE_VIEWER_CONTAINER_LABEL_REGEX
     * (seen on-device as a ViewGroup, label='הגדלת התמונה', spanning
     * almost the full screen). Returns null if not found - the caller
     * falls back to a generous full-width band in that case.
     */
    private fun findImageViewerBounds(root: AccessibilityNodeInfo): Rect? {
        val label = root.contentDescription?.toString() ?: ""
        if (IMAGE_VIEWER_CONTAINER_LABEL_REGEX.containsMatchIn(label)) {
            val r = Rect()
            root.getBoundsInScreen(r)
            return r
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findImageViewerBounds(child)?.let { return it }
        }
        return null
    }

    private fun findBottommostImageNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findNodeByDescription(root)?.let { return it }
        return findBottommostImageViewClassed(root)
    }

    private fun findNodeByDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo) {
            val desc = node.contentDescription?.toString() ?: ""
            if (MEDIA_BUBBLE_DESC_REGEX.containsMatchIn(desc)) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                val target = clickable ?: node
                target.getBoundsInScreen(rect)
                if (rect.bottom > bestBottom) {
                    bestBottom = rect.bottom
                    best = target
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child)
            }
        }

        visit(root)
        return best
    }

    /**
     * Picks the ImageView-class node whose on-screen bounds sit lowest
     * (largest bounds.bottom) - in a chat scrolled to the newest
     * message (which is how WhatsApp opens by default), that's the most
     * recently received media bubble. Only considers nodes that are
     * themselves clickable or have a clickable ancestor, since a bare
     * ImageView with no clickable wrapper can't be tapped meaningfully.
     * FALLBACK ONLY - see findBottommostImageNode's doc comment; the
     * real media bubble was confirmed NOT to be ImageView-classed, so
     * this heuristic is kept only as a safety net.
     */
    private fun findBottommostImageViewClassed(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo) {
            val cls = node.className?.toString() ?: ""
            if (cls.contains("ImageView", ignoreCase = true)) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable != null) {
                    // FIX (21.8.2026): skip known chrome/overlay icons
                    // (jump-to-bottom FAB, overflow menu, etc.) - see
                    // NON_MEDIA_ICON_DESC_REGEX doc comment. Confirmed via
                    // on-device log that these were previously winning
                    // this search instead of the real photo bubble.
                    val desc = (clickable.contentDescription?.toString() ?: "") +
                        " " + (node.contentDescription?.toString() ?: "")
                    if (NON_MEDIA_ICON_DESC_REGEX.containsMatchIn(desc)) {
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i) ?: continue
                            visit(child)
                        }
                        return
                    }
                    node.getBoundsInScreen(rect)
                    if (rect.bottom > bestBottom) {
                        bestBottom = rect.bottom
                        best = clickable
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child)
            }
        }

        visit(root)
        return best
    }

    /**
     * DIAGNOSTIC (21.8.2026): one-time full-tree dump used when the
     * ImageView-class search comes up empty for the actual photo bubble
     * (imgCount flat across the whole timeout on-device). Logs:
     *  1) a class-name histogram (top 8) - to see what class the bubble
     *     actually reports itself as, since it's clearly not ImageView
     *  2) every clickable node regardless of class, top-to-bottom, with
     *     class/text/desc/id/bounds - the real photo bubble should stand
     *     out by content-description (often includes "תמונה"/"photo")
     *     or by being roughly centered horizontally in the message list
     *     area rather than pinned to a screen edge like toolbar icons.
     * Capped output (8 classes, 15 clickable nodes) to avoid flooding
     * the ring buffer.
     */
    private fun dumpFullNodeTree(root: AccessibilityNodeInfo) {
        try {
            val classCounts = HashMap<String, Int>()
            data class ClickableInfo(
                val node: AccessibilityNodeInfo, val cls: String, val text: String, val desc: String,
                val id: String, val rect: Rect
            )
            val clickables = mutableListOf<ClickableInfo>()

            fun visit(node: AccessibilityNodeInfo) {
                val cls = node.className?.toString() ?: "(null)"
                classCounts[cls] = (classCounts[cls] ?: 0) + 1
                if (node.isClickable) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    clickables.add(
                        ClickableInfo(
                            node,
                            cls,
                            node.text?.toString() ?: "",
                            node.contentDescription?.toString() ?: "",
                            node.viewIdResourceName ?: "",
                            r
                        )
                    )
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    visit(child)
                }
            }
            visit(root)

            EventLog.log("A11y-MediaDownload: 🌳 אבחון מלא - היסטוגרמת classes (8 המובילים מתוך ${classCounts.size} סוגים שונים):")
            classCounts.entries.sortedByDescending { it.value }.take(8).forEach { (cls, count) ->
                EventLog.log("A11y-MediaDownload: 🌳   $count× $cls")
            }

            EventLog.log("A11y-MediaDownload: 🌳 אבחון מלא - ${clickables.size} nodes לחיצים סה\"כ, עד 15 (מסודר לפי מיקום אנכי):")
            clickables.sortedBy { it.rect.top }.take(15).forEach { c ->
                // FIX (23.8.2026): on-device dump of an OPEN menu showed
                // 8 clickable rows all logged as text='' desc='' - each
                // row is a plain clickable container wrapping a SEPARATE
                // TextView with the real label, which this per-node
                // listing never reached. Now falls back to searching the
                // node's own subtree for the first non-empty text/desc
                // when the node itself has none, so menu/list rows show
                // their real visible label instead of blank strings.
                val label = if (c.text.isNotEmpty() || c.desc.isNotEmpty()) {
                    null
                } else {
                    findDescendantTextMatchingRegex(c.node, Regex(".+"))
                }
                EventLog.log(
                    "A11y-MediaDownload: 🌳   class=${c.cls} text='${c.text}' " +
                        "desc='${c.desc}'" + (if (label != null) " label='$label'" else "") +
                        " id='${c.id}' bounds=${c.rect}"
                )
            }
        } catch (e: Exception) {
            EventLog.log("A11y-MediaDownload: 🌳 אבחון מלא נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * DIAGNOSTIC (21.8.2026): companion to findBottommostImageNode -
     * walks the same tree but collects EVERY clickable ImageView-class
     * candidate (not just the winner) and logs the 5 with the largest
     * bounds.bottom, each with its class/text/content-description/
     * resource-id/bounds. Purely for diagnosis; doesn't affect which
     * node actually gets tapped.
     */
    private fun logImageNodeCandidates(root: AccessibilityNodeInfo) {
        try {
            data class Candidate(val node: AccessibilityNodeInfo, val rect: Rect)
            val found = mutableListOf<Candidate>()

            fun visit(node: AccessibilityNodeInfo) {
                val cls = node.className?.toString() ?: ""
                if (cls.contains("ImageView", ignoreCase = true)) {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    if (clickable != null) {
                        val r = Rect()
                        clickable.getBoundsInScreen(r)
                        found.add(Candidate(clickable, r))
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    visit(child)
                }
            }
            visit(root)

            val top = found.sortedByDescending { it.rect.bottom }.take(5)
            EventLog.log("A11y-MediaDownload: 🔎 אבחון - ${found.size} מועמדי ImageView לחיצים, 5 התחתונים ביותר:")
            top.forEach { c ->
                EventLog.log(
                    "A11y-MediaDownload: 🔎 אבחון -   class=${c.node.className} " +
                        "text='${c.node.text}' desc='${c.node.contentDescription}' " +
                        "id=${c.node.viewIdResourceName} bounds=${c.rect}"
                )
            }
        } catch (e: Exception) {
            EventLog.log("A11y-MediaDownload: 🔎 אבחון מועמדים נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Finds the first scrollable node anywhere in the tree (e.g. the
     * RecyclerView backing WhatsApp's Group Info screen), so learnRunnable's
     * stage 1 can scroll it down to bring off-screen rows like "Invite via
     * link" into the accessibility tree.
     */
    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds a clickable node whose text or content-description matches the
     * given regex anywhere in the string (e.g. "5 חברים" / "5 members") -
     * used to jump straight to WhatsApp's dedicated Participants screen,
     * where "Invite via link" reliably sits near the top regardless of
     * member count.
     */
    /** Like findClickableMatchingRegex but returns the matched TEXT itself (from wherever it actually matched), not the clickable ancestor - useful for logging what a click actually targeted when the clickable container has no text of its own. */
    private fun findDescendantTextMatchingRegex(node: AccessibilityNodeInfo, regex: Regex): String? {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (text != null && regex.containsMatchIn(text)) return text
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDescendantTextMatchingRegex(child, regex)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableMatchingRegex(node: AccessibilityNodeInfo, regex: Regex): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (text != null && regex.containsMatchIn(text)) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable) return n
                n = n.parent
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableMatchingRegex(child, regex)
            if (found != null) return found
        }
        return null
    }

    /** Like findClickableByText but matches if the candidate is CONTAINED in the node's text (broader, last-resort). */
    private fun findClickableContaining(node: AccessibilityNodeInfo, candidates: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (node.isClickable && text != null) {
            for (candidate in candidates) {
                if (text.contains(candidate, ignoreCase = true)) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableContaining(child, candidates)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds a clickable node whose visible text or content-description
     * matches one of the given candidates (case-insensitive, exact or
     * "starts with" match) - used to find the group-invite intermediate
     * landing screen's CTA button.
     */
    private fun findClickableByText(node: AccessibilityNodeInfo, candidates: List<String>): AccessibilityNodeInfo? {
        val match = findTextMatch(node, candidates) ?: return null
        // Same fix as findSendButton: the matched text node might just be
        // a label inside a larger clickable container (e.g. the toolbar
        // title area) - walk up to the nearest actually-clickable
        // ancestor, since that's what needs to be clicked.
        var n: AccessibilityNodeInfo? = match
        while (n != null) {
            if (n.isClickable) return n
            n = n.parent
        }
        return null
    }

    private fun findTextMatch(node: AccessibilityNodeInfo, candidates: List<String>): AccessibilityNodeInfo? {
        // FIX45: strip Unicode bidi control chars before comparing - Android
        // wraps raw phone-number titles (unsaved contacts) in RLM/LRE/PDF
        // marks for RTL display, e.g. '\u200f\u202a+972 50-914-4971\u202c\u200f'.
        // Without stripping, this never matched a clean target like
        // '+972 50-914-4971', so phoneLearnRunnable's stage 0 (find/click the
        // contact header) always timed out for new private contacts - group
        // names aren't bidi-wrapped this way, so learnRunnable's identical
        // stage 0 never showed the bug.
        val text = node.text?.toString()?.let { stripBidiMarks(it) } ?: node.contentDescription?.toString()?.let { stripBidiMarks(it) }
        if (text != null) {
            for (candidate in candidates) {
                if (text.equals(candidate, ignoreCase = true) || text.startsWith(candidate, ignoreCase = true)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTextMatch(child, candidates)
            if (found != null) return found
        }
        return null
    }

    /**
     * Diagnostic helper: collects up to maxCount non-blank text/
     * content-description strings visible anywhere in the tree, so we
     * can see on-screen (via EventLog) what WhatsApp is actually
     * showing at the moment the search runs - without needing
     * Logcat/Android Studio.
     */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, maxCount: Int) {
        if (out.size >= maxCount) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrBlank() && t !in out) out.add(t)
        else if (!d.isNullOrBlank() && d !in out) out.add(d)
        for (i in 0 until node.childCount) {
            if (out.size >= maxCount) return
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, maxCount)
        }
    }

    /** Diagnostic: collects "class/id/text/desc" info for clickable nodes. */
    private fun collectClickableInfo(node: AccessibilityNodeInfo, out: MutableList<String>, maxCount: Int) {
        if (out.size >= maxCount) return
        if (node.isClickable) {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            out.add("$cls${if (id.isNotBlank()) "#$id" else ""}${if (text.isNotBlank()) "('$text')" else ""}")
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxCount) return
            val child = node.getChild(i) ?: continue
            collectClickableInfo(child, out, maxCount)
        }
    }

    /** Diagnostic: counts nodes anywhere in the tree whose class name contains the given substring. */
    private fun countMatchingClass(node: AccessibilityNodeInfo, substr: String): Int {
        var count = if (node.className?.contains(substr, ignoreCase = true) == true) 1 else 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += countMatchingClass(child, substr)
        }
        return count
    }

    /** Finds the first EditText-class node in the tree - WhatsApp's message box. */
    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Broadened again: countMatchingClass diagnostics confirmed
        // exactly one "Edit*"-classed node exists on the correct chat
        // screen, but it didn't match the stricter "EditText" substring -
        // so its real class name apparently contains "Edit" without
        // literally containing "EditText" (e.g. a custom subclass name).
        val cls = node.className?.toString()
        if (cls != null && cls.contains("Edit", ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Removes invisible Unicode bidi control characters (RLM, LRM, and
     * embedding/override/isolate marks) that Android inserts into
     * accessibility labels for mixed Hebrew/English text - the exact
     * same class of issue documented and fixed in Code.gs's own
     * stripBidiMarks() for incoming notification titles. Without this,
     * an exact string comparison against a plain "שליחה" silently fails
     * because the real label is actually "\u200Fשליחה" or similar.
     */
    private fun stripBidiMarks(s: String): String =
        s.replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]"), "")

    /**
     * Finds the send button. WhatsApp's internal resource id for this has
     * changed across versions historically, so we try several strategies
     * in order rather than relying on exactly one id string.
     */
    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidate = findSendButtonCandidate(node) ?: return null
        // The matched node (by id/text) might just be an inner icon/label
        // that isn't itself the actionable element - walk up to the
        // nearest ancestor that Android reports as actually clickable,
        // since that's the one performAction(ACTION_CLICK) needs to
        // target for the click to actually register with the app.
        var n: AccessibilityNodeInfo? = candidate
        while (n != null) {
            if (n.isClickable) return n
            n = n.parent
        }
        // No clickable node anywhere in the ancestor chain - this match
        // is useless (clicking it is guaranteed to fail), so report "not
        // found" rather than wasting a retry attempt on a dead click.
        return null
    }

    private fun findSendButtonCandidate(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Never match the entry/compose box itself as the "send button" -
        // if the message text being typed happens to contain the word
        // "שליחה"/"Send" (e.g. a test message like "בדיקה שליחה..."),
        // the EditText's OWN text would otherwise match our text-based
        // search below and get clicked instead of the real button. This
        // was confirmed to be exactly what was happening in testing.
        val cls = node.className?.toString()
        val isEntryField = cls != null && cls.contains("Edit", ignoreCase = true)

        // Strategy 1: resource id's last path segment equals "send"
        // exactly (e.g. "com.whatsapp:id/send") - "contains" was too
        // loose and matched unrelated buttons whose internal id happens
        // to contain the substring "send" (confirmed: an empty-compose-
        // box camera/attach-area button got matched this way while the
        // real Send button didn't exist yet).
        if (!isEntryField) {
            node.viewIdResourceName?.let { id ->
                val lastSegment = id.substringAfterLast('/')
                if (lastSegment.equals("send", ignoreCase = true)) return node
            }
            // Strategy 2: visible text OR content description EXACTLY
            // matching a known send-button label (not just "contains"!).
            // Diagnostics showed a "contains" match on "שלח" was
            // accidentally matching OLD messages' "נשלח" (delivery
            // status label, e.g. "Sent"/"נשלח") elsewhere in the chat
            // history, which also contains "שלח" as a substring - that
            // produced a completely wrong, unrelated, non-clickable
            // target. Exact match avoids this false-positive class
            // entirely.
            val label = node.text?.toString()?.let { stripBidiMarks(it).trim() }
                ?: node.contentDescription?.toString()?.let { stripBidiMarks(it).trim() }
            if (label != null) {
                val exactMatches = setOf("Send", "שלח", "שליחה")
                if (exactMatches.any { it.equals(label, ignoreCase = true) }) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButtonCandidate(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        EventLog.log("[${BuildInfo.BUILD_TAG}] A11y: השירות התחבר בהצלחה")
    }
}
