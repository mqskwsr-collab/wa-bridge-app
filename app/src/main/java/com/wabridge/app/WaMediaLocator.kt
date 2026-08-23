package com.wabridge.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Finds the actual media FILE WhatsApp wrote to disk for a just-arrived
 * notification. The notification itself never carries the real file
 * (at most a small compressed preview embedded in some cases), so this
 * reads directly from WhatsApp's own media folder.
 *
 * FIX (19.8.2026): on-device diagnostics showed the "new" post-2021
 * scoped-storage location (Android/media/com.whatsapp/WhatsApp/Media/)
 * exists but is completely EMPTY on this device/WhatsApp install - no
 * "WhatsApp" subfolder at all, meaning this install never migrated to
 * it and is still using the pre-2021 legacy root-level location. Both
 * are now tried, in order, since which one applies can genuinely differ
 * per device/WhatsApp version/install history:
 *
 *   1) Android/media/com.whatsapp/WhatsApp/Media/   (new, most current installs)
 *   2) WhatsApp/Media/                              (legacy, storage root)
 *
 *   each containing:
 *     ├── WhatsApp Images/
 *     ├── WhatsApp Video/
 *     └── WhatsApp Voice Notes/
 *
 * Requires MANAGE_EXTERNAL_STORAGE ("All files access", granted manually
 * via Settings - see MainActivity) rather than the narrower
 * READ_MEDIA_IMAGES/VIDEO/AUDIO permissions: WhatsApp is known to drop a
 * .nomedia marker in some of these subfolders on some devices, which
 * silently hides the files from MediaStore queries (what the narrower
 * permissions gate access through) while leaving them perfectly readable
 * via direct File access, which is what this class uses.
 *
 * MATCHING STRATEGY: there is no reliable per-chat identifier available
 * from the filesystem side (WhatsApp organizes media by TYPE, not by
 * chat/sender), so this simply returns the newest file in the relevant
 * subfolder whose lastModified() falls within a short window of the
 * notification's arrival. KNOWN LIMITATION: if two media messages of the
 * same type arrive from different chats within that same short window,
 * this can pick the wrong one. Acceptable for a low-volume personal/family
 * bridge; not something to over-engineer for now.
 */
object WaMediaLocator {

    private const val TAG = "WaBridgeMedia"

