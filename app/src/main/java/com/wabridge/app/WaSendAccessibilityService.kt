package com.wabridge.app

import android.accessibilityservice.AccessibilityService
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!SendCoordinator.hasPendingJob()) return
        if (searching) return // already trying for the current job

        Log.i(TAG, "WhatsApp window state changed and a job is pending - starting search")
        EventLog.log("A11y: חלון וואטסאפ השתנה, מתחיל לחפש שדה הודעה+שליחה")
        searching = true
        searchStartTime = System.currentTimeMillis()
        clickedIntermediateScreen = false
        lastDiagnosticDumpTime = 0L
        lastSendDumpTime = 0L
        handler.post(searchRunnable)
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
        val freshRoot = rootInActiveWindow
        val freshSend = freshRoot?.let { findSendButton(it) }
        if (freshSend != null) {
            val clicked = freshSend.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            searching = false
            if (clicked) {
                Log.i(TAG, "Send button clicked successfully")
                EventLog.log("A11y: ✅ נלחץ כפתור שליחה בהצלחה")
                SendCoordinator.reportResult(SendCoordinator.Result.SUCCESS)
            } else {
                Log.w(TAG, "Send button click failed")
                EventLog.log("A11y: ❌ לחיצה על כפתור שליחה נכשלה")
                SendCoordinator.reportResult(SendCoordinator.Result.FAILED_NO_SEND_BUTTON)
            }
            return
        }

        if (attempt >= 6) {
            Log.w(TAG, "Send button still not found after typing (all retries exhausted)")
            val root = rootInActiveWindow
            val entryNowText = root?.let { findEditText(it) }?.text?.toString() ?: "?"
            val clickables = mutableListOf<String>()
            if (root != null) collectClickableInfo(root, clickables, maxCount = 20)
            EventLog.log("A11y: ❌ כפתור שליחה לא נמצא אחרי הקלדה. תוכן entry עכשיו='$entryNowText' | כפתורים: ${clickables.joinToString(" | ")}")
            searching = false
            SendCoordinator.reportResult(SendCoordinator.Result.FAILED_NO_SEND_BUTTON)
            return
        }

        handler.postDelayed({ searchForSendButtonAfterTyping(attempt + 1) }, 400)
    }

    /**
     * Finds a clickable node whose visible text or content-description
     * matches one of the given candidates (case-insensitive, exact or
     * "starts with" match) - used to find the group-invite intermediate
     * landing screen's CTA button.
     */
    private fun findClickableByText(node: AccessibilityNodeInfo, candidates: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (node.isClickable && text != null) {
            for (candidate in candidates) {
                if (text.equals(candidate, ignoreCase = true) || text.startsWith(candidate, ignoreCase = true)) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByText(child, candidates)
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
     * Finds the send button. WhatsApp's internal resource id for this has
     * changed across versions historically, so we try several strategies
     * in order rather than relying on exactly one id string.
     */
    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: resource id containing "send"
        node.viewIdResourceName?.let { id ->
            if (id.contains("send", ignoreCase = true)) return node
        }
        // Strategy 2: visible text OR content description matching
        // "Send" (English) or a Hebrew form - diagnostics confirmed
        // WhatsApp's actual button text is "שליחה" (a noun, "sending"),
        // not "שלח" (the imperative verb) which was checked before and
        // is NOT a substring of "שליחה" - both are matched now.
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        label?.let { l ->
            if (l.equals("Send", ignoreCase = true) || l.contains("שלח") || l.contains("שליחה")) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
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
