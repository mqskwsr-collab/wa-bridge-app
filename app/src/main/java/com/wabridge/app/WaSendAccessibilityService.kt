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
        private const val SEARCH_TIMEOUT_MS = 10000L
        private const val SEARCH_INTERVAL_MS = 400L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var searching = false
    private var searchStartTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!SendCoordinator.hasPendingJob()) return
        if (searching) return // already trying for the current job

        Log.i(TAG, "WhatsApp window state changed and a job is pending - starting search")
        EventLog.log("A11y: חלון וואטסאפ השתנה, מתחיל לחפש שדה הודעה+שליחה")
        searching = true
        searchStartTime = System.currentTimeMillis()
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
                EventLog.log("A11y: ❌ Timeout - לא נמצא שדה הודעה/שליחה תוך 10 שניות")
                searching = false
                SendCoordinator.reportResult(SendCoordinator.Result.TIMEOUT)
                return
            }

            val root = rootInActiveWindow
            if (root == null) {
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            val entryNode = findEditText(root)
            val sendNode = findSendButton(root)

            if (entryNode == null) {
                // Screen probably still loading (or this is not the chat
                // screen, e.g. a "join group" preview) - keep retrying
                // until timeout.
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            if (sendNode == null) {
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            Log.i(TAG, "Found entry + send nodes - typing and sending")
            EventLog.log("A11y: נמצאו שני השדות, מקליד טקסט...")
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                job.text
            )
            val typed = entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!typed) {
                Log.w(TAG, "ACTION_SET_TEXT failed on entry node")
                EventLog.log("A11y: ⚠️ הקלדה נכשלה, מנסה שוב...")
                handler.postDelayed(this, SEARCH_INTERVAL_MS)
                return
            }

            // Give WhatsApp a brief moment to enable the send button after
            // text is entered (some versions disable it for empty input).
            handler.postDelayed({
                val freshRoot = rootInActiveWindow
                val freshSend = freshRoot?.let { findSendButton(it) } ?: sendNode
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
            }, 500)
        }
    }

    /** Finds the first EditText-class node in the tree - WhatsApp's message box. */
    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") return node
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
        // Strategy 2: content description matching "Send" (English) or
        // the Hebrew equivalent "שלח" - WhatsApp's UI language follows
        // the phone's system language, which may differ from the chat
        // content's language.
        node.contentDescription?.toString()?.let { desc ->
            if (desc.equals("Send", ignoreCase = true) || desc.contains("שלח")) return node
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
        EventLog.log("A11y: השירות התחבר בהצלחה")
    }
}
