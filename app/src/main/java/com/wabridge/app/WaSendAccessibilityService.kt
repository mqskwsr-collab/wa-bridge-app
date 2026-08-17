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

    companion object {
        private const val TAG = "WaBridgeA11y"
        private const val SEARCH_TIMEOUT_MS = 15000L
        private const val SEARCH_INTERVAL_MS = 400L
        private const val MAX_TAP_ATTEMPTS = 8
        private const val LEARN_TIMEOUT_MS = 15000L
    }

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

    // --- Phone-number learning state (separate from both flows above) ---
    private var phoneLearning = false
    private var phoneLearnStartTime = 0L
    private var phoneLearnStage = 0 // 0=find/click contact header, 1=scan for a phone-shaped string
    private var lastPhoneLearnDumpTime = 0L

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
                    } else {
                        handler.postDelayed(this, SEARCH_INTERVAL_MS)
                    }
                }
            }
        }
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
        val text = node.text?.toString() ?: node.contentDescription?.toString()
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