    private val CANDIDATE_BASE_PATHS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media", // new (post-2021 scoped storage) - WhatsApp's own auto-download cache
        "WhatsApp/Media",                              // legacy (storage root) - WhatsApp's own auto-download cache
        // FIX (22.8.2026): on-device log confirmed the accessibility
        // flow now genuinely finds and taps the real "שמירה"/"Save"
        // menu item (see FIX51/52 doc comments in
        // WaSendAccessibilityService) - but STILL nothing showed up in
        // either path above afterward. That's expected: a manual "Save"
        // from the full-screen viewer's overflow menu writes a COPY to
        // the device's regular Gallery, a different location entirely
        // from WhatsApp's own auto-download cache (which is what the
        // two paths above are). Gallery saves commonly land under
        // Pictures/WhatsApp Images or DCIM/WhatsApp Images depending on
        // Android version/OEM - both are tried now. (For non-image
        // types these combine into paths that don't exist, e.g.
        // "Pictures/WhatsApp Voice Notes" - harmless, just skipped.)
        "Pictures",
        "DCIM"
    )
    private const val SUBFOLDER_IMAGES = "WhatsApp Images"
    private const val SUBFOLDER_VIDEO = "WhatsApp Video"
    private const val SUBFOLDER_VOICE_NOTES = "WhatsApp Voice Notes"

    // How far back (and slightly forward, to absorb clock/IO ordering
    // slack between "file written" and "notification posted") from the
    // notification's arrival time to consider a file a match. Widened
    // from 15s to 30s (19.8.2026) - real-device testing on the legacy
    // path found the folder but nothing inside the original window;
    // the diagnostic added alongside this will confirm the exact real
    // gap so this can be tuned precisely instead of guessed again.
    private const val MATCH_WINDOW_MS = 30_000L

    data class FoundMedia(val file: File, val mimeType: String)

    fun isAvailable(context: Context): Boolean {
        // FIX (21.8.2026): was hardcoded `true` below API 30 with the
        // reasoning "permission concept doesn't exist pre-Android 11" -
        // true for MANAGE_EXTERNAL_STORAGE specifically, but WRONG in
        // general: READ_EXTERNAL_STORAGE is still a required runtime
        // (dangerous) permission on API 23-29, and this app never
        // declared or requested it (see AndroidManifest.xml fix, same
        // date). On a pre-Android-11 device that never got asked for it,
        // this hardcoded `true` was a straight-up lie that made every
        // subsequent listFiles() call silently return empty - exactly
        // the "0 files" symptom confirmed on-device, including for
        // long-pre-existing files a separate file manager could see
        // just fine. Now actually checks the real OS-reported state on
        // both branches instead of assuming.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns the best-guess file for this media type near the given
     * notification timestamp, or null if the permission isn't granted,
     * no candidate folder exists, or nothing recent enough was found.
     */
    fun findRecentMediaFile(context: Context, type: MediaClassifier.MediaType, notificationTimeMs: Long, matchWindowMs: Long = MATCH_WINDOW_MS): FoundMedia? {
        if (type == MediaClassifier.MediaType.NONE) return null

        if (!isAvailable(context)) {
            Log.w(TAG, "Storage read permission not granted - cannot locate media file")
            EventLog.log("Media: ⚠️ אין הרשאת קריאת אחסון - לא ניתן לאתר את קובץ המדיה")
            return null
        }

        val subfolder = when (type) {
            MediaClassifier.MediaType.IMAGE -> SUBFOLDER_IMAGES
            MediaClassifier.MediaType.VIDEO -> SUBFOLDER_VIDEO
            MediaClassifier.MediaType.VOICE_NOTE -> SUBFOLDER_VOICE_NOTES
            MediaClassifier.MediaType.NONE -> return null
        }

        val root = Environment.getExternalStorageDirectory()
        // FIX (22.8.2026): this used to stop at the FIRST candidate
        // folder that merely EXISTED (isDirectory), even if nothing in
        // it matched - meaning once the legacy WhatsApp/Media path was
        // found to exist (which it always was, with old cached files),
        // no other candidate (including the newly-added Gallery paths
        // above) would ever even get checked. Now checks every existing
        // candidate folder and picks the best (newest) match across all
        // of them, so a real match in a later candidate isn't hidden by
        // an earlier candidate that merely exists but has nothing recent.
        val triedDirs = mutableListOf<File>()
        var bestFile: File? = null
        var bestDir: File? = null

        for (basePath in CANDIDATE_BASE_PATHS) {
            val candidateDir = File(root, "$basePath/$subfolder")
            if (!candidateDir.isDirectory) continue
            triedDirs.add(candidateDir)

            val filesHere = candidateDir.listFiles { f -> f.isFile } ?: emptyArray()
            if (filesHere.isEmpty()) {
                // DIAGNOSTIC (21.8.2026): on-device logs show this folder
                // reporting 0 files EVERY time, including for files that are
                // NOT new (72 pre-existing files confirmed present via a
                // separate file manager app, some days old) - so this isn't
                // "new file not visible yet", listFiles() is failing to see
                // ANY of the directory's contents. That points at a
                // permission problem masquerading as an empty folder, not a
                // path or timing problem. Log the raw signals needed to tell
                // apart the possible causes: is isExternalStorageManager()
                // actually true, can we even read/execute the dir, and does
                // the plain String[] list() (different underlying code path
                // than listFiles()) agree or disagree with listFiles().
                EventLog.log(
                    "Media: 🔎 אבחון הרשאות (${candidateDir.absolutePath}) - sdkInt=${Build.VERSION.SDK_INT} isExternalStorageManager=${isAvailable(context)} " +
                        "dir.exists=${candidateDir.exists()} dir.canRead=${candidateDir.canRead()} dir.canExecute=${candidateDir.canExecute()} " +
                        "list()=${candidateDir.list()?.size ?: "null"} listFiles()=${filesHere.size}"
                )
            }

            val matchHere = filesHere
                .filter { kotlin.math.abs(it.lastModified() - notificationTimeMs) <= matchWindowMs }
                .maxByOrNull { it.lastModified() }
            if (matchHere != null && (bestFile == null || matchHere.lastModified() > bestFile!!.lastModified())) {
                bestFile = matchHere
                bestDir = candidateDir
            }
        }

        if (triedDirs.isEmpty()) {
            val tried = CANDIDATE_BASE_PATHS.joinToString(" , ") { File(root, "$it/$subfolder").absolutePath }
            Log.w(TAG, "Media folder not found in any candidate path: $tried")
            EventLog.log("Media: ⚠️ תיקיית מדיה לא נמצאה באף אחד מהנתיבים: $tried")
            logDiagnostics(root)
            return null
        }

        val best = bestFile

        if (best == null) {
            Log.w(TAG, "No recent file matched in any of: ${triedDirs.joinToString(" , ") { it.absolutePath }} within ${matchWindowMs}ms of $notificationTimeMs")
            EventLog.log("Media: ⚠️ לא נמצא קובץ תואם בזמן באף אחת מ-${triedDirs.size} תיקיות שנבדקו")
            triedDirs.forEach { d -> logCandidateTimings(d, d.listFiles { f -> f.isFile } ?: emptyArray(), notificationTimeMs) }
            return null
        }
        val dir = bestDir!!

        val mimeType = guessMimeType(best.name)
        Log.i(TAG, "Matched media file: ${best.absolutePath} (mime=$mimeType)")
        EventLog.log("Media: ✅ נמצא קובץ: ${best.name} (ב-${dir.absolutePath})")
        return FoundMedia(best, mimeType)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    /**
     * DIAGNOSTIC (19.8.2026) - neither candidate base path panned out
     * (confirmed on-device: Android/media/com.whatsapp exists but is
     * completely empty, meaning that install never migrated to it - see
     * class doc comment). Rather than add a third guessed candidate path,
     * this does a broad, case-insensitive scan of the storage root for
     * ANY folder whose name contains "whatsapp", and logs what it finds -
     * covering unexpected casing/naming we haven't anticipated, so the
     * real answer can be read straight from EventLog.
     */
    private fun logDiagnostics(root: File) {
        try {
            val rootChildren = root.list()?.sorted() ?: emptyList()
            EventLog.log("Media: 🔎 אבחון - תוכן שורש האחסון (${root.absolutePath}): ${if (rootChildren.isEmpty()) "(ריקה/לא נגיש)" else rootChildren.joinToString(" | ")}")

            val whatsappLike = rootChildren.filter { it.contains("whatsapp", ignoreCase = true) }
            if (whatsappLike.isNotEmpty()) {
                EventLog.log("Media: 🔎 אבחון - תיקיות שמכילות \"whatsapp\" בשורש: ${whatsappLike.joinToString(" | ")}")
                whatsappLike.forEach { name ->
                    val sub = File(root, name)
                    val subChildren = sub.list()?.sorted() ?: emptyList()
                    EventLog.log("Media: 🔎 אבחון - תוכן \"$name\": ${if (subChildren.isEmpty()) "(ריקה)" else subChildren.joinToString(" | ")}")
                }
            }

            val androidMedia = File(root, "Android/media")
            val androidMediaChildren = androidMedia.list()?.sorted() ?: emptyList()
            val waPkgLike = androidMediaChildren.filter { it.contains("whatsapp", ignoreCase = true) }
            EventLog.log("Media: 🔎 אבחון - חבילות עם \"whatsapp\" תחת Android/media: ${if (waPkgLike.isEmpty()) "(אין)" else waPkgLike.joinToString(" | ")}")
        } catch (e: Exception) {
            EventLog.log("Media: 🔎 אבחון נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * DIAGNOSTIC (19.8.2026) - the folder itself was found this time
     * (legacy path confirmed working), but nothing matched within the
     * 15s window. Logs the actual candidate files and exactly how far
     * off (in seconds) each one's lastModified() is from the
     * notification's postTime - so we can tell, from real numbers,
     * whether MATCH_WINDOW_MS just needs widening or something else is
     * going on (e.g. empty folder, or timestamps wildly off).
     */
    private fun logCandidateTimings(dir: File, candidates: Array<File>, notificationTimeMs: Long) {
        try {
            if (candidates.isEmpty()) {
                EventLog.log("Media: 🔎 אבחון - התיקייה \"${dir.absolutePath}\" ריקה לגמרי (0 קבצים)")
                return
            }
            val newest = candidates.sortedByDescending { it.lastModified() }.take(5)
            EventLog.log("Media: 🔎 אבחון - ${candidates.size} קבצים בתיקייה, 5 החדשים ביותר:")
            newest.forEach { f ->
                val diffSec = (f.lastModified() - notificationTimeMs) / 1000.0
                val sign = if (diffSec >= 0) "+" else ""
                EventLog.log("Media: 🔎 אבחון -   ${f.name} (הפרש מההתראה: $sign${"%.1f".format(diffSec)} שנ')")
            }
        } catch (e: Exception) {
            EventLog.log("Media: 🔎 אבחון נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
