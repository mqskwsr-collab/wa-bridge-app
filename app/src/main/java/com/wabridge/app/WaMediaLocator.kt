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
 * reads directly from WhatsApp's own media folder:
 *
 *   Android/media/com.whatsapp/WhatsApp/Media/
 *     ├── WhatsApp Images/
 *     ├── WhatsApp Video/
 *     └── WhatsApp Voice Notes/
 *
 * (this is the "new", post-scoped-storage location WhatsApp moved to
 * around 2021-2022 - the old /WhatsApp/Media/ path at the storage root
 * no longer receives new files on any remotely current WhatsApp version).
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

    private const val BASE_PATH = "Android/media/com.whatsapp/WhatsApp/Media"
    private const val SUBFOLDER_IMAGES = "WhatsApp Images"
    private const val SUBFOLDER_VIDEO = "WhatsApp Video"
    private const val SUBFOLDER_VOICE_NOTES = "WhatsApp Voice Notes"

    // How far back (and slightly forward, to absorb clock/IO ordering
    // slack between "file written" and "notification posted") from the
    // notification's arrival time to consider a file a match.
    private const val MATCH_WINDOW_MS = 15_000L

    data class FoundMedia(val file: File, val mimeType: String)

    fun isAvailable(): Boolean {
        // Environment.isExternalStorageManager() only exists from API 30
        // (Android 11) onward - minSdk for this app is 26, so this must
        // be version-guarded or it crashes on older devices with a
        // NoSuchMethodError. Below API 30, MANAGE_EXTERNAL_STORAGE isn't
        // a thing yet and legacy external storage access rules apply
        // instead; treating it as "available" there is fine since this
        // whole media feature is a best-effort add-on that already fails
        // safe (see attachMediaIfAny's try/catch) if direct file access
        // turns out not to work on such a device.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return Environment.isExternalStorageManager()
    }

    /**
     * Returns the best-guess file for this media type near the given
     * notification timestamp, or null if the permission isn't granted,
     * the folder doesn't exist, or nothing recent enough was found.
     */
    fun findRecentMediaFile(context: Context, type: MediaClassifier.MediaType, notificationTimeMs: Long): FoundMedia? {
        if (type == MediaClassifier.MediaType.NONE) return null

        if (!isAvailable()) {
            Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted - cannot locate media file")
            EventLog.log("Media: ⚠️ אין הרשאת \"כל הקבצים\" - לא ניתן לאתר את קובץ המדיה")
            return null
        }

        val subfolder = when (type) {
            MediaClassifier.MediaType.IMAGE -> SUBFOLDER_IMAGES
            MediaClassifier.MediaType.VIDEO -> SUBFOLDER_VIDEO
            MediaClassifier.MediaType.VOICE_NOTE -> SUBFOLDER_VOICE_NOTES
            MediaClassifier.MediaType.NONE -> return null
        }

        val dir = File(Environment.getExternalStorageDirectory(), "$BASE_PATH/$subfolder")
        if (!dir.isDirectory) {
            Log.w(TAG, "Media folder not found: ${dir.absolutePath}")
            EventLog.log("Media: ⚠️ תיקיית מדיה לא נמצאה: ${dir.absolutePath}")
            return null
        }

        val candidates = dir.listFiles { f -> f.isFile } ?: emptyArray()
        val best = candidates
            .filter { kotlin.math.abs(it.lastModified() - notificationTimeMs) <= MATCH_WINDOW_MS }
            .maxByOrNull { it.lastModified() }

        if (best == null) {
            Log.w(TAG, "No recent file matched in ${dir.absolutePath} within ${MATCH_WINDOW_MS}ms of $notificationTimeMs")
            EventLog.log("Media: ⚠️ לא נמצא קובץ תואם בזמן ב-\"$subfolder\"")
            return null
        }

        val mimeType = guessMimeType(best.name)
        Log.i(TAG, "Matched media file: ${best.absolutePath} (mime=$mimeType)")
        EventLog.log("Media: ✅ נמצא קובץ: ${best.name}")
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
}
